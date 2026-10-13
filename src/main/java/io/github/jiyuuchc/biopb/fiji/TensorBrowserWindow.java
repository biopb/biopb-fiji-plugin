package io.github.jiyuuchc.biopb.fiji;

import ij.ImagePlus;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.function.Supplier;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;

/** The single Tensor Browser frame: a {@link TensorBrowserPanel}, a status line and the open/ROI wiring. */
public final class TensorBrowserWindow extends JFrame implements TensorBrowserController.Ui {
    private static final long serialVersionUID = 1L;
    private static TensorBrowserWindow instance; // EDT only

    private final TensorBrowserPanel panel;
    private final JLabel status = new JLabel(" ");
    private final JProgressBar progress = new JProgressBar();
    private static final Dimension DEFAULT_SIZE = new Dimension(560, 720);
    private static final Dimension MIN_SIZE = new Dimension(400, 460);
    private final WindowPrefs prefs = new WindowPrefs();
    private final javax.swing.Timer saveBounds = new javax.swing.Timer(500, e -> rememberBounds());
    private boolean placed; // the position came from the saved bounds
    private Rectangle initial; // where the window first settled; only a change from it is the user's

    private TensorBrowserWindow(TensorBrowserPanel panel) {
        super("BioPB Tensor Browser");
        this.panel = panel;
        progress.setIndeterminate(true);
        progress.setVisible(false);
        progress.setPreferredSize(new Dimension(120, 12));
        JPanel south = new JPanel(new BorderLayout(6, 0));
        south.setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 10, 8, 10));
        south.add(status, BorderLayout.CENTER);
        south.add(progress, BorderLayout.EAST);
        add(panel, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(MIN_SIZE);
        setSize(DEFAULT_SIZE);
        restoreBounds();
        saveBounds.setRepeats(false);
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent e) { changed(); }

            @Override public void componentMoved(java.awt.event.ComponentEvent e) { changed(); }
        });
        panel.addListener(new TensorBrowserController(panel::getSharedConnection, this));
        addWindowListener(new WindowAdapter() {
            @Override public void windowOpened(WindowEvent e) {
                SwingUtilities.invokeLater(() -> initial = getBounds()); // after the initial placement events
            }

            @Override public void windowClosed(WindowEvent e) { // also by dispose()
                saveBounds.stop();
                rememberBounds();
                panel.dispose();
                if (instance == TensorBrowserWindow.this) {
                    instance = null;
                }
            }
        });
    }

    /** Uses the size and position of the last session, fitted to the screens now attached. */
    private void restoreBounds() {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        GraphicsDevice[] devices = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
        Rectangle[] screens = new Rectangle[devices.length];
        for (int i = 0; i < devices.length; i++) {
            java.awt.GraphicsConfiguration gc = devices[i].getDefaultConfiguration();
            Rectangle b = gc.getBounds();
            java.awt.Insets in = Toolkit.getDefaultToolkit().getScreenInsets(gc); // taskbars
            screens[i] = new Rectangle(b.x + in.left, b.y + in.top, b.width - in.left - in.right, b.height - in.top - in.bottom);
        }
        Rectangle r = WindowPrefs.usable(prefs.load(), MIN_SIZE, screens);
        if (r != null) {
            setBounds(r);
            placed = true;
        }
    }

    /** A move or resize: remember it shortly, once the window has settled and it differs from there. */
    private void changed() {
        if (initial != null) {
            saveBounds.restart();
        }
    }

    /** Saves the bounds of a normal (not maximised or minimised) window that the user has moved or resized. */
    private void rememberBounds() {
        Rectangle b = getBounds();
        if (initial != null && !b.equals(initial) && (getExtendedState() & (MAXIMIZED_BOTH | ICONIFIED)) == 0) {
            prefs.saveAsync(b);
        }
    }

    /**
     * Shows the browser, creating it on first use and otherwise bringing the existing window to
     * front. Call on the EDT.
     *
     * @param panels creates the panel when there is no window yet
     * @return the window
     */
    public static TensorBrowserWindow showWindow(Supplier<TensorBrowserPanel> panels) {
        if (instance == null) {
            TensorBrowserPanel p = panels.get();
            try {
                TensorBrowserWindow w = new TensorBrowserWindow(p);
                w.setLocationByPlatform(!w.placed);
                instance = w;
            } catch (Throwable t) {
                p.dispose();
                throw t;
            }
        }
        TensorBrowserWindow w = instance;
        w.setVisible(true);
        w.setExtendedState(w.getExtendedState() & ~JFrame.ICONIFIED);
        w.toFront();
        w.requestFocus();
        return w;
    }

    /** The open window, or null. EDT only. */
    static TensorBrowserWindow current() { return instance; }

    /** The panel inside. */
    TensorBrowserPanel getPanel() { return panel; }

    /** The status text. */
    String getStatusText() { return status.getText(); }

    @Override public void status(String text, boolean busy) {
        status.setText(text);
        progress.setVisible(busy);
    }

    @Override public void show(ImagePlus imp) { imp.show(); }

    private java.awt.Component parent() { return isDisplayable() ? this : null; }

    @Override public boolean confirm(String title, String message) {
        return JOptionPane.showConfirmDialog(parent(), message, title, JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;
    }

    @Override public String askAxes(String message, String previous) {
        return (String) JOptionPane.showInputDialog(parent(),
                message + "\n\nAxis roles, one letter per axis (t c z y x s -):",
                "Axis override", JOptionPane.QUESTION_MESSAGE, null, null, previous);
    }

    @Override public void error(String title, String message) {
        JOptionPane.showMessageDialog(parent(), message, title, JOptionPane.ERROR_MESSAGE);
    }

    /** Runs {@link #showWindow} on the EDT; a failure goes to {@code onError}. */
    static void showLater(Supplier<TensorBrowserPanel> panels, java.util.function.Consumer<Throwable> onError) {
        SwingUtilities.invokeLater(() -> {
            try {
                showWindow(panels);
            } catch (Throwable t) {
                onError.accept(t);
            }
        });
    }
}
