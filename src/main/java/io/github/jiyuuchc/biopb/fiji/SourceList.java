package io.github.jiyuuchc.biopb.fiji;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
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
 */
public final class SourceList implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(SourceList.class.getName());

    /** Most children one folder asks for; the server says when it holds more. */
    public static final int LEVEL_LIMIT = 2000;

    /** The watcher's default poll interval, in seconds. */
    public static final long WATCH_INTERVAL_S = 30;

    /** Source ids per descriptor query, so no statement grows without bound. */
    static final int DESCRIBE_CHUNK = 500;

    /** The columns a source row is decoded from, when the client names none. */
    public static final String BASE_COLUMNS = "source_id, source_url, source_type, is_resolved, tensors";

    private final Supplier<CatalogClient> connection;
    private final long intervalMs;
    private ScheduledExecutorService executor; // guarded by lock; null until the watcher starts
    private final boolean ownsExecutor;
    private ScheduledFuture<?> watch;

    // The watcher and the folder workers both commit levels; a commit is whole,
    // and one begun before a clear() is dropped.
    private final Object lock = new Object();
    private int epoch;

    private volatile Map<String, Map<String, Object>> sources = Collections.emptyMap();
    private volatile Map<String, CatalogClient.Level> levels = Collections.emptyMap();
    private volatile boolean lazy;
    private boolean lazyUnsupported;

    private volatile Supplier<? extends Collection<String>> openKeys = Collections::emptySet;
    private volatile Consumer<Map<String, Map<String, Object>>> onChanged;

    // Watcher state: what the list was last reconciled against. Touched only by poll().
    private Long lastCount;
    private Object lastGeneration;

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

    /** Sets the folder keys the user has open, asked on each re-list. */
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
            lazyUnsupported = false;
        }
        lastCount = null;
        lastGeneration = null;
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
        boolean tryLevels;
        synchronized (lock) {
            tryLevels = !lazyUnsupported;
        }
        if (tryLevels && refreshLevels(client)) {
            return sources;
        }
        int startEpoch = currentEpoch();
        Map<String, Map<String, Object>> listed = indexById(client.query(sourcesSql(sourceColumns(client))));
        synchronized (lock) {
            checkCurrent(client, startEpoch);
            lazy = false;
            levels = Collections.emptyMap();
            sources = listed;
        }
        return listed;
    }

    /** Reads the levels; false when the server (or SDK) cannot serve them. */
    private boolean refreshLevels(CatalogClient client) throws IOException {
        int startEpoch;
        Map<String, CatalogClient.Level> before;
        synchronized (lock) {
            startEpoch = epoch;
            before = levels;
        }
        CatalogClient.Level top;
        try {
            top = client.catalogChildren("", LEVEL_LIMIT);
        } catch (UnsupportedOperationException e) {
            top = null;
        }
        if (top == null) {
            synchronized (lock) {
                lazyUnsupported = true;
            }
            return false;
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
                ids.add(s.getSourceId());
            }
        }
        Map<String, Map<String, Object>> described = describe(ids, client);
        synchronized (lock) {
            checkCurrent(client, startEpoch);
            Map<String, CatalogClient.Level> merged = new LinkedHashMap<>();
            for (Map.Entry<String, CatalogClient.Level> e : levels.entrySet()) {
                if (before == null || !before.containsKey(e.getKey())) {
                    merged.put(e.getKey(), e.getValue());
                }
            }
            merged.putAll(fresh);
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

    private void checkCurrent(CatalogClient client, int startEpoch) {
        if (startEpoch != epoch || connection.get() != client) {
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
     * block); idempotent.
     */
    public synchronized void startWatch() {
        if (watch != null && !watch.isCancelled()) {
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
        stopWatch();
        if (ownsExecutor && executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private void pollQuietly() {
        try {
            poll();
        } catch (RuntimeException e) { // an uncaught one would end the schedule
            LOG.log(Level.WARNING, "Source watch: poll failed", e);
        }
    }

    /**
     * One watcher tick: ask the server how it is and re-list if the count or the
     * catalog generation moved. The first connected poll compares against what was
     * listed at connect, so a listing taken mid-index is caught. Package-private
     * so tests can drive it without a clock.
     */
    void poll() {
        CatalogClient client = connection.get();
        if (client == null) {
            lastCount = null; // disconnected: next connected poll starts a baseline
            return;
        }
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
        long count = ((Number) raw).longValue();
        Object generation = health.get("catalog_generation");
        if (lastCount == null) {
            // A lazy listing holds a few levels, not the catalog: what it was
            // listed against is what the server said at the first poll.
            lastCount = lazy ? count : (long) sources.size();
            lastGeneration = generation;
        }
        if (count != lastCount || !Objects.equals(generation, lastGeneration)) {
            relist();
            lastCount = count;
            lastGeneration = generation;
        }
    }

    private void relist() {
        Map<String, Map<String, Object>> listed;
        try {
            listed = refresh();
        } catch (IOException | RuntimeException e) { // keep watching through a blip
            LOG.log(Level.WARNING, "Source watch: re-list failed", e);
            return;
        }
        LOG.info("Source watch: re-listed " + listed.size() + " sources");
        Consumer<Map<String, Map<String, Object>>> callback = onChanged;
        if (callback != null) {
            try {
                callback.accept(listed);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Source watch: change listener failed", e);
            }
        }
    }
}
