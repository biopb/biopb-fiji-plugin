package io.github.jiyuuchc.biopb.fiji;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The persisted settings: defaults, overridden by a JSON file. A Swing-free port of the napari
 * widget's {@code _settings.py}, sharing its file and key names so the two clients agree.
 *
 * <p>The file is {@code napari-widget.json} in biopb's config directory,
 * {@code $BIOPB_CONFIG_HOME/biopb} (an absolute path; default {@code ~/.config/biopb} on every
 * platform; {@code XDG_CONFIG_HOME} is not read). It is a JSON object of sections of keys,
 * e.g. {@code {"memory": {"warn_threshold_mb": 500}}}. A key missing from the file, or of the
 * wrong type (a string where a number belongs, ...), reads as its default; an unreadable file
 * reads as all defaults.
 *
 * <p>Writes keep what this class does not own: unknown sections and keys (napari-only
 * settings, or a newer client's) are re-read from the file at save time and written back
 * untouched, and only the keys {@link #set} changed are replaced, so a concurrent edit by the
 * other client to a different key is not lost. The write is a temporary file renamed over the
 * target, under a lock. Thread-safe.
 *
 * <p>The tensor browser reads none of the keys yet: all of {@link #DEFAULTS} are napari's
 * ProcessImage-widget settings, kept here so the file contract is complete and typed getters
 * exist for the features that grow an equivalent.
 */
public final class Settings {
    private static final Logger LOG = Logger.getLogger(Settings.class.getName());

    /** The file's name inside biopb's config directory. */
    public static final String FILE_NAME = "napari-widget.json";

    /** Every known {@code "section.key"} and its default; the default's class is the key's type. */
    public static final Map<String, Object> DEFAULTS;

    static {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("widget.server_url", "localhost:50051");
        d.put("widget.is_3d", Boolean.FALSE);
        d.put("timeout.health_check", 5.0);
        d.put("timeout.get_op_names", 10.0);
        d.put("timeout.process_image", 300L);
        d.put("grpc.max_message_size_mb", 512L);
        d.put("grpc.max_concurrent_calls", 4L);
        d.put("memory.warn_threshold_mb", 500L);
        d.put("memory.error_threshold_mb", 2000L);
        DEFAULTS = Collections.unmodifiableMap(d);
    }

    private final Path file;
    private final Object lock = new Object();
    private Map<String, Object> values; // guarded by lock; null until first read
    private final Set<String> dirty = new HashSet<>(); // guarded by lock: set but not yet saved

    /** The settings in {@code file}. */
    public Settings(Path file) {
        this.file = file;
    }

    /** The settings in biopb's config directory, as the environment says. */
    public static Settings forEnvironment() {
        return new Settings(settingsPath(System::getenv, Paths.get(System.getProperty("user.home", "."))));
    }

    /**
     * biopb's rule: an absolute {@code $BIOPB_CONFIG_HOME}, else {@code <home>/.config}. A relative
     * value is ignored (biopb refuses it; a client falls back instead).
     */
    static Path settingsPath(Function<String, String> env, Path home) {
        String raw = env.apply("BIOPB_CONFIG_HOME");
        Path base = null;
        if (raw != null && !raw.isEmpty()) {
            try {
                Path p = Paths.get(raw);
                if (p.isAbsolute()) {
                    base = p;
                } else {
                    LOG.warning("Ignoring relative BIOPB_CONFIG_HOME=" + raw);
                }
            } catch (RuntimeException e) {
                LOG.warning("Ignoring invalid BIOPB_CONFIG_HOME=" + raw);
            }
        }
        if (base == null) {
            base = home.resolve(".config");
        }
        return base.resolve("biopb").resolve(FILE_NAME);
    }

    /** Where the settings live. */
    public Path getFile() {
        return file;
    }

    /** The string at {@code "section.key"}. */
    public String getString(String path) {
        return (String) get(path, String.class);
    }

    public boolean getBoolean(String path) {
        return (Boolean) get(path, Boolean.class);
    }

    /** A whole-number key; a stored fractional value is truncated. */
    public long getLong(String path) {
        return ((Number) get(path, Number.class)).longValue();
    }

    /** {@link #getLong} clamped to the int range. */
    public int getInt(String path) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, getLong(path)));
    }

    public double getDouble(String path) {
        return ((Number) get(path, Number.class)).doubleValue();
    }

    private Object get(String path, Class<?> want) {
        Object def = DEFAULTS.get(path);
        if (def == null || !want.isInstance(def)) {
            throw new IllegalArgumentException("not a " + want.getSimpleName() + " setting: " + path);
        }
        synchronized (lock) {
            return load().get(path);
        }
    }

    /**
     * Sets {@code path} and, if {@code persist}, saves. A value of the wrong type for the key is
     * refused.
     *
     * @throws IllegalArgumentException for an unknown key or a wrong-typed value
     */
    public void set(String path, Object value, boolean persist) {
        Object def = DEFAULTS.get(path);
        if (def == null) {
            throw new IllegalArgumentException("unknown setting: " + path);
        }
        if (!typeOk(value, def)) {
            throw new IllegalArgumentException("wrong type for " + path + ": " + value);
        }
        synchronized (lock) {
            load().put(path, value);
            dirty.add(path);
            if (persist) {
                save();
            }
        }
    }

    /** {@link #set(String, Object, boolean)} and save. */
    public void set(String path, Object value) {
        set(path, value, true);
    }

    /** Whether {@code value} is of {@code def}'s type: booleans only for booleans, numbers for numbers. */
    static boolean typeOk(Object value, Object def) {
        if (value == null) {
            return false;
        }
        if (def instanceof Boolean) {
            return value instanceof Boolean;
        }
        if (def instanceof Number) {
            return value instanceof Number;
        }
        return def.getClass().isInstance(value);
    }

    /** Drops what was read, so the next get re-reads the file. Unsaved sets are lost. */
    public void reload() {
        synchronized (lock) {
            values = null;
            dirty.clear();
        }
    }

    /**
     * Writes the keys set since the last save over the file's current content, atomically. A failure
     * is logged, not thrown: settings are a convenience.
     */
    public void save() {
        synchronized (lock) {
            if (dirty.isEmpty()) {
                return;
            }
            Path tmp = null;
            try {
                JsonObject root = readTree();
                if (root == null) {
                    root = new JsonObject();
                }
                Map<String, Object> mine = load();
                for (String path : dirty) {
                    int dot = path.indexOf('.');
                    String sectionName = path.substring(0, dot);
                    JsonElement s = root.get(sectionName);
                    JsonObject section;
                    if (s != null && s.isJsonObject()) {
                        section = s.getAsJsonObject();
                    } else {
                        section = new JsonObject();
                        root.add(sectionName, section);
                    }
                    section.add(path.substring(dot + 1), toJson(mine.get(path)));
                }
                Path dir = file.toAbsolutePath().getParent();
                Files.createDirectories(dir);
                tmp = Files.createTempFile(dir, "napari-widget", ".tmp");
                Files.write(tmp, new GsonBuilder().setPrettyPrinting().create().toJson(root).getBytes(StandardCharsets.UTF_8));
                try {
                    Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
                tmp = null;
                dirty.clear();
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.WARNING, "Could not save " + file, e);
            } finally {
                if (tmp != null) {
                    try {
                        Files.deleteIfExists(tmp);
                    } catch (IOException ignored) {
                        // best effort
                    }
                }
            }
        }
    }

    private static JsonElement toJson(Object v) {
        if (v instanceof Boolean) {
            return new JsonPrimitive((Boolean) v);
        }
        if (v instanceof Number) {
            return new JsonPrimitive((Number) v);
        }
        return new JsonPrimitive(String.valueOf(v));
    }

    /** The file's JSON object, or null if absent, unreadable or not an object. */
    private JsonObject readTree() {
        try {
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            JsonElement e = JsonParser.parseString(text);
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (java.nio.file.NoSuchFileException e) {
            return null;
        } catch (IOException | RuntimeException e) {
            LOG.warning("Ignoring unreadable " + file + ": " + e);
            return null;
        }
    }

    private Map<String, Object> load() { // caller holds lock
        if (values == null) {
            Map<String, Object> v = new LinkedHashMap<>(DEFAULTS);
            JsonObject root = readTree();
            if (root != null) {
                for (Map.Entry<String, Object> d : DEFAULTS.entrySet()) {
                    int dot = d.getKey().indexOf('.');
                    JsonElement s = root.get(d.getKey().substring(0, dot));
                    if (s == null || !s.isJsonObject()) {
                        continue;
                    }
                    JsonElement e = s.getAsJsonObject().get(d.getKey().substring(dot + 1));
                    Object parsed = e == null ? null : fromJson(e, d.getValue());
                    if (parsed != null) {
                        v.put(d.getKey(), parsed);
                    }
                }
            }
            values = v;
        }
        return values;
    }

    /** {@code e} as {@code def}'s type, or null if it is not that type. */
    private static Object fromJson(JsonElement e, Object def) {
        if (!e.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (def instanceof Boolean) {
            return p.isBoolean() ? (Object) p.getAsBoolean() : null;
        }
        if (def instanceof Number) {
            if (!p.isNumber()) {
                return null;
            }
            return def instanceof Double ? (Object) p.getAsDouble() : (Object) (long) p.getAsDouble();
        }
        return p.isString() ? p.getAsString() : null;
    }
}
