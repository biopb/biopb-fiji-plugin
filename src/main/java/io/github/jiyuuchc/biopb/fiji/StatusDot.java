package io.github.jiyuuchc.biopb.fiji;

import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.Icon;

/** A small filled circle in the colour of a {@link ConnectionStatus.State}. */
final class StatusDot implements Icon {
    private static final int SIZE = 10;
    private ConnectionStatus.State state = ConnectionStatus.State.NOT_CONNECTED;

    void setState(ConnectionStatus.State s) { state = s; }

    ConnectionStatus.State getState() { return state; }

    @Override public void paintIcon(Component c, Graphics g, int x, int y) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(state.color());
            g2.fillOval(x, y, SIZE, SIZE);
        } finally {
            g2.dispose();
        }
    }

    @Override public int getIconWidth() { return SIZE; }

    @Override public int getIconHeight() { return SIZE; }
}
