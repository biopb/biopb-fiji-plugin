package io.github.jiyuuchc.biopb.fiji;

import ij.ImagePlus;
import java.awt.BorderLayout;
import java.awt.Dimension;
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

    private TensorBrowserWindow(TensorBrowserPanel panel) {
        super("BioPB Tensor Browser");
        this.panel = panel;
        progress.setIndeterminate(true);
        progress.setVisible(false);
        progress.setPreferredSize(new Dimension(120, 12));
        JPanel south = new JPanel(new BorderLayout(6, 0));
        south.setBorder(javax.swing.BorderFactory.createEmptyBorder(2, 6, 4, 6));
        south.add(status, BorderLayout.CENTER);
        south.add(progress, BorderLayout.EAST);
        add(panel, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(480, 640);
        panel.addListener(new TensorBrowserController(panel::getSharedConnection, this));
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) {
                panel.dispose();
                if (instance == TensorBrowserWindow.this) {
                    instance = null;
                }
            }
        });
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
                w.setLocationByPlatform(true);
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
