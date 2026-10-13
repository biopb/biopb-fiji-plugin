package io.github.jiyuuchc.biopb.fiji;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The catalog as the Tensor Browser shows it, and what keeps it current. A
 * Swing-free model: it reads through a {@link CatalogClient} and holds the one
 * cache in the picture, the sources the tree is drawn from.
 *
 * <p>A server that answers {@code catalog_children} is read a folder at a time,
 * so what is fetched, held and drawn follows what the user has opened, not the
 * size of the catalog ({@link #isLazy()}). An older server is listed whole. In
 * both, {@link #getSources()} is the descriptors (catalog rows) the tree can show.
 *
 * <p>Threading: reads and re-lists run on worker threads and only ever
 * <em>rebind</em> {@code sources} and {@code levels} to fresh immutable maps, so
 * a reader on the UI thread sees the whole old state or the whole new one. The
 * {@linkplain #setOnChanged change listener} is called on the watcher thread;
 * a Swing UI must marshal to the EDT (e.g. {@code SwingUtilities.invokeLater}).
 *
 * <p>Known limitation (as in the napari widget): sources removed from the server
 * are not pruned from {@link #getSources()} in lazy mode; descriptors accumulate
 * until {@link #clear()}.
 */
public final class SourceList implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(SourceList.class.getName());

    /** Most children one folder asks for; the server says when it holds more. */
    public static final int LEVEL_LIMIT = 2000;

    /** The watcher's default poll interval, in seconds. */
    public static final long WATCH_INTERVAL_S = 30;

    /** Source ids per descriptor query, so no statement grows without bound. */
    static final int DESCRIBE_CHUNK = 500;

    /** The top-level {@code dnd://} names: what drag-and-drop (or the Add action) registered, so what can be removed. */
    static final String DND_ROOTS_SQL = "SELECT DISTINCT regexp_extract(source_url, '^dnd://[^\\\\/]+') AS root "
            + "FROM sources WHERE source_url LIKE 'dnd://%'";

    /** The columns a source row is decoded from, when the client names none. */
    public static final String BASE_COLUMNS = "source_id, source_url, source_type, is_resolved, tensors";

    private final Supplier<CatalogClient> connection;
    private final long intervalMs;
    private ScheduledExecutorService executor; // guarded by the instance monitor; null until the watcher starts
    private final boolean ownsExecutor;
    private ScheduledFuture<?> watch;
    private boolean closed;
    private final Object pollLock = new Object(); // serializes polls

    // The watcher and the folder workers both commit levels; a commit is whole,
    // and one begun before a clear() is dropped.
    private final Object lock = new Object();
    private int epoch;

    private volatile Map<String, Map<String, Object>> sources = Collections.emptyMap();
    private volatile Map<String, CatalogClient.Level> levels = Collections.emptyMap();
    private volatile boolean lazy;
    private volatile Set<String> droppedRoots = Collections.emptySet();
    // The dropped roots are read again at the next lazy re-list when marked is ahead of read. A counter,
    // not a flag: a mark made while a read is in flight is newer than that read and must not be cleared by it.
    private final java.util.concurrent.atomic.AtomicInteger rootsMarked = new java.util.concurrent.atomic.AtomicInteger(1);
    private volatile int rootsRead;
    private boolean lazyUnsupported;

    private volatile Supplier<? extends Collection<String>> openKeys = Collections::emptySet;
    private volatile Consumer<Map<String, Map<String, Object>>> onChanged;

    // Watcher state: what the list was last reconciled against. Guarded by lock and
    // reset by clear(); a poll that began before a clear() does not write it.
    private Long lastCount;
    private Object lastGeneration;
    private long lastPending;

    /** A list over one fixed client, polling every {@link #WATCH_INTERVAL_S} seconds. */
    public SourceList(CatalogClient client) {
        this(() -> client);
    }

    /**
     * @param connection the current client, or {@code null} while disconnected;
     *        a read that began on one client and finds another is dropped
     */
    public SourceList(Supplier<CatalogClient> connection) {
        this(connection, TimeUnit.SECONDS.toMillis(WATCH_INTERVAL_S), null);
    }

    /**
     * @param intervalMs the watcher's poll interval
     * @param executor runs the watcher; {@code null} for a private daemon thread.
     *        An injected executor is not shut down by {@link #close()}
     */
    public SourceList(Supplier<CatalogClient> connection, long intervalMs, ScheduledExecutorService executor) {
        this.connection = connection;
        this.intervalMs = intervalMs;
        this.executor = executor;
        this.ownsExecutor = executor == null;
    }

    // --- state ---------------------------------------------------------------

    /** The descriptors the tree can show, by source id. An immutable snapshot. */
    public Map<String, Map<String, Object>> getSources() {
        return sources;
    }

    /** The folders read, by key ({@code ""} is the top); empty unless {@link #isLazy()}. */
    public Map<String, CatalogClient.Level> getLevels() {
        return levels;
    }

    /** Whether the catalog is read a folder at a time (the server answers {@code catalog_children}). */
    public boolean isLazy() {
        return lazy;
    }

    /**
     * Sets the folder keys the user has open, asked on each re-list. The supplier
     * is called on worker and watcher threads, so it must be thread-safe (return a
     * snapshot, not a live UI collection).
     */
    public void setOpenKeys(Supplier<? extends Collection<String>> openKeys) {
        this.openKeys = openKeys;
    }

    /**
     * Sets the listener called with the fresh sources after the watcher re-lists.
     * It runs on the watcher thread, not the EDT.
     */
    public void setOnChanged(Consumer<Map<String, Map<String, Object>>> onChanged) {
        this.onChanged = onChanged;
    }

    private CatalogClient client() {
        CatalogClient client = connection.get();
        if (client == null) {
            throw new IllegalStateException("Not connected");
        }
        return client;
    }

    /** Forgets everything, for a new connection; reads begun before this are dropped. */
    public void clear() {
        synchronized (lock) {
            epoch++;
            sources = Collections.emptyMap();
            levels = Collections.emptyMap();
            lazy = false;
            droppedRoots = Collections.emptySet();
            markRootsStale();
            lazyUnsupported = false;
            lastCount = null;
            lastGeneration = null;
            lastPending = 0;
        }
    }

    // --- listing -------------------------------------------------------------

    /**
     * Re-reads the catalog: the top and the open folders on a server that serves
     * them a level at a time, else the whole listing.
     *
     * @return the sources after the re-list
     * @throws IllegalStateException if not connected, or the connection changed mid-read
     */
    public Map<String, Map<String, Object>> refresh() throws IOException {
        CatalogClient client = client();
        int startEpoch;
        boolean tryLevels;
        Map<String, CatalogClient.Level> before;
        synchronized (lock) {
            startEpoch = epoch;
            tryLevels = !lazyUnsupported;
            before = levels;
        }
        if (tryLevels && refreshLevels(client, startEpoch, before)) {
            if (rootsMarked.get() != rootsRead) {
                refreshDroppedRoots(client, startEpoch);
            }
            return sources;
        }
        Map<String, Map<String, Object>> listed = indexById(client.query(sourcesSql(sourceColumns(client))));
        CatalogClient now = connection.get();
        synchronized (lock) {
            checkCurrent(client, startEpoch, now);
            lazy = false;
            levels = Collections.emptyMap();
            sources = listed;
            droppedRoots = Collections.unmodifiableSet(DroppedSources.rootsOf(listed.values())); // all rows are held
        }
        return listed;
    }

    /**
     * Reads which top-level names were made by drag-and-drop ({@code dnd://name} roots), as the napari
     * widget does. Asked only when stale (a new connection, a change the watcher saw, an add or a
     * removal), not on every re-list. A server that will not say leaves the last answer (and stays
     * stale, so it is asked again): the tree then has no remove action on a folder.
     */
    private void refreshDroppedRoots(CatalogClient client, int startEpoch) {
        int marked = rootsMarked.get();
        try {
            Set<String> roots = new HashSet<>();
            for (Map<String, Object> row : client.query(DND_ROOTS_SQL)) {
                if (row.get("root") instanceof String && !((String) row.get("root")).isEmpty()) {
                    roots.add((String) row.get("root"));
                }
            }
            CatalogClient now = connection.get();
            synchronized (lock) {
                if (startEpoch == epoch && now == client) {
                    droppedRoots = Collections.unmodifiableSet(roots);
                    if (rootsMarked.get() == marked) { // not marked again while the read was out
                        rootsRead = marked;
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.FINE, "Reading the dropped roots failed", e);
        }
    }

    /** The next lazy re-list reads the dropped roots again. */
    void markRootsStale() {
        rootsMarked.incrementAndGet();
    }

    /** The {@code dnd://} roots the server holds, as of the last re-list (lazy listing only). */
    public Set<String> getDroppedRoots() {
        return droppedRoots;
    }

    /** Reads the levels; false when the server (or SDK) cannot serve them. */
    private boolean refreshLevels(CatalogClient client, int startEpoch,
            Map<String, CatalogClient.Level> before) throws IOException {
        CatalogClient.Level top = null;
        boolean unsupported = false;
        try {
            top = client.catalogChildren("", LEVEL_LIMIT);
        } catch (UnsupportedOperationException e) { // only an old server latches
            unsupported = true;
        }
        if (unsupported) {
            CatalogClient now = connection.get();
            synchronized (lock) {
                checkCurrent(client, startEpoch, now);
                lazyUnsupported = true;
            }
            return false;
        }
        if (top == null) {
            throw new IOException("unexpected catalog_children answer for the top");
        }
        Map<String, CatalogClient.Level> fresh = new LinkedHashMap<>();
        fresh.put("", top);
        for (String key : new TreeSet<>(openKeys.get())) {
            if (key.isEmpty() || !before.containsKey(key)) {
                continue;
            }
            try {
                fresh.put(key, readLevel(client, key));
            } catch (IOException | RuntimeException e) { // a folder that fails keeps its last read
                LOG.log(Level.FINE, "Reading folder '" + key + "' failed", e);
                fresh.put(key, before.get(key));
            }
        }
        commit(client, fresh, startEpoch, before);
        return true;
    }

    private static CatalogClient.Level readLevel(CatalogClient client, String key) throws IOException {
        CatalogClient.Level level = client.catalogChildren(key, LEVEL_LIMIT);
        if (level == null) {
            throw new IOException("unexpected catalog_children answer for '" + key + "'");
        }
        return level;
    }

    /**
     * Takes {@code fresh} levels and the descriptors of the sources they list.
     * With {@code before} (the levels a re-list started from), {@code fresh}
     * replaces them but for folders read since, which a concurrent read added.
     * A commit begun before a {@link #clear()} is dropped.
     */
    private void commit(CatalogClient client, Map<String, CatalogClient.Level> fresh, int startEpoch,
            Map<String, CatalogClient.Level> before) throws IOException {
        List<String> ids = new ArrayList<>();
        for (CatalogClient.Level level : fresh.values()) {
            for (CatalogClient.Source s : level.getSources()) {
                if (s.getSourceId() != null) {
                    ids.add(s.getSourceId());
                }
            }
        }
        Map<String, Map<String, Object>> described = describe(ids, client);
        CatalogClient current = connection.get();
        synchronized (lock) {
            checkCurrent(client, startEpoch, current);
            Map<String, CatalogClient.Level> merged = new LinkedHashMap<>();
            for (Map.Entry<String, CatalogClient.Level> e : levels.entrySet()) {
                if (before == null || !before.containsKey(e.getKey())) {
                    merged.put(e.getKey(), e.getValue());
                }
            }
            for (Map.Entry<String, CatalogClient.Level> e : fresh.entrySet()) {
                // A folder committed since the re-list began is newer than the re-list's read.
                CatalogClient.Level now = levels.get(e.getKey());
                boolean newer = before != null && now != null && now != before.get(e.getKey());
                merged.put(e.getKey(), newer ? now : e.getValue());
            }
            Map<String, Map<String, Object>> all = new HashMap<>(sources);
            all.putAll(described);
            sources = Collections.unmodifiableMap(all);
            levels = Collections.unmodifiableMap(merged);
            lazy = true;
        }
    }

    private int currentEpoch() {
        synchronized (lock) {
            return epoch;
        }
    }

    /** Call under the lock, with {@code now} read from the connection before taking it. */
    private void checkCurrent(CatalogClient client, int startEpoch, CatalogClient now) {
        if (startEpoch != epoch || now != client) {
            throw new IllegalStateException("The connection changed");
        }
    }

    /** Reads one folder (call off the UI thread) and keeps it with its sources. */
    public CatalogClient.Level loadLevel(String key) throws IOException {
        CatalogClient client = client();
        int startEpoch = currentEpoch();
        CatalogClient.Level level = readLevel(client, key);
        commit(client, Collections.singletonMap(key, level), startEpoch, null);
        return level;
    }

    /** The descriptors of {@code ids}, read from the catalog by the current client. */
    public Map<String, Map<String, Object>> describe(Collection<String> ids) throws IOException {
        return describe(ids, client());
    }

    private static Map<String, Map<String, Object>> describe(Collection<String> ids, CatalogClient client)
            throws IOException {
        List<String> all = new ArrayList<>(ids);
        String columns = sourceColumns(client);
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (int i = 0; i < all.size(); i += DESCRIBE_CHUNK) {
            List<String> chunk = all.subList(i, Math.min(i + DESCRIBE_CHUNK, all.size()));
            StringBuilder in = new StringBuilder();
            for (String id : chunk) {
                in.append(in.length() == 0 ? "" : ", ").append(quote(id));
            }
            out.putAll(indexById(client.query(
                    "SELECT " + columns + " FROM sources WHERE source_id IN (" + in + ")")));
        }
        return out;
    }

    /** Keeps descriptors read for something other than a level (a search). */
    public void addSources(Map<String, Map<String, Object>> found) {
        synchronized (lock) {
            Map<String, Map<String, Object>> all = new HashMap<>(sources);
            all.putAll(found);
            sources = Collections.unmodifiableMap(all);
            Set<String> roots = DroppedSources.rootsOf(found.values());
            if (!droppedRoots.containsAll(roots)) { // a search finds sources the levels have not listed
                roots.addAll(droppedRoots);
                droppedRoots = Collections.unmodifiableSet(roots);
            }
        }
    }

    private static Map<String, Map<String, Object>> indexById(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object id = row.get("source_id");
            if (id instanceof String) {
                out.put((String) id, Collections.unmodifiableMap(row));
            }
        }
        return Collections.unmodifiableMap(out);
    }

    // --- the server's verbs --------------------------------------------------

    /** Whether a source row is resolved; a row without the column reads as resolved. */
    public static boolean isResolved(Map<String, Object> row) {
        return !Boolean.FALSE.equals(row.get("is_resolved"));
    }

    /**
     * Resolves a source (a cloud one is downloaded, which can take minutes: call off the UI thread)
     * and re-lists. The row the resolve committed is merged into {@link #getSources()} if it is
     * resolved and the held entry is not (or replaced while the held entry is itself still unresolved,
     * so a new reason, say cloud to failed, shows), so a re-list that still shows the source unresolved (a
     * pending source registers without moving the count) cannot undo it. A failing re-list is
     * ignored: the resolve itself succeeded.
     *
     * @param onProgress receives the server's heartbeats on the calling thread, or {@code null}
     * @param shouldCancel polled at each heartbeat; true stops the resolve, or {@code null}
     * @return the committed row, merged or not
     * @throws ResolveCancelledException if {@code shouldCancel} stopped it
     * @throws IllegalStateException if not connected, or the connection changed meanwhile (nothing is merged)
     * @throws IOException if the resolve fails
     */
    public Map<String, Object> resolve(String sourceId, Consumer<CatalogClient.ResolveProgress> onProgress,
            BooleanSupplier shouldCancel) throws IOException {
        CatalogClient client = client();
        int startEpoch = currentEpoch();
        Map<String, Object> row = client.resolveSource(sourceId, onProgress, shouldCancel);
        if (row == null) {
            throw new IOException("resolve of '" + sourceId + "' returned no catalog row");
        }
        synchronized (lock) { // a replaced connection must not be re-listed into
            checkCurrent(client, startEpoch, connection.get());
        }
        try {
            refresh();
        } catch (IOException | RuntimeException e) { // a connection replaced meanwhile is caught by the check below
            LOG.log(Level.FINE, "Re-list after resolving '" + sourceId + "' failed", e);
        }
        Map<String, Object> committed = Collections.unmodifiableMap(row);
        synchronized (lock) {
            checkCurrent(client, startEpoch, connection.get()); // read here: no gap before the merge
            Object id = row.get("source_id");
            String key = id instanceof String ? (String) id : sourceId;
            Map<String, Object> held = sources.get(key);
            // A resolved entry is never replaced; an unresolved one takes the committed row, resolved or not
            // (cloud -> failed must show). A missing entry takes it only if resolved, as the napari widget does.
            if (held == null ? isResolved(row) : !isResolved(held)) {
                Map<String, Map<String, Object>> all = new HashMap<>(sources);
                all.put(key, committed);
                sources = Collections.unmodifiableMap(all);
            }
        }
        return committed;
    }

    // --- adding and removing -------------------------------------------------

    /**
     * Registers a local path on the server and re-lists, so the new sources show. The server walks a
     * folder, which takes as long as it takes: call off the UI thread. A re-list that fails after the
     * add went through is logged, not thrown (the add happened; the watcher catches up).
     *
     * @param onProgress receives progress on the calling thread, or {@code null}
     * @param shouldCancel polled as progress arrives; true stops the walk, or {@code null}
     * @throws IllegalStateException if not connected (a connection replaced meanwhile still returns the result)
     * @throws IOException if the call fails
     */
    public SourceChanges.AddResult add(String path, boolean cloud, Consumer<SourceChanges.AddProgress> onProgress,
            BooleanSupplier shouldCancel) throws IOException {
        CatalogClient client = client();
        int startEpoch = currentEpoch();
        SourceChanges.AddResult result = client.registerLocalPath(path, cloud, onProgress, shouldCancel);
        afterChange(client, startEpoch, "adding '" + path + "'");
        return result;
    }

    /**
     * Unregisters the sources under a {@code dnd://} root and re-lists.
     *
     * @throws IllegalStateException if not connected (a connection replaced meanwhile still returns the result)
     * @throws IOException if the call fails
     */
    public SourceChanges.RemoveResult remove(String rootUrl) throws IOException {
        CatalogClient client = client();
        int startEpoch = currentEpoch();
        SourceChanges.RemoveResult result = client.deregisterLocalPath(rootUrl);
        afterChange(client, startEpoch, "removing '" + rootUrl + "'");
        return result;
    }

    /**
     * Re-lists after the server call went through. The call happened, so its result is the caller's
     * either way; only the re-list is skipped (a replaced connection must not be listed into).
     */
    private void afterChange(CatalogClient client, int startEpoch, String what) {
        markRootsStale();
        synchronized (lock) {
            try {
                checkCurrent(client, startEpoch, connection.get());
            } catch (IllegalStateException replaced) {
                return;
            }
        }
        try {
            refresh();
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.FINE, "Re-list after " + what + " failed", e);
        }
    }

    // --- SQL -----------------------------------------------------------------

    /**
     * The columns to read a source with: what the client projects (it adds
     * {@code unresolved_reason} when the server's schema has it), else
     * {@link #BASE_COLUMNS}.
     */
    public static String sourceColumns(CatalogClient client) {
        try {
            String probed = client.sourceRowColumns();
            if (probed != null && !probed.isEmpty()) {
                return probed;
            }
        } catch (RuntimeException e) { // absent or failing probe: base columns
            LOG.log(Level.FINE, "sourceRowColumns unavailable; using base columns", e);
        }
        return BASE_COLUMNS;
    }

    /** The statement that lists every source. */
    public static String sourcesSql(String columns) {
        return "SELECT " + columns + " FROM sources ORDER BY source_id";
    }

    /** {@code value} as a SQL string literal. */
    public static String quote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    // --- the watcher ---------------------------------------------------------

    /**
     * Starts polling the server's health and re-listing when {@code source_count}
     * or {@code catalog_generation} changes, so a catalog listed while the server
     * was still indexing fills itself in. Runs on a daemon thread (health checks
     * block); idempotent, and a no-op after {@link #close()}.
     */
    public synchronized void startWatch() {
        if (closed || watch != null && !watch.isCancelled()) {
            return;
        }
        if (executor == null) {
            executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "biopb-source-watch");
                t.setDaemon(true);
                return t;
            });
        }
        watch = executor.scheduleWithFixedDelay(this::pollQuietly, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    /** Stops polling; {@link #startWatch()} may resume it. */
    public synchronized void stopWatch() {
        if (watch != null) {
            watch.cancel(false);
            watch = null;
        }
    }

    /** Stops the watcher and releases its thread. */
    @Override
    public synchronized void close() {
        closed = true;
        stopWatch();
        if (ownsExecutor && executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private void pollQuietly() {
        try {
            poll();
        } catch (Throwable t) { // anything uncaught would silently end the schedule
            LOG.log(Level.WARNING, "Source watch: poll failed", t);
        }
    }

    /**
     * One watcher tick: ask the server how it is and re-list if the count, the
     * catalog generation or the pending-registration figure moved. The first
     * connected poll takes its baseline: the number of sources held (whole
     * listing) or the server's own count (lazy, where only a few levels are held),
     * and the server's generation then. A whole listing taken mid-index is thus
     * caught; a lazy one is not, by design. The baseline advances only after a successful
     * re-list, so a failed one is retried on the next tick. Package-private so
     * tests can drive it without a clock.
     */
    void poll() {
        synchronized (pollLock) {
            pollOnce();
        }
    }

    private void pollOnce() {
        CatalogClient client = connection.get();
        if (client == null) {
            synchronized (lock) {
                lastCount = null; // disconnected: next connected poll starts a baseline
                lastGeneration = null;
                lastPending = 0;
            }
            return;
        }
        int startEpoch = currentEpoch();
        Map<String, Object> health;
        try {
            health = client.healthCheck();
        } catch (IOException | RuntimeException e) { // transient; try again next tick
            LOG.log(Level.FINE, "Source watch: health check failed", e);
            return;
        }
        Object raw = health == null ? null : health.get("source_count");
        if (!(raw instanceof Number)) {
            return;
        }
        Number count = (Number) raw;
        Object generation = health.get("catalog_generation");
        Object pendingRaw = health.get("registration_pending");
        long pending = pendingRaw instanceof Number ? ((Number) pendingRaw).longValue() : 0;
        boolean changed;
        synchronized (lock) {
            if (startEpoch != epoch) {
                return; // cleared while we asked; the answer belongs to the old listing
            }
            if (lastCount == null) {
                // A lazy listing holds a few levels, not the catalog: what it was
                // listed against is what the server said at the first poll.
                lastCount = lazy ? count.longValue() : (long) sources.size();
                lastGeneration = generation;
            }
            changed = !sameNumber(count, lastCount) || pending != lastPending
                    || !sameValue(generation, lastGeneration);
        }
        if (changed) {
            markRootsStale(); // another client may have added or removed a drop
        }
        if (changed && relist(startEpoch)) {
            synchronized (lock) {
                if (startEpoch == epoch) {
                    lastCount = count.longValue();
                    lastGeneration = generation;
                    lastPending = pending;
                }
            }
        }
    }

    private static boolean sameNumber(Number a, Number b) {
        return a.doubleValue() == b.doubleValue();
    }

    private static boolean sameValue(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) {
            return sameNumber((Number) a, (Number) b);
        }
        return Objects.equals(a, b);
    }

    /** Re-lists and tells the listener; false when the re-list failed. */
    private boolean relist(int startEpoch) {
        Map<String, Map<String, Object>> listed;
        try {
            listed = refresh();
        } catch (IOException | RuntimeException e) { // keep watching through a blip
            LOG.log(Level.WARNING, "Source watch: re-list failed", e);
            return false;
        }
        LOG.info("Source watch: re-listed " + listed.size() + " sources");
        if (currentEpoch() != startEpoch) {
            return false; // cleared since: the listing is stale, say nothing
        }
        Consumer<Map<String, Map<String, Object>>> callback = onChanged;
        if (callback != null) {
            try {
                callback.accept(listed);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Source watch: change listener failed", e);
            }
        }
        return true;
    }
}
