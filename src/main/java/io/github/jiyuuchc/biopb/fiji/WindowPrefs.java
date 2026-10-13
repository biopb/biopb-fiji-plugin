package io.github.jiyuuchc.biopb.fiji;

import java.awt.Rectangle;
import java.util.prefs.Preferences;

/** Remembers the Tensor Browser window's bounds between sessions (java.util.prefs; failures are ignored). */
final class WindowPrefs {
    private static final String KEY = "tensorBrowserBounds";
    private final Preferences node;

    WindowPrefs() { this(Preferences.userNodeForPackage(WindowPrefs.class)); }

    WindowPrefs(Preferences node) { this.node = node; }

    /** The saved bounds, or {@code null} if none or unreadable. */
    Rectangle load() {
        try {
            return parse(node.get(KEY, null));
        } catch (RuntimeException e) { // prefs can be unavailable or locked down
            return null;
        }
    }

    void save(Rectangle r) {
        try {
            node.put(KEY, format(r));
        } catch (RuntimeException e) {
            // not remembered
        }
    }

    static String format(Rectangle r) { return r.x + "," + r.y + "," + r.width + "," + r.height; }

    /** {@code x,y,width,height}, or {@code null} if it is not that or the size is not positive. */
    static Rectangle parse(String s) {
        if (s == null) {
            return null;
        }
        String[] p = s.trim().split(",");
        if (p.length != 4) {
            return null;
        }
        try {
            Rectangle r = new Rectangle(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()),
                    Integer.parseInt(p[2].trim()), Integer.parseInt(p[3].trim()));
            return r.width > 0 && r.height > 0 ? r : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Writes the bounds on a background thread (the preferences store may block on disk). */
    void saveAsync(Rectangle r) {
        Thread t = new Thread(() -> save(r), "biopb-window-prefs");
        t.setDaemon(true);
        t.start();
    }

    /**
     * {@code saved} grown to at least {@code min}, then fitted into the screen it overlaps most: shrunk to
     * that screen's usable area and moved inside it, so the title bar is reachable.
     *
     * @param screens the usable areas (screen bounds minus taskbars)
     * @return the fitted bounds, or {@code null} if {@code saved} is {@code null} or overlaps no screen
     *     (a monitor that is gone): the caller then lets the platform place the window
     */
    static Rectangle usable(Rectangle saved, java.awt.Dimension min, Rectangle[] screens) {
        if (saved == null) {
            return null;
        }
        Rectangle r = new Rectangle(saved.x, saved.y, Math.max(saved.width, min.width), Math.max(saved.height, min.height));
        Rectangle best = null;
        long bestArea = 0;
        for (Rectangle s : screens) {
            Rectangle v = s.intersection(r);
            long area = v.isEmpty() ? 0 : (long) v.width * v.height;
            if (area > bestArea) {
                best = s;
                bestArea = area;
            }
        }
        if (best == null) {
            return null;
        }
        r.width = Math.min(r.width, best.width);
        r.height = Math.min(r.height, best.height);
        r.x = Math.max(best.x, Math.min(r.x, best.x + best.width - r.width));
        r.y = Math.max(best.y, Math.min(r.y, best.y + best.height - r.height));
        return r;
    }
}
