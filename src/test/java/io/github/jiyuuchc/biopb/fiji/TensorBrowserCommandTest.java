package io.github.jiyuuchc.biopb.fiji;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.awt.GraphicsEnvironment;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.After;
import org.junit.Assume;
import org.junit.Test;
import org.scijava.plugin.Plugin;

public class TensorBrowserCommandTest {

    @Test
    public void menuEntryIsUnderPluginsBioPB() {
        Plugin p = TensorBrowserCommand.class.getAnnotation(Plugin.class);
        assertEquals("Plugins>BioPB>Tensor Browser", p.menuPath());
    }

    private static <T> T onEdt(java.util.concurrent.Callable<T> c) throws Exception {
        AtomicReference<T> r = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try { r.set(c.call()); } catch (Throwable t) { err.set(t); }
        });
        if (err.get() != null) throw new Exception(err.get());
        return r.get();
    }

    @After
    public void closeWindow() throws Exception {
        if (!GraphicsEnvironment.isHeadless()) {
            onEdt(() -> { if (TensorBrowserWindow.current() != null) TensorBrowserWindow.current().dispose(); return null; });
        }
    }

    @Test
    public void secondInvocationReusesTheWindowAndCloseDisposesIt() throws Exception {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        AtomicInteger made = new AtomicInteger();
        java.util.function.Supplier<TensorBrowserPanel> s = () -> {
            made.incrementAndGet();
            return new TensorBrowserPanel((h, p) -> { throw new java.io.IOException("none"); }, "localhost:1");
        };
        TensorBrowserWindow w1 = onEdt(() -> TensorBrowserWindow.showWindow(s));
        TensorBrowserWindow w2 = onEdt(() -> TensorBrowserWindow.showWindow(s));
        assertSame(w1, w2);
        assertEquals(1, made.get());
        assertTrue(w1.isVisible());

        onEdt(() -> { w1.dispose(); return null; }); // what closing the window does
        assertNull(onEdt(TensorBrowserWindow::current));
        TensorBrowserWindow w3 = onEdt(() -> TensorBrowserWindow.showWindow(s));
        assertNotNull(w3);
        assertEquals(2, made.get());
        assertTrue(w3 != w1);
    }

    @Test
    public void statusLineShowsProgress() throws Exception {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        TensorBrowserWindow w = onEdt(() -> TensorBrowserWindow.showWindow(() ->
                new TensorBrowserPanel((h, p) -> { throw new java.io.IOException("none"); }, "localhost:1")));
        onEdt(() -> { w.status("Opening x…", true); return null; });
        assertEquals("Opening x…", onEdt(w::getStatusText));
    }
}
