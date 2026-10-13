package io.github.jiyuuchc.biopb.fiji;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTextField;

/**
 * One {@code Name: value} line with a Copy button, for the identifiers the browser shows (a source
 * id, an array id): they are arguments to {@code client.get_tensor(...)}, so they have to leave the
 * screen exactly. The text is selectable, but the button copies the value this row was given, never
 * the rendered string, so a drag that stops one character short cannot produce a wrong id.
 */
final class CopyableIdRow extends JPanel {
    private static final long serialVersionUID = 1L;

    private final String name;
    private final JTextField text = new JTextField();
    private static final String LABEL = "Copy";
    private final JButton button = new JButton(LABEL);
    private String value = "";
    private transient Consumer<String> copier = CopyableIdRow::toClipboard;
    private transient Consumer<String> onCopied = v -> { };
    private transient Runnable onFailed = () -> { };
    private final javax.swing.Timer revert = new javax.swing.Timer(1500, e -> button.setText(LABEL));

    CopyableIdRow(String name) {
        super(new BorderLayout(4, 0));
        this.name = name;
        text.setEditable(false);
        text.setBorder(BorderFactory.createEmptyBorder());
        text.setOpaque(false);
        text.setForeground(Color.GRAY);
        button.setMargin(new java.awt.Insets(0, 4, 0, 4));
        button.setToolTipText("Copy the " + name.toLowerCase(java.util.Locale.ROOT) + " to the clipboard");
        button.setFocusable(false);
        button.addActionListener(e -> copy());
        add(text, BorderLayout.CENTER);
        add(button, BorderLayout.EAST);
        revert.setRepeats(false);
        setOpaque(false);
    }

    /** Shows {@code v}, and makes it what the button copies. */
    void setValue(String v) {
        value = v;
        text.setText(name + ": " + v);
        text.setCaretPosition(0);
    }

    /** What the button would copy now. */
    String value() { return value; }

    /** Where a copy goes (the system clipboard by default). */
    void setCopier(Consumer<String> c) { copier = c; }

    /** Told the value after each copy, to confirm it. */
    void setOnCopied(Consumer<String> c) { onCopied = c; }

    /** Told when the clipboard could not be written. */
    void setOnCopyFailed(Runnable r) { onFailed = r; }

    JButton button() { return button; }

    private void copy() {
        try {
            copier.accept(value);
        } catch (RuntimeException e) { // no clipboard (headless, or held by another process)
            flash("Copy failed");
            onFailed.run();
            return;
        }
        flash("Copied");
        onCopied.accept(value);
    }

    /** The button says what happened for a moment, then goes back to its label. */
    private void flash(String label) {
        button.setText(label);
        revert.restart();
    }

    private static void toClipboard(String v) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(v), null);
    }
}
