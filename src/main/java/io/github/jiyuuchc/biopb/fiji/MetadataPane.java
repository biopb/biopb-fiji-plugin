package io.github.jiyuuchc.biopb.fiji;

import java.awt.Color;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JPanel;
import javax.swing.JTextArea;

/**
 * The small pane under the tree for the selected tensor: its source id and array id (each with a
 * Copy button) and its shape, dtype and dims, as the napari widget's pane shows. Hidden when
 * nothing with a tensor is selected. EDT only.
 */
final class MetadataPane extends JPanel {
    private static final long serialVersionUID = 1L;

    private final CopyableIdRow sourceRow = new CopyableIdRow("Source");
    private final CopyableIdRow tensorRow = new CopyableIdRow("Tensor");
    private final JTextArea lines = wrapped();

    MetadataPane(Consumer<String> onCopied, Runnable onCopyFailed) {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        for (CopyableIdRow r : new CopyableIdRow[] {sourceRow, tensorRow}) {
            r.setOnCopied(onCopied);
            r.setOnCopyFailed(onCopyFailed);
            add(r);
        }
        lines.setForeground(Color.GRAY);
        add(lines);
        setVisible(false);
    }

    /** Shows {@code target}'s identifiers and tensor line, or hides the pane for {@code null} / no tensor. */
    void show(MetadataTarget target) {
        if (target == null || !target.hasTensor()) {
            setVisible(false);
            return;
        }
        sourceRow.setValue(target.getSourceId());
        tensorRow.setValue(target.getArrayId());
        lines.setText(target.summary());
        lines.setCaretPosition(0);
        setVisible(true);
    }

    /** A selectable, read-only text that wraps rather than clips. */
    static JTextArea wrapped() {
        JTextArea a = new JTextArea();
        a.setEditable(false);
        a.setLineWrap(true);
        a.setWrapStyleWord(true);
        a.setBorder(BorderFactory.createEmptyBorder());
        a.setOpaque(false);
        return a;
    }

    CopyableIdRow sourceRow() { return sourceRow; }
    CopyableIdRow tensorRow() { return tensorRow; }
    String linesText() { return lines.getText(); }
}
