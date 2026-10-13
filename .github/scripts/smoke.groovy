// Headless smoke test run inside Fiji: ./Fiji.app/fiji-linux-x64 --headless --run smoke.groovy
// Exits non-zero on any failure.
#@ CommandService cs
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

    // 4. TLS: the SDK connection loads against Fiji's own netty (jars.sh checks the install left it alone);
    //    gRPC falls back to the JDK TLS provider when netty's OpenSSL (tcnative) is unavailable.
    Class.forName("biopb.tensor.Connection")
    Class.forName("biopb.tensor.TlsTrusts")
    def netty = io.netty.util.Version.identify().get("netty-common").artifactVersion()
    println("OK TLS classes load (Fiji netty " + netty + ", OpenSSL " + io.netty.handler.ssl.OpenSsl.isAvailable() + ")")
} catch (Throwable t) {
    t.printStackTrace()
    System.err.println("SMOKE FAILED: " + t)
    System.exit(1)
}
System.exit(0)
