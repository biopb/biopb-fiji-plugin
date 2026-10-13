package io.github.jiyuuchc.biopb.fiji;

/**
 * What the Tensor Browser asks of its host. The panel opens nothing itself;
 * calls arrive on the event dispatch thread, so a handler that reads from the
 * server must hand the work to a background thread.
 */
public interface TensorBrowserListener {

    /**
     * The user asked to open a tensor as an image.
     *
     * @param arrayId the tensor's array id
     * @param level the pyramid level, 0 for full resolution
     */
    void openRequested(String arrayId, int level);

    /** The user asked to load a tensor's ROI annotations as layers. */
    void roisRequested(String arrayId);

    /**
     * Progress of the panel's own resolve work, to show in a status line.
     *
     * @param text what is happening or happened (an outcome when {@code busy} is false)
     * @param busy whether a resolve is still running
     */
    default void resolveStatus(String text, boolean busy) { }
}
