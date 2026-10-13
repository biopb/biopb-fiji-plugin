package io.github.jiyuuchc.biopb.fiji;

import java.awt.Dimension;
import javax.swing.JLabel;
import javax.swing.ToolTipManager;

/** The persistent connection line: a coloured dot and a short text, with the detail as tooltip. */
final class ConnectionLabel extends JLabel {
    private static final long serialVersionUID = 1L;
    private final StatusDot dot = new StatusDot();

    ConnectionLabel() {
        super("", null, LEADING);
        setIcon(dot);
        setIconTextGap(6);
        ToolTipManager.sharedInstance().registerComponent(this);
        show(ConnectionStatus.State.NOT_CONNECTED, "Not connected", null);
    }

    /** Sets state, text and tooltip together (a {@code null} detail falls back to the text); repaints. */
    void show(ConnectionStatus.State state, String text, String detail) {
        dot.setState(state);
        setText(text);
        setToolTipText(detail != null ? detail : text);
        repaint();
    }

    ConnectionStatus.State getState() { return dot.getState(); }

    @Override public Dimension getMinimumSize() { // a long text is cut with an ellipsis, not made to widen the window
        return new Dimension(0, super.getMinimumSize().height);
    }
}
