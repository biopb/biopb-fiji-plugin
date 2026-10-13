package io.github.jiyuuchc.biopb.fiji;

/** The message painted in an empty tree. */
final class EmptyState {
    private EmptyState() { }

    /**
     * @param connected whether there is a connection
     * @param connecting whether one is being made
     * @param loading whether the connected catalog has not been listed yet
     * @param searching whether the tree shows search results
     * @param canDrop whether sources can be added here (a local server)
     */
    static String message(boolean connected, boolean connecting, boolean loading, boolean searching, boolean canDrop) {
        if (!connected) {
            return connecting ? "Connecting…" : "Not connected";
        }
        if (loading) {
            return "Loading…";
        }
        if (searching) {
            return "No matching sources";
        }
        return canDrop ? "No sources - drop a folder here" : "No sources";
    }
}
