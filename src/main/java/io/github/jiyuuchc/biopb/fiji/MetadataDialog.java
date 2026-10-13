package io.github.jiyuuchc.biopb.fiji;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Window;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;

/**
 * A source's or tensor's metadata, in a window of its own that does not block the browser (as the
 * napari widget's dialog does): where it is, the shape and dtype, the identifiers (each copyable),
 * and the server's metadata as selectable text. Opens in a loading state; the caller reads the server
 * off the EDT and then calls {@link #showMetadata} or {@link #showError}. EDT only.
 */
final class MetadataDialog extends JDialog {
    private static final long serialVersionUID = 1L;

    private final JTextArea body = new JTextArea("Loading…");
    private final CopyableIdRow sourceRow = new CopyableIdRow("Source");
    private final CopyableIdRow tensorRow = new CopyableIdRow("Tensor");
    private final java.util.List<Object> key;
    private transient java.util.concurrent.Future<?> read;

    MetadataDialog(Window owner, MetadataTarget target) {
        super(owner, target.getTitle(), ModalityType.MODELESS);
        this.key = keyOf(target);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        JPanel head = new JPanel();
        head.setLayout(new BoxLayout(head, BoxLayout.Y_AXIS));
        head.add(selectable(target.getPath(), true));
        if (target.hasTensor()) {
            javax.swing.JTextArea summary = MetadataPane.wrapped();
            summary.setText(target.summary());
            head.add(summary);
        }
        sourceRow.setValue(target.getSourceId());
        head.add(sourceRow);
        if (target.hasTensor()) {
            tensorRow.setValue(target.getArrayId());
            head.add(tensorRow);
        }
        head.add(Box.createVerticalStrut(6));
        head.add(new JLabel("Metadata"));
        head.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));

        body.setEditable(false);
        body.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JButton close = new JButton("Close");
        close.addActionListener(e -> dispose());
        JPanel foot = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT));
        foot.add(close);

        add(head, BorderLayout.NORTH);
        add(new JScrollPane(body), BorderLayout.CENTER);
        add(foot, BorderLayout.SOUTH);
        setMinimumSize(new Dimension(420, 320));
        setSize(620, 520);
        setLocationRelativeTo(owner);
    }

    private static JTextField selectable(String text, boolean bold) {
        JTextField f = new JTextField(text);
        f.setEditable(false);
        f.setBorder(BorderFactory.createEmptyBorder());
        f.setOpaque(false);
        f.setCaretPosition(0);
        if (bold) {
            f.setFont(f.getFont().deriveFont(Font.BOLD));
        }
        return f;
    }

    /** What identifies the window's subject, so asking again finds it. */
    static java.util.List<Object> keyOf(MetadataTarget t) {
        return java.util.Arrays.<Object>asList(t.getSourceId(), t.getArrayId() == null ? NO_TENSOR : t.getArrayId());
    }

    private static final Object NO_TENSOR = new Object(); // distinct from any array id, so a source and a tensor never collide

    java.util.List<Object> key() { return key; }

    /** Shows the (already formatted) metadata text. */
    void showText(String text) {
        body.setText(text);
        body.setCaretPosition(0);
    }

    /** The running read, to cancel when the window goes. */
    void setRead(java.util.concurrent.Future<?> f) { read = f; }

    /** Stops the read, if it is still running. */
    void cancelRead() {
        if (read != null) {
            read.cancel(true);
        }
    }

    void setOnCopied(java.util.function.Consumer<String> c) {
        sourceRow.setOnCopied(c);
        tensorRow.setOnCopied(c);
    }

    void setOnCopyFailed(Runnable r) {
        sourceRow.setOnCopyFailed(r);
        tensorRow.setOnCopyFailed(r);
    }

    /** Shows why the metadata could not be read, in place of it. */
    void showError(String message) {
        body.setText("Could not read the metadata: " + message);
        body.setCaretPosition(0);
    }

    String bodyText() { return body.getText(); }
    CopyableIdRow sourceRow() { return sourceRow; }
    CopyableIdRow tensorRow() { return tensorRow; }
}
