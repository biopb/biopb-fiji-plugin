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

    // 4. The real gRPC/netty/TLS path on Fiji's own netty (jars.sh checks the install left it alone):
    //    an in-process Flight server on 127.0.0.1 answering what the SDK asks of a tensor server, reached
    //    (a) by a raw flight-core TLS client and (b) through the plugin's ServerConnector, over TLS and in
    //    plaintext. A NoClassDefFoundError / NoSuchMethodError anywhere fails the test.
    //    The SDK verifies a loopback TLS server against $BIOPB_STATE_HOME/biopb/tls/server-cert.pem, so
    //    this needs BIOPB_STATE_HOME pointing at a scratch directory (never the real state dir).
    def stateHome = System.getenv("BIOPB_STATE_HOME")
    if (!stateHome) throw new RuntimeException("set BIOPB_STATE_HOME to a scratch directory for the TLS smoke test")
    def quietly = { Closure c -> try { c() } catch (Throwable ignored) { } } // cleanup must not mask the real error
    def dir = java.nio.file.Files.createTempDirectory("biopb-smoke").toFile()
    def stateCert = new File(stateHome, "biopb/tls/server-cert.pem")
    RootAllocator alloc2 = null
    def tlsServer = null, plainServer = null
    try {
        def cert = new File(dir, "cert.pem"), key = new File(dir, "key.pem")
        def gen = ["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1", "-subj", "/CN=localhost",
                   "-addext", "subjectAltName=DNS:localhost,IP:127.0.0.1", "-keyout", key.path, "-out", cert.path].execute()
        gen.waitForProcessOutput(new StringBuffer(), new StringBuffer())
        if (gen.exitValue() != 0 || !cert.exists()) throw new RuntimeException("openssl could not make a certificate")
        stateCert.parentFile.mkdirs()
        stateCert.bytes = cert.bytes
        alloc2 = new RootAllocator(Long.MAX_VALUE)
        def allocator = alloc2
        def producer = new NoOpFlightProducer() {
            void listActions(FlightProducer.CallContext c, FlightProducer.StreamListener<ActionType> l) {
                l.onNext(new ActionType("health", "health")); l.onCompleted()
            }
            void doAction(FlightProducer.CallContext c, Action a, FlightProducer.StreamListener<Result> l) {
                l.onNext(new Result('{"status": "SERVING", "protocol": 2}'.getBytes("UTF-8"))); l.onCompleted()
            }
            void getStream(FlightProducer.CallContext c, Ticket t, FlightProducer.ServerStreamListener l) { // the SDK's SELECT
                def schema = new org.apache.arrow.vector.types.pojo.Schema([
                    org.apache.arrow.vector.types.pojo.Field.nullable("x", new org.apache.arrow.vector.types.pojo.ArrowType.Int(32, true))])
                def root = org.apache.arrow.vector.VectorSchemaRoot.create(schema, allocator)
                try { l.start(root); root.setRowCount(0); l.putNext(); l.completed() } finally { root.close() }
            }
        }
        tlsServer = FlightServer.builder(alloc2, Location.forGrpcTls("127.0.0.1", 0), producer).useTls(cert, key).build().start()
        plainServer = FlightServer.builder(alloc2, Location.forGrpcInsecure("127.0.0.1", 0), producer).build().start()
        def netty = io.netty.util.Version.identify().get("netty-common").artifactVersion()

        def trusted = new FileInputStream(cert)
        try {
            def raw = FlightClient.builder(alloc2, Location.forGrpcTls("127.0.0.1", tlsServer.port)).trustedCertificates(trusted).build()
            try {
                def names = raw.listActions().collect { it.type }
                if (names != ["health"]) throw new RuntimeException("raw TLS listActions answered " + names)
            } finally { raw.close() }
        } finally { trusted.close() }
        println("OK raw TLS Flight round trip (netty " + netty + ")")

        // the plugin's own path: no real pin store (the SDK's connection over an in-memory one)
        def connector = io.github.jiyuuchc.biopb.fiji.ServerConnector.over({ new biopb.tensor.Connection(biopb.tensor.TlsPinStore.inMemory()) })
        [["grpc+tls://127.0.0.1:" + tlsServer.port, "TLS"], ["grpc://127.0.0.1:" + plainServer.port, "plaintext"]].each { url, kind ->
            def conn = connector.connect(url, null)
            try {
                if (conn.flight().healthCheck().status != "SERVING") throw new RuntimeException(kind + " health did not answer SERVING")
            } finally { conn.close() }
            println("OK ServerConnector " + kind + " round trip")
        }
    } finally {
        quietly { tlsServer?.close() }
        quietly { plainServer?.close() }
        quietly { alloc2?.close() }
        quietly { stateCert.delete() }
        quietly { dir.deleteDir() } // takes key.pem with it
    }
} catch (Throwable t) {
    t.printStackTrace()
    System.err.println("SMOKE FAILED: " + t)
    System.exit(1)
}
System.exit(0)
