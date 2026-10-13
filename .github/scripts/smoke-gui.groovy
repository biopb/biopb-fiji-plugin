// GUI smoke test run inside Fiji on a virtual display (xvfb), NOT headless:
// builds the real Tensor Browser window through the command. Exits non-zero on failure.
#@ CommandService cs
try {
    cs.run(Class.forName("io.github.jiyuuchc.biopb.fiji.TensorBrowserCommand"), true).get()
    // the window is created with invokeLater: wait for the EDT to drain
    javax.swing.SwingUtilities.invokeAndWait({ } as Runnable)
    def title = "BioPB Tensor Browser"
    def frames = java.awt.Frame.getFrames().findAll { it.getTitle() == title && it.isDisplayable() }
    if (frames.size() != 1) throw new RuntimeException("expected 1 '" + title + "' window, found " + frames.size())
    if (!frames[0].isVisible()) throw new RuntimeException("window is not visible")
    println("OK window created")
    // a second run reuses the same window
    cs.run(Class.forName("io.github.jiyuuchc.biopb.fiji.TensorBrowserCommand"), true).get()
    javax.swing.SwingUtilities.invokeAndWait({ } as Runnable)
    def again = java.awt.Frame.getFrames().findAll { it.getTitle() == title && it.isDisplayable() }
    if (again.size() != 1 || !again[0].is(frames[0])) throw new RuntimeException("window was not reused")
    println("OK window reused")
    javax.swing.SwingUtilities.invokeAndWait({ frames[0].dispose() } as Runnable)
    println("OK window disposed")
} catch (Throwable t) {
    t.printStackTrace()
    System.err.println("GUI SMOKE FAILED: " + t)
    System.exit(1)
}
System.exit(0)
