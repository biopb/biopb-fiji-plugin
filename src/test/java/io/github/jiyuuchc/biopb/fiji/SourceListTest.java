package io.github.jiyuuchc.biopb.fiji;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/** {@link SourceList} against a fake server: lazy and whole listings, races, the watcher. */
public class SourceListTest {

    /** A server that serves {@code levels} (or none) and a health sequence. */
    static final class FakeClient implements CatalogClient {
        List<String> catalog = new ArrayList<>();
        Map<String, Level> levels; // null: an old server without catalog_children
        BiFunction<String, Integer, Level> childrenHook; // overrides levels when set
        final Deque<Object> health = new java.util.concurrent.ConcurrentLinkedDeque<>(); // Map or RuntimeException
        String columns; // sourceRowColumns answer
        final List<String> queries = new ArrayList<>();
        final List<String> childKeys = new ArrayList<>();
        final java.util.concurrent.atomic.AtomicInteger healthCalls = new java.util.concurrent.atomic.AtomicInteger();
        Runnable queryHook; // runs inside a whole-listing query
        Runnable healthHook; // runs inside healthCheck, to interleave a call

        @Override
        public Level catalogChildren(String key, int limit) {
            childKeys.add(key);
            if (childrenHook != null) {
                return childrenHook.apply(key, limit);
            }
            if (levels == null) {
                throw new UnsupportedOperationException("too old");
            }
            return levels.get(key);
        }

        @Override
        public List<Map<String, Object>> query(String sql) throws IOException {
            queries.add(sql);
            if (queryHook != null && sql.contains("ORDER BY")) {
                queryHook.run();
            }
            if (sql.contains(" IN (")) {
                Matcher m = Pattern.compile("'((?:[^']|'')*)'").matcher(sql.substring(sql.indexOf(" IN (")));
                List<String> ids = new ArrayList<>();
                while (m.find()) {
                    ids.add(m.group(1).replace("''", "'"));
                }
                return rows(ids);
            }
            return rows(catalog);
        }

        @Override
        @SuppressWarnings("unchecked")
        public Map<String, Object> healthCheck() throws IOException {
            healthCalls.incrementAndGet();
            if (healthHook != null) {
                healthHook.run();
            }
            Object next = health.poll();
            if (next instanceof RuntimeException) {
                throw (RuntimeException) next;
            }
            return (Map<String, Object>) next;
        }

        @Override
        public String sourceRowColumns() {
            return columns;
        }

        int listings() {
            int n = 0;
            for (String q : queries) {
                if (q.contains("ORDER BY source_id")) {
                    n++;
                }
            }
            return n;
        }
    }

