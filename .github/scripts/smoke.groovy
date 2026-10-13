// Headless smoke test run inside Fiji: ./Fiji.app/fiji-linux-x64 --headless --run smoke.groovy
// Exits non-zero on any failure.
#@ CommandService cs
import org.apache.arrow.flight.*
import org.apache.arrow.memory.RootAllocator
try {
    // 1. the command is discovered with its menu path
    def info = cs.getCommand("io.github.jiyuuchc.biopb.fiji.TensorBrowserCommand")
    if (info == null) throw new RuntimeException("TensorBrowserCommand not discovered by CommandService")
    def path = info.getMenuPath().getMenuString().replaceAll("\\s*>\\s*", ">")
    if (path != "Plugins>BioPB>Tensor Browser") throw new RuntimeException("unexpected menu path: " + path)
    println("OK command: " + path)

    // 1b. the plugin's own classes load
    ["TensorOpener", "OpenFailure", "TensorBrowserController", "TensorBrowserWindow", "AxisMap",
     "RefCountedConnection"].each {
        Class.forName("io.github.jiyuuchc.biopb.fiji." + it)
    }
    println("OK plugin classes load")

    // 1c. pure logic: AxisMap on sample labels, and its typed failure
    def am = io.github.jiyuuchc.biopb.fiji.AxisMap.resolve(["t", "c", "y", "x"], [2L, 3L, 8L, 9L] as long[], null)
    if (am.roles() != "tcyx") {
        throw new RuntimeException("unexpected roles: " + am.roles())
    }
    try {
        io.github.jiyuuchc.biopb.fiji.AxisMap.resolve([], [3L, 4L, 5L, 6L] as long[], null)
        throw new RuntimeException("AxisMap accepted unlabeled 4-d axes")
    } catch (io.github.jiyuuchc.biopb.fiji.AxisMappingException e) {
        if (e.getReason() != io.github.jiyuuchc.biopb.fiji.AxisMappingException.Reason.NEEDS_OVERRIDE) throw e
    }
    println("OK AxisMap logic")

    // 1d. running the command headless is a no-op (the headless guard), not an error
    def run = cs.run(Class.forName("io.github.jiyuuchc.biopb.fiji.TensorBrowserCommand"), true).get()
    if (run == null || run.isCanceled()) throw new RuntimeException("command did not run: " + run?.getCancelReason())
    println("OK command runs headless")

    // 2. the SDK class loads (its dependencies resolve)
    Class.forName("biopb.tensor.TensorFlightClient")
    println("OK TensorFlightClient loads")

    // 3. Arrow memory works: needs java.nio opened by Fiji's launcher
    def alloc = new org.apache.arrow.memory.RootAllocator(Long.MAX_VALUE)
    try {
        def buf = alloc.buffer(64)
        buf.setLong(0, 42L)
        if (buf.getLong(0) != 42L) throw new RuntimeException("ArrowBuf round trip failed")
        buf.close()
    } finally {
        alloc.close()
    }
    println("OK Arrow allocation")

    // 4. Real gRPC/netty path on Fiji's own netty (jars.sh checks the install left it alone): an
    //    in-process Flight server on loopback, once over TLS (self-signed cert from openssl) and once in
    //    plaintext through the SDK client. Any NoClassDefFoundError / NoSuchMethodError fails the test.
    def dir = java.nio.file.Files.createTempDirectory("biopb-smoke").toFile()
    def cert = new File(dir, "cert.pem"), key = new File(dir, "key.pem")
    def gen = ["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1", "-subj", "/CN=localhost",
               "-addext", "subjectAltName=DNS:localhost", "-keyout", key.path, "-out", cert.path].execute()
    gen.waitForProcessOutput(new StringBuffer(), new StringBuffer())
    if (gen.exitValue() != 0 || !cert.exists()) throw new RuntimeException("openssl could not make a certificate")
    def producer = new NoOpFlightProducer() {
        void listActions(FlightProducer.CallContext c, FlightProducer.StreamListener<ActionType> l) {
            l.onNext(new ActionType("health", "health")); l.onCompleted()
        }
        void doAction(FlightProducer.CallContext c, Action a, FlightProducer.StreamListener<Result> l) {
            l.onNext(new Result('{"status": "SERVING"}'.getBytes("UTF-8"))); l.onCompleted()
        }
    }
    def alloc2 = new RootAllocator(Long.MAX_VALUE)
    def serve = { Location loc, boolean tls ->
        def b = FlightServer.builder(alloc2, loc, producer)
        if (tls) b.useTls(cert, key)
        return b.build().start()
    }
    def tlsServer = serve(Location.forGrpcTls("localhost", 0), true)
    def plainServer = serve(Location.forGrpcInsecure("localhost", 0), false)
    try {
        def tlsClient = FlightClient.builder(alloc2, Location.forGrpcTls("localhost", tlsServer.port))
                .trustedCertificates(new FileInputStream(cert)).build()
        try {
            def names = tlsClient.listActions().collect { it.type }
            if (names != ["health"]) throw new RuntimeException("TLS listActions answered " + names)
        } finally { tlsClient.close() }
        println("OK TLS Flight round trip (netty " + io.netty.util.Version.identify().get("netty-common").artifactVersion() + ")")
        def sdk = new biopb.tensor.TensorFlightClient(Location.forGrpcInsecure("localhost", plainServer.port))
        try {
            def h = sdk.healthCheck()
            if (h.status != "SERVING") throw new RuntimeException("plaintext health answered " + h)
        } finally { sdk.close() }
        println("OK plaintext SDK round trip")
    } finally {
        tlsServer.close(); plainServer.close(); alloc2.close(); dir.deleteDir()
    }
} catch (Throwable t) {
    t.printStackTrace()
    System.err.println("SMOKE FAILED: " + t)
    System.exit(1)
}
System.exit(0)
