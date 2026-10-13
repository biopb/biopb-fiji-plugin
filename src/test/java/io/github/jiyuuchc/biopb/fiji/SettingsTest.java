package io.github.jiyuuchc.biopb.fiji;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** {@link Settings}: defaults under a partial or bad file, writes that keep foreign keys, the path rules. */
public class SettingsTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private Path file;

    @Before
    public void setUp() {
        file = tmp.getRoot().toPath().resolve("biopb").resolve(Settings.FILE_NAME);
    }

    private void write(String json) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, json.getBytes(StandardCharsets.UTF_8));
    }

    private JsonObject read() throws IOException {
        return JsonParser.parseString(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test
    public void noFileReadsTheDefaults() {
        Settings s = new Settings(file);
        assertEquals("localhost:50051", s.getString("widget.server_url"));
        assertFalse(s.getBoolean("widget.is_3d"));
        assertEquals(300, s.getLong("timeout.process_image"));
        assertEquals(5.0, s.getDouble("timeout.health_check"), 0);
        assertEquals(2000, s.getInt("memory.error_threshold_mb"));
    }

    @Test
    public void aStoredKeyOverridesAndTheRestDefault() throws IOException {
        write("{\"memory\": {\"warn_threshold_mb\": 100}}");
        Settings s = new Settings(file);
        assertEquals(100, s.getLong("memory.warn_threshold_mb"));
        assertEquals(2000, s.getLong("memory.error_threshold_mb"));
    }

    @Test
    public void wrongTypedValuesReadAsTheirDefault() throws IOException {
        write("{\"widget\": {\"is_3d\": \"yes\", \"server_url\": 5}, \"memory\": {\"warn_threshold_mb\": \"big\"},"
                + " \"grpc\": {\"max_concurrent_calls\": true}, \"timeout\": [1]}");
        Settings s = new Settings(file);
        assertFalse(s.getBoolean("widget.is_3d"));
        assertEquals("localhost:50051", s.getString("widget.server_url"));
        assertEquals(500, s.getLong("memory.warn_threshold_mb"));
        assertEquals(4, s.getLong("grpc.max_concurrent_calls"));
        assertEquals(10.0, s.getDouble("timeout.get_op_names"), 0);
    }

    @Test
    public void anIntegerForAFloatKeyAndAWholeFloatForAnIntegerKeyAreAccepted() throws IOException {
        write("{\"timeout\": {\"health_check\": 7, \"process_image\": 60.0}}");
        Settings s = new Settings(file);
        assertEquals(7.0, s.getDouble("timeout.health_check"), 0);
        assertEquals(60, s.getLong("timeout.process_image"));
    }

    @Test
    public void fractionalOrOutOfRangeValuesForIntegerKeysReadAsDefault() throws IOException {
        write("{\"timeout\": {\"process_image\": 60.5, \"health_check\": 1e999},"
                + " \"memory\": {\"warn_threshold_mb\": 1e30, \"error_threshold_mb\": 9007199254740993},"
                + " \"grpc\": {\"max_concurrent_calls\": 9223372036854775808}}");
        Settings s = new Settings(file);
        assertEquals(300, s.getLong("timeout.process_image"));
        assertEquals(5.0, s.getDouble("timeout.health_check"), 0);
        assertEquals(500, s.getLong("memory.warn_threshold_mb"));
        assertEquals(9007199254740993L, s.getLong("memory.error_threshold_mb")); // exact, no double round trip
        assertEquals(4, s.getLong("grpc.max_concurrent_calls"));
    }

    @Test
    public void badSetsAreRefusedAndNeverPoisonTheDirtySet() throws IOException {
        Settings s = new Settings(file);
        Object[][] bad = {{"timeout.health_check", Double.NaN}, {"timeout.health_check", Double.POSITIVE_INFINITY},
                {"memory.warn_threshold_mb", 1.5}, {"memory.warn_threshold_mb", Double.NaN},
                {"memory.warn_threshold_mb", 1e30}, {"memory.warn_threshold_mb", Float.POSITIVE_INFINITY}};
        for (Object[] b : bad) {
            try {
                s.set((String) b[0], b[1], false);
                fail("accepted " + b[1]);
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
        s.set("widget.is_3d", true); // a later good save works and writes nothing bad
        JsonObject out = read();
        assertEquals(1, out.size());
        assertEquals(500, s.getLong("memory.warn_threshold_mb"));
    }

    @Test
    public void setNormalisesNumbers() throws IOException {
        Settings s = new Settings(file);
        s.set("memory.warn_threshold_mb", 100); // Integer
        s.set("timeout.process_image", 12.0); // whole Double for an integer key
        s.set("timeout.health_check", 3); // Integer for a float key
        assertEquals(100, s.getLong("memory.warn_threshold_mb"));
        assertEquals(12, s.getLong("timeout.process_image"));
        assertEquals(3.0, s.getDouble("timeout.health_check"), 0);
        assertEquals(12, read().getAsJsonObject("timeout").get("process_image").getAsLong());
    }

    @Test
    public void anIoErrorReadingTheFileAbortsTheSaveAndLeavesItAlone() throws IOException {
        Files.createDirectories(file); // a directory where the file should be: readable? no
        Settings s = new Settings(file);
        s.set("widget.is_3d", true);
        assertTrue(Files.isDirectory(file));
        try (java.util.stream.Stream<Path> files = Files.list(file.getParent())) {
            assertEquals(1, files.count()); // no temp file left
        }
        assertTrue(s.getBoolean("widget.is_3d")); // still pending in memory
    }

    @Test
    public void aLongLivedInstanceSeesAnotherClientsEdit() throws IOException {
        write("{\"memory\": {\"warn_threshold_mb\": 100}}");
        Settings s = new Settings(file);
        assertEquals(100, s.getLong("memory.warn_threshold_mb"));
        write("{\"memory\": {\"warn_threshold_mb\": 12345}}");
        assertEquals(12345, s.getLong("memory.warn_threshold_mb"));
        Files.delete(file);
        assertEquals(500, s.getLong("memory.warn_threshold_mb"));
    }

    @Test
    public void unsavedSetsSurviveAFileChange() throws IOException {
        write("{\"memory\": {\"warn_threshold_mb\": 100}}");
        Settings s = new Settings(file);
        s.set("memory.warn_threshold_mb", 7L, false);
        write("{\"memory\": {\"warn_threshold_mb\": 100, \"error_threshold_mb\": 4000}}");
        assertEquals(7, s.getLong("memory.warn_threshold_mb"));
        assertEquals(4000, s.getLong("memory.error_threshold_mb"));
    }

    @Test
    public void savePreservesPermissionsAndWritesThroughASymlink() throws IOException {
        org.junit.Assume.assumeTrue(file.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path real = tmp.newFolder("real").toPath().resolve("data.json");
        Files.write(real, "{}".getBytes(StandardCharsets.UTF_8));
        java.util.Set<java.nio.file.attribute.PosixFilePermission> perms =
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--");
        Files.setPosixFilePermissions(real, perms);
        Files.createDirectories(file.getParent());
        Files.createSymbolicLink(file, real);
        new Settings(file).set("widget.is_3d", true);
        assertTrue(Files.isSymbolicLink(file));
        assertEquals(perms, Files.getPosixFilePermissions(real));
        assertTrue(JsonParser.parseString(new String(Files.readAllBytes(real), StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonObject("widget").get("is_3d").getAsBoolean());
    }

    @Test
    public void unreadableOrNonObjectFilesReadAsDefaults() throws IOException {
        write("{not json");
        assertEquals(500, new Settings(file).getLong("memory.warn_threshold_mb"));
        write("[1, 2]");
        assertEquals(500, new Settings(file).getLong("memory.warn_threshold_mb"));
    }

    @Test
    public void setWritesThroughAndSurvivesAReload() throws IOException {
        Settings s = new Settings(file);
        s.set("widget.server_url", "host:1");
        s.reload();
        assertEquals("host:1", s.getString("widget.server_url"));
        assertEquals("host:1", read().getAsJsonObject("widget").get("server_url").getAsString());
        assertTrue(new Settings(file).getString("widget.server_url").equals("host:1"));
    }

    @Test
    public void aBatchedSetIsNotWrittenUntilSave() {
        Settings s = new Settings(file);
        s.set("widget.is_3d", true, false);
        assertFalse(Files.exists(file));
        s.save();
        assertTrue(Files.exists(file));
        assertTrue(new Settings(file).getBoolean("widget.is_3d"));
    }

    @Test
    public void saveKeepsUnknownKeysAndKeysItDidNotSet() throws IOException {
        write("{\"widget\": {\"server_url\": \"a:1\", \"future\": [1]}, \"napari_only\": {\"x\": 2}, \"memory\": 7}");
        Settings s = new Settings(file);
        s.getString("widget.server_url"); // read first
        // The other client changes a key we did not touch, after our read.
        write("{\"widget\": {\"server_url\": \"a:1\", \"future\": [1], \"is_3d\": true}, \"napari_only\": {\"x\": 2}, \"memory\": 7}");
        s.set("timeout.health_check", 9.5);
        JsonObject out = read();
        assertEquals(2, out.getAsJsonObject("napari_only").get("x").getAsInt());
        assertEquals(1, out.getAsJsonObject("widget").getAsJsonArray("future").size());
        assertTrue(out.getAsJsonObject("widget").get("is_3d").getAsBoolean());
        assertEquals(9.5, out.getAsJsonObject("timeout").get("health_check").getAsDouble(), 0);
        assertEquals(7, out.get("memory").getAsInt());
    }

    @Test
    public void saveOverUnreadableFileReplacesIt() throws IOException {
        write("{not json");
        Settings s = new Settings(file);
        s.set("grpc.max_message_size_mb", 64L);
        assertEquals(64, read().getAsJsonObject("grpc").get("max_message_size_mb").getAsLong());
    }

    @Test
    public void noTempFileIsLeftBehind() throws IOException {
        new Settings(file).set("widget.is_3d", true);
        try (java.util.stream.Stream<Path> files = Files.list(file.getParent())) {
            assertEquals(1, files.count());
        }
    }

    @Test
    public void setRefusesUnknownKeysAndWrongTypes() {
        Settings s = new Settings(file);
        for (Object[] bad : new Object[][] {{"nope.key", 1}, {"widget.is_3d", "x"}, {"memory.warn_threshold_mb", true},
                {"widget.server_url", 3}, {"widget.server_url", null}}) {
            try {
                s.set((String) bad[0], bad[1]);
                fail("accepted " + bad[0]);
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
        assertFalse(Files.exists(file));
    }

    @Test
    public void getterOfTheWrongKindIsRefused() {
        try {
            new Settings(file).getBoolean("widget.server_url");
            fail();
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void unwritableLocationDoesNotThrow() throws IOException {
        Path blocker = tmp.newFile("blocker").toPath(); // a file where the directory should be
        Settings s = new Settings(blocker.resolve("biopb").resolve(Settings.FILE_NAME));
        s.set("widget.is_3d", true);
        assertTrue(s.getBoolean("widget.is_3d")); // still held in memory
    }

    @Test
    public void pathFollowsBiopbConfigHome() {
        Path home = Paths.get("/home/u").toAbsolutePath();
        Map<String, String> env = new HashMap<>();
        assertEquals(home.resolve(".config").resolve("biopb").resolve("napari-widget.json"), Settings.settingsPath(env::get, home));
        assertEquals(null, Settings.settingsPath(env::get, null));
        env.put("XDG_CONFIG_HOME", "/xdg");
        assertEquals(home.resolve(".config/biopb/napari-widget.json"), Settings.settingsPath(env::get, home));
        Path cfg = tmp.getRoot().toPath().toAbsolutePath();
        env.put("BIOPB_CONFIG_HOME", cfg.toString());
        assertEquals(cfg.resolve("biopb").resolve("napari-widget.json"), Settings.settingsPath(env::get, home));
        env.put("BIOPB_CONFIG_HOME", "rel/cfg");
        assertEquals(home.resolve(".config/biopb/napari-widget.json"), Settings.settingsPath(env::get, home));
        env.put("BIOPB_CONFIG_HOME", "");
        assertEquals(home.resolve(".config/biopb/napari-widget.json"), Settings.settingsPath(env::get, home));
    }

    @Test
    public void forEnvironmentUsesInjectedEnvironmentAndHome() {
        Map<String, String> env = new HashMap<>();
        Map<String, String> props = new HashMap<>();
        Path home = tmp.getRoot().toPath().toAbsolutePath();
        props.put("user.home", home.toString());
        assertEquals(home.resolve(".config/biopb/napari-widget.json"), Settings.forEnvironment(env::get, props::get).getFile());
        Path cfg = home.resolve("cfg");
        env.put("BIOPB_CONFIG_HOME", cfg.toString());
        assertEquals(cfg.resolve("biopb/napari-widget.json"), Settings.forEnvironment(env::get, props::get).getFile());
    }

    @Test
    public void forEnvironmentWithoutAHomeIsDefaultsOnly() {
        Map<String, String> env = new HashMap<>();
        for (String home : new String[] {null, "", "   ", "rel/home"}) {
            Map<String, String> props = new HashMap<>();
            if (home != null) {
                props.put("user.home", home);
            }
            Settings s = Settings.forEnvironment(env::get, props::get);
            assertEquals(null, s.getFile());
            s.set("widget.is_3d", true); // held, not written, no exception
            assertTrue(s.getBoolean("widget.is_3d"));
            assertEquals("localhost:50051", s.getString("widget.server_url"));
        }
        // An absolute BIOPB_CONFIG_HOME needs no home.
        env.put("BIOPB_CONFIG_HOME", tmp.getRoot().toPath().toAbsolutePath().toString());
        assertTrue(Settings.forEnvironment(env::get, k -> null).getFile() != null);
    }
}