    static List<Map<String, Object>> rows(List<String> ids) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String id : ids) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("source_id", id);
            row.put("source_url", "/data/" + id);
            row.put("source_type", "zarr");
            row.put("is_resolved", true);
            out.add(row);
        }
        return out;
    }

    static Map<String, Object> h(Object count) {
        Map<String, Object> m = new HashMap<>();
        m.put("source_count", count);
        return m;
    }

    static Map<String, Object> h(Object count, String generation) {
        Map<String, Object> m = h(count);
        m.put("catalog_generation", generation);
        return m;
    }

    static CatalogClient.Level level(List<String> folders, List<String> sources, boolean truncated) {
        List<CatalogClient.Folder> fs = new ArrayList<>();
        for (String f : folders) {
            fs.add(new CatalogClient.Folder(f, f));
        }
        List<CatalogClient.Source> ss = new ArrayList<>();
        for (String s : sources) {
            ss.add(new CatalogClient.Source(s + ".zarr", s));
        }
        return new CatalogClient.Level("", fs, ss, truncated);
    }

    static CatalogClient.Level level(List<String> folders, List<String> sources) {
        return level(folders, sources, false);
    }

    static List<String> l(String... s) {
        return Arrays.asList(s);
    }

    static Set<String> set(String... s) {
        return new HashSet<>(Arrays.asList(s));
    }

    /** A list over a whole-catalog server already holding {@code ids}. */
    static SourceList listing(FakeClient client, String... ids) throws IOException {
        client.catalog = new ArrayList<>(Arrays.asList(ids));
        SourceList list = new SourceList(client);
        list.refresh();
        client.queries.clear();
        return list;
    }

    /** A lazy server serving {@code levels}. */
    static SourceList lazyListing(FakeClient client, Map<String, CatalogClient.Level> levels) {
        client.levels = levels;
        return new SourceList(client);
    }

    static Map<String, CatalogClient.Level> levels(Object... kv) {
        Map<String, CatalogClient.Level> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], (CatalogClient.Level) kv[i + 1]);
        }
        return m;
    }

    // --- whole-catalog fallback ---------------------------------------------

    @Test
    public void aServerWithoutTheActionIsListedWhole() throws Exception {
        FakeClient client = new FakeClient();
        SourceList list = listing(client, "a", "b");
        assertFalse(list.isLazy());
        assertEquals(set("a", "b"), list.getSources().keySet());
        assertEquals("zarr", list.getSources().get("a").get("source_type"));
        client.childKeys.clear();
        list.refresh(); // not asked again
        assertTrue(client.childKeys.isEmpty());
    }

    @Test
    public void aMalformedTopLevelIsAnErrorNotAnOldServer() throws Exception {
        FakeClient client = new FakeClient();
        client.levels = new HashMap<>(); // answers null for ""
        client.catalog = l("a");
        SourceList list = new SourceList(client);
        try {
            list.refresh();
            fail("should have thrown");
        } catch (IOException expected) {
            // not latched as unsupported
        }
        client.levels = levels("", level(l(), l("a")));
        list.refresh();
        assertTrue(list.isLazy());
    }

    @Test
    public void aListingStaleAfterAClearIsNotDelivered() throws Exception {
        FakeClient client = new FakeClient();
        SourceList list = listing(client, "a");
        client.health.add(h(2));
        client.catalog = l("a", "b");
        List<Object> delivered = new ArrayList<>();
        list.setOnChanged(delivered::add);
        client.healthHook = null;
        // clear() lands while the re-list's query runs
        FakeClient racing = client;
        racing.queryHook = list::clear;
        list.poll();
        assertTrue(delivered.isEmpty());
    }

    @Test
    public void anUnrelatedFailureIsNotTakenForAnOldServer() {
        FakeClient client = new FakeClient();
        client.childrenHook = (k, n) -> {
            throw new IllegalStateException("Not connected");
        };
        try {
            new SourceList(client).refresh();
            fail("should have thrown");
        } catch (IllegalStateException | IOException expected) {
            // propagated
        }
    }

    @Test
    public void listingAsksForTheColumnsTheClientProjects() throws Exception {
        FakeClient client = new FakeClient();
        client.columns = "source_id, is_resolved, unresolved_reason";
        new SourceList(client).refresh();
        assertTrue(client.queries.get(0).contains("unresolved_reason"));
    }

    @Test
    public void aClientWithoutTheProjectorListsTheBaseColumns() throws Exception {
        FakeClient client = new FakeClient();
        new SourceList(client).refresh();
        String sql = client.queries.get(0);
        assertTrue(sql.contains("is_resolved") && !sql.contains("unresolved_reason"));
    }

    @Test
    public void disconnectedReadsRefuse() {
        SourceList list = new SourceList(() -> null);
        try {
            list.refresh();
            fail("should have thrown");
        } catch (IllegalStateException | IOException expected) {
            // not connected
        }
    }

    // --- lazy listing --------------------------------------------------------

    @Test
    public void readsTheTopAndOnlyTheOpenFolders() throws Exception {
        FakeClient client = new FakeClient();
        SourceList list = lazyListing(client, levels(
                "", level(l("a", "b"), l("t")),
                "a", level(l(), l("x", "y")),
                "b", level(l(), l("z"))));
        list.refresh();
        assertTrue(list.isLazy());
        assertEquals(set(""), list.getLevels().keySet());
        assertEquals(set("t"), list.getSources().keySet()); // descriptors of what is shown only

        list.loadLevel("a");
        assertEquals(set("", "a"), list.getLevels().keySet());
        assertEquals(set("t", "x", "y"), list.getSources().keySet());

        // A re-list reads the top and the open folder, and not "b".
        list.setOpenKeys(() -> set("a", "b"));
        client.childKeys.clear();
        list.refresh();
        assertEquals(l("", "a"), client.childKeys);
    }

    @Test
    public void descriptorsAreAskedForByIdInBoundedStatements() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 1200; i++) {
            ids.add("s" + i);
        }
        ids.add("it's");
        FakeClient client = new FakeClient();
        SourceList list = lazyListing(client, levels("", level(l(), ids)));
        list.refresh();
        List<String> sqls = new ArrayList<>();
        for (String q : client.queries) {
            if (q.contains("source_id IN")) {
                sqls.add(q);
            }
        }
        assertEquals(3, sqls.size()); // 500 + 500 + 201
        assertTrue(sqls.get(2).contains("'it''s'"));
        assertTrue(list.getSources().containsKey("it's"));
    }

    @Test
    public void aTruncatedLevelKeepsItsFlag() throws Exception {
        FakeClient client = new FakeClient();
        SourceList list = lazyListing(client, levels("", level(l(), l("a"), true)));
        list.refresh();
        assertTrue(list.getLevels().get("").isTruncated());
    }

    @Test
    public void aFolderThatFailsToReReadKeepsItsLastLevel() throws Exception {
        Map<String, CatalogClient.Level> served = levels(
                "", level(l("a"), l()),
                "a", level(l(), l("x")));
        FakeClient client = new FakeClient();
        SourceList list = lazyListing(client, served);
        list.refresh();
        list.loadLevel("a");
        CatalogClient.Level before = list.getLevels().get("a");

        client.childrenHook = (key, limit) -> {
            if (key.equals("a")) {
                throw new IllegalStateException("blip");
            }
            return served.get(key);
        };
        list.setOpenKeys(() -> set("a"));
        list.refresh();
        assertSame(before, list.getLevels().get("a"));
    }

    @Test
    public void clearForgetsTheLevels() throws Exception {
        FakeClient client = new FakeClient();
        SourceList list = lazyListing(client, levels("", level(l(), l("a"))));
        list.refresh();
        list.clear();
        assertFalse(list.isLazy());
        assertTrue(list.getLevels().isEmpty());
        assertTrue(list.getSources().isEmpty());
    }

    @Test
    public void addSourcesKeepsSearchResults() throws Exception {
        FakeClient client = new FakeClient();
        SourceList list = lazyListing(client, levels("", level(l(), l("a"))));
        list.refresh();
        list.addSources(Collections.singletonMap("q", rows(l("q")).get(0)));
        assertEquals(set("a", "q"), list.getSources().keySet());
    }

    // --- races ---------------------------------------------------------------

    @Test
    public void aFolderReadDuringAReListSurvivesIt() throws Exception {
        Map<String, CatalogClient.Level> served = levels(
                "", level(l("a"), l()),
                "a", level(l(), l("x")));
        FakeClient client = new FakeClient();
        SourceList list = lazyListing(client, served);
        list.refresh();
        client.childrenHook = (key, limit) -> {
            if (key.isEmpty()) {
                try {
                    list.loadLevel("a"); // the folder worker lands mid re-list
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }
            return served.get(key);
        };
        list.refresh();
        assertTrue(list.getLevels().containsKey("a"));
    }

    @Test
    public void aReadBegunBeforeAClearIsDropped() throws Exception {
        Map<String, CatalogClient.Level> served = levels(
                "", level(l("a"), l()),
                "a", level(l(), l("x")));
        FakeClient client = new FakeClient();
        SourceList list = lazyListing(client, served);
        list.refresh();
        client.childrenHook = (key, limit) -> {
            list.clear();
            return served.get(key);
        };
        try {
            list.loadLevel("a");
            fail("should have been dropped");
        } catch (IllegalStateException expected) {
            // stale epoch
        }
        assertTrue(list.getLevels().isEmpty());
        assertFalse(list.isLazy());
    }

    @Test
    public void aReadFromAReplacedClientIsDropped() throws Exception {
        FakeClient first = new FakeClient();
        FakeClient second = new FakeClient();
        CatalogClient[] current = {first};
        first.levels = levels("", level(l("a"), l()), "a", level(l(), l("x")));
        SourceList list = new SourceList(() -> current[0]);
        list.refresh();
        first.childrenHook = (key, limit) -> {
            current[0] = second; // reconnected while the read was in flight
            return first.levels.get(key);
        };
        try {
            list.loadLevel("a");
            fail("should have been dropped");
        } catch (IllegalStateException expected) {
            // stale client
        }
        assertFalse(list.getLevels().containsKey("a"));
    }

    // --- the watcher ---------------------------------------------------------

    @Test
    public void relistsWhenTheCountChanges() throws Exception {
        FakeClient client = new FakeClient();
        SourceList list = listing(client, "a", "b");
        client.health.add(h(2));
        client.health.add(h(3));
        client.catalog = l("a", "b", "c");
        List<Map<String, Map<String, Object>>> changed = new ArrayList<>();
        list.setOnChanged(changed::add);

        list.poll();
        list.poll();

        assertEquals(set("a", "b", "c"), list.getSources().keySet());
        assertEquals(1, changed.size());
        assertSame(list.getSources(), changed.get(0));
    }

    @Test
    public void aListTakenMidIndexIsCaughtOnTheFirstPoll() throws Exception {
        FakeClient client = new FakeClient();
        SourceList list = listing(client, "a");
        List<String> all = new ArrayList<>();
        for (int i = 0; i < 18; i++) {
            all.add(String.valueOf(i));
        }
        client.catalog = all;
        client.health.add(h(18));
        list.poll();
        assertEquals(18, list.getSources().size());
    }

    @Test
    public void aStableCountDoesNotRelist() throws Exception {
        FakeClient client = new FakeClient();
        SourceList list = listing(client, "a", "b");
        client.health.add(h(2));
        client.health.add(h(2L)); // a Long compares equal to an Integer count
        list.poll();
        list.poll();
        assertTrue(client.queries.isEmpty());
    }

    @Test
    public void aHealthErrorIsTolerated() throws Exception {
        FakeClient client = new FakeClient();
        SourceList list = listing(client, "a");
        client.health.add(new IllegalStateException("blip"));
        list.poll();
        assertTrue(client.queries.isEmpty());
    }

    @Test
    public void aHealthAnswerWithoutACountIsIgnored() throws Exception {
        FakeClient client = new FakeClient();
        SourceList list = listing(client, "a");
        client.health.add(new HashMap<String, Object>());
        list.poll();
        assertTrue(client.queries.isEmpty());
    }

    @Test
    public void disconnectedPollsNothing() throws Exception {
        FakeClient client = new FakeClient();
        CatalogClient[] current = {client};
        client.catalog = l("a");
        SourceList list = new SourceList(() -> current[0]);
        list.refresh();
        current[0] = null;
        list.poll();
        assertEquals(0, client.healthCalls.get());
    }

    @Test
    public void aFailedReListIsRetriedOnTheNextPoll() throws Exception {
        FakeClient client = new FakeClient();
        SourceList list = listing(client, "a");
        client.health.add(h(2));
        client.health.add(h(2));
        client.catalog = null; // the listing query now fails
        list.poll();
        assertEquals(set("a"), list.getSources().keySet());
        client.catalog = l("a", "b");
        list.poll(); // same health answer, but the baseline did not advance
        assertEquals(set("a", "b"), list.getSources().keySet());
        assertEquals(2, client.healthCalls.get());
    }

    @Test
    public void aChangeInRegistrationPendingRelists() throws Exception {
        FakeClient client = new FakeClient();
        SourceList list = listing(client, "a");
        Map<String, Object> p2 = h(1);
        p2.put("registration_pending", 2);
        Map<String, Object> p1 = h(1);
        p1.put("registration_pending", 1);
        client.health.add(p2);
        client.health.add(p2);
        client.health.add(p1);
        client.health.add(h(1));
        for (int i = 0; i < 4; i++) {
            list.poll();
        }
        assertEquals(3, client.listings()); // on 2, on 1, on 0; none between
    }

    @Test
    public void theWatcherRereadsWhenTheCatalogGenerationMoves() throws Exception {
        FakeClient client = new FakeClient();
        SourceList list = lazyListing(client, levels("", level(l(), l("a"))));
        list.refresh();
        client.queries.clear();
        client.childKeys.clear();
        client.health.add(h(1, "1.0"));
        client.health.add(h(1, "1.0"));
        client.health.add(h(1, "1.1"));
        list.poll();
        list.poll();
        list.poll();
        // The first poll sets the baseline, the second is the same, the third moved.
        assertEquals(1, client.childKeys.size());
    }

    @Test
    public void aLazyListingIsBaselinedAgainstTheServersCount() throws Exception {
        FakeClient client = new FakeClient();
        SourceList list = lazyListing(client, levels("", level(l("f"), l("a"))));
        list.refresh(); // holds 1 source; the server has 5000
        client.childKeys.clear();
        client.health.add(h(5000));
        list.poll();
        assertTrue(client.childKeys.isEmpty());
    }

    @Test
    public void clearRestartsTheWatcherBaseline() throws Exception {
        FakeClient client = new FakeClient();
        SourceList list = listing(client, "a"); // baseline would be 1
        client.health.add(h(1));
        list.poll();
        list.clear();
        client.catalog = l("a", "b", "c");
        list.refresh(); // 3 sources now
        client.queries.clear();
        client.health.add(h(3));
        list.poll(); // a stale baseline of 1 would re-list here
        assertEquals(0, client.listings());
    }

    @Test
    public void aPollRunningDuringAClearDoesNotRestoreTheBaseline() throws Exception {
        FakeClient client = new FakeClient();
        SourceList list = listing(client, "a");
        client.health.add(h(1));
        client.healthHook = list::clear; // the clear lands while the poll asks
        list.poll();
        client.healthHook = null;
        client.catalog = l("a", "b", "c");
        list.refresh();
        client.queries.clear();
        client.health.add(h(3));
        list.poll(); // baseline from the new listing, not the interrupted poll's
        assertEquals(0, client.listings());
    }

    @Test
    public void aListenerThatThrowsDoesNotStopTheWatch() throws Exception {
        FakeClient client = new FakeClient();
        SourceList list = listing(client, "a");
        client.health.add(h(2));
        list.setOnChanged(m -> {
            throw new IllegalArgumentException("boom");
        });
        client.catalog = l("a", "b");
        list.poll();
        assertEquals(2, list.getSources().size());
    }

    @Test
    public void theScheduledWatcherPollsAndStops() throws Exception {
        FakeClient client = new FakeClient();
        client.catalog = l("a");
        java.util.concurrent.ScheduledExecutorService exec =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        try {
            SourceList list = new SourceList(() -> client, 5, exec);
            list.refresh();
            client.health.add(h(2));
            client.catalog = l("a", "b");
            CountDownLatch changed = new CountDownLatch(1);
            list.setOnChanged(m -> changed.countDown());
            list.startWatch();
            list.startWatch(); // idempotent
            assertTrue(changed.await(5, TimeUnit.SECONDS));
            assertEquals(set("a", "b"), list.getSources().keySet());

            list.close();
            assertFalse(exec.isShutdown()); // an injected executor is the caller's
            exec.submit(() -> { }).get(); // any in-flight poll has finished
            int calls = client.healthCalls.get();
            Thread.sleep(60);
            assertEquals(calls, client.healthCalls.get());
        } finally {
            exec.shutdownNow();
        }
    }

    // --- more states -----------------------------------------------------------

    @Test
    public void clearLetsTheNextRefreshTryLevelsAgain() throws Exception {
        FakeClient client = new FakeClient();
        client.catalog = l("a");
        SourceList list = new SourceList(client);
        list.refresh(); // old server: latched as unsupported
        assertFalse(list.isLazy());
        client.levels = levels("", level(l(), l("a")));
        list.refresh();
        assertFalse(list.isLazy()); // still latched
        list.clear();
        list.refresh();
        assertTrue(list.isLazy());
    }

    @Test
    public void aWholeListingReadBegunBeforeAClearIsDropped() throws Exception {
        FakeClient client = new FakeClient();
        client.catalog = l("a");
        SourceList list = new SourceList(client);
        client.healthHook = null;
        client.childrenHook = (k, n) -> {
            list.clear(); // lands after the epoch was taken, before the listing commits
            throw new UnsupportedOperationException("old");
        };
        try {
            list.refresh();
            fail("should have been dropped");
        } catch (IllegalStateException expected) {
            // stale epoch
        }
        assertTrue(list.getSources().isEmpty());
    }

    @Test
    public void aMalformedLevelIsRejected() throws Exception {
        try {
            new CatalogClient.Level("", null, l().isEmpty() ? new ArrayList<>() : null, false);
            fail("a level without its lists is malformed");
        } catch (NullPointerException expected) {
            // rejected
        }
        FakeClient client = new FakeClient();
        client.levels = levels("", level(l("a"), l()), "a", null); // the server's answer for "a" is unusable
        SourceList list = new SourceList(client);
        list.refresh();
        try {
            list.loadLevel("a");
            fail("should have thrown");
        } catch (IOException expected) {
            // not kept
        }
        assertFalse(list.getLevels().containsKey("a"));
    }

    @Test
    public void aNewerFolderReadIsNotOverwrittenByAnOlderReList() throws Exception {
        CatalogClient.Level old = level(l(), l("x"));
        CatalogClient.Level newer = level(l(), l("x", "y"));
        Map<String, CatalogClient.Level> served = levels("", level(l("a"), l()), "a", old);
        FakeClient client = new FakeClient();
        SourceList list = lazyListing(client, served);
        list.refresh();
        list.loadLevel("a");
        list.setOpenKeys(() -> set("a"));
        boolean[] done = {false};
        client.childrenHook = (key, limit) -> {
            CatalogClient.Level answer = served.get(key);
            if (key.equals("a") && !done[0]) {
                done[0] = true; // the re-list has read the old "a"; a newer read lands before it commits
                served.put("a", newer);
                try {
                    list.loadLevel("a");
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }
            return answer;
        };
        list.refresh();
        assertSame(newer, list.getLevels().get("a"));
    }

    @Test
    public void sqlHelpers() {
        assertEquals("'it''s'", SourceList.quote("it's"));
        assertEquals("SELECT x FROM sources ORDER BY source_id", SourceList.sourcesSql("x"));
    }
}
