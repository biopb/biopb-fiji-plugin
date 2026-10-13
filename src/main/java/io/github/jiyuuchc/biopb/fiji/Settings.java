package io.github.jiyuuchc.biopb.fiji;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
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
 * wrong type (a string where a number belongs, a fractional value for a whole-number key, ...),
 * reads as its default; an unreadable file reads as all defaults.
 *
 * <p>Writes keep what this class does not own: unknown sections and keys (napari-only
 * settings, or a newer client's) are re-read from the file at save time and written back
 * untouched, and only the keys {@link #set} changed are replaced, so a concurrent edit by the
 * other client to a different key is not lost. The write is a temporary file renamed over the
 * target (through a symlink to the real file, keeping its permissions), so a reader never sees a
 * half-written file. If the existing file cannot be read (an I/O error, not merely bad JSON) the
 * save is abandoned and the file left untouched.
 *
 * <p>Thread-safe. The lock is in-process only: across processes (Fiji and napari at once) writes
 * are best-effort, last writer wins, and a key both change at the same moment keeps one value. A
 * long-lived instance notices the file changing (modification time or size) at the next get.
 *
 * <p>The tensor browser reads none of the keys yet: all of {@link #DEFAULTS} are napari's
 * ProcessImage-widget settings, kept here so the file contract is complete and typed getters
 * exist for the features that grow an equivalent.
 */
public final class Settings {
    private static final Logger LOG = Logger.getLogger(Settings.class.getName());

    /** The file's name inside biopb's config directory. */
    public static final String FILE_NAME = "napari-widget.json";

    /**
     * Every known {@code "section.key"} and its default; the default's class is the key's type
     * (Boolean, String, Long for a whole-number key, Double for a number key).
     */
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

    private final Path file; // null: defaults-only, nothing is read or written
    private final Object lock = new Object();
    private Map<String, Object> values; // guarded by lock; null until first read
    private Stamp stamp; // guarded by lock: the file as values were read from it
    private final Map<String, Object> dirty = new LinkedHashMap<>(); // guarded by lock: set, not yet saved

    /** The settings in {@code file}; null for defaults-only. */
    public Settings(Path file) {
        this.file = file;
    }

    /** The settings in biopb's config directory, as the environment says. */
    public static Settings forEnvironment() {
        return forEnvironment(System::getenv, System::getProperty);
    }

    /**
     * As {@link #forEnvironment()} with the environment and system properties injected. With no
     * usable config directory ({@code BIOPB_CONFIG_HOME} unset or relative, and {@code user.home}
     * unset, blank or relative) the result is defaults-only: sets are held in memory, never written.
     */
    static Settings forEnvironment(Function<String, String> env, Function<String, String> props) {
        String h = props.apply("user.home");
        Path home = null;
        if (h != null && !h.trim().isEmpty()) {
            try {
                Path p = Paths.get(h);
                home = p.isAbsolute() ? p : null;
            } catch (RuntimeException e) {
                home = null;
            }
        }
        Path path = settingsPath(env, home);
        if (path == null) {
            LOG.warning("No config directory (user.home unset or relative, no BIOPB_CONFIG_HOME): settings are not persisted");
        }
        return new Settings(path);
    }

    /**
     * biopb's rule: an absolute {@code $BIOPB_CONFIG_HOME}, else {@code <home>/.config}. A relative
     * value is ignored (biopb refuses it; a client falls back instead). Null if neither applies.
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
        if (base == null && home != null) {
            base = home.resolve(".config");
        }
        return base == null ? null : base.resolve("biopb").resolve(FILE_NAME);
    }

    /** Where the settings live; null in defaults-only mode. */
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

    /** A whole-number key. */
    public long getLong(String path) {
        return (Long) get(path, Long.class);
    }

    /** {@link #getLong} clamped to the int range. */
    public int getInt(String path) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, getLong(path)));
    }

    /** A number key (whole-number keys read as doubles too). */
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
     * Sets {@code path} and, if {@code persist}, saves. Whole-number keys take whole numbers, number
     * keys take finite numbers; anything else is refused and changes nothing.
     *
     * @throws IllegalArgumentException for an unknown key or a bad value
     */
    public void set(String path, Object value, boolean persist) {
        Object def = DEFAULTS.get(path);
        if (def == null) {
            throw new IllegalArgumentException("unknown setting: " + path);
        }
        Object norm = normalise(value, def);
        if (norm == null) {
            throw new IllegalArgumentException("bad value for " + path + ": " + value);
        }
        synchronized (lock) {
            load().put(path, norm);
            dirty.put(path, norm);
            if (persist) {
                save();
            }
        }
    }

    /** {@link #set(String, Object, boolean)} and save. */
    public void set(String path, Object value) {
        set(path, value, true);
    }

    /** {@code value} as {@code def}'s type (Long, Double, Boolean, String), or null if it is not one. */
    static Object normalise(Object value, Object def) {
        if (value == null) {
            return null;
        }
        if (def instanceof Boolean) {
            return value instanceof Boolean ? value : null;
        }
        if (def instanceof String) {
            return value instanceof String ? value : null;
        }
        if (!(value instanceof Number)) {
            return null;
        }
        Number n = (Number) value;
        if (def instanceof Double) {
            double d = n.doubleValue();
            return Double.isNaN(d) || Double.isInfinite(d) ? null : (Object) d;
        }
        if (n instanceof Long || n instanceof Integer || n instanceof Short || n instanceof Byte) {
            return n.longValue();
        }
        try {
            BigDecimal bd = n instanceof BigDecimal ? (BigDecimal) n : new BigDecimal(n.toString());
            return bd.longValueExact();
        } catch (ArithmeticException | NumberFormatException e) { // fractional, out of range, NaN, Infinity
            return null;
        }
    }

    /** Drops what was read, so the next get re-reads the file. Unsaved sets are lost. */
    public void reload() {
        synchronized (lock) {
            values = null;
            stamp = null;
            dirty.clear();
        }
    }

    /**
     * Writes the keys set since the last save over the file's current content, atomically. A failure
     * is logged, not thrown: settings are a convenience. If the existing file cannot be read the
     * save is abandoned (the keys stay pending); only an absent or malformed file is replaced.
     */
    public void save() {
        synchronized (lock) {
            if (dirty.isEmpty()) {
                return;
            }
            if (file == null) {
                dirty.clear();
                return;
            }
            Path tmp = null;
            try {
                Path target = Files.exists(file) ? file.toRealPath() : file.toAbsolutePath();
                Tree read = readTree(target);
                if (read.failed != null) {
                    LOG.log(Level.WARNING, "Not saving: could not read " + target, read.failed);
                    return;
                }
                JsonObject root = read.root != null ? read.root : new JsonObject();
                for (Map.Entry<String, Object> e : dirty.entrySet()) {
                    String path = e.getKey();
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
                    section.add(path.substring(dot + 1), toJson(e.getValue()));
                }
                Path dir = target.getParent();
                Files.createDirectories(dir);
                tmp = Files.createTempFile(dir, "napari-widget", ".tmp");
                if (Files.exists(target)) {
                    try {
                        Files.setPosixFilePermissions(tmp, Files.getPosixFilePermissions(target));
                    } catch (UnsupportedOperationException ignored) {
                        // not a POSIX file system
                    }
                }
                Files.write(tmp, new GsonBuilder().setPrettyPrinting().create().toJson(root).getBytes(StandardCharsets.UTF_8));
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
                tmp = null;
                dirty.clear();
                values = null; // re-read next get: the file now also holds the other client's keys
                stamp = null;
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

    /** The file as values were read: modification time and size, both null if absent. */
    private static final class Stamp {
        final FileTime mtime;
        final Long size;

        Stamp(FileTime mtime, Long size) {
            this.mtime = mtime;
            this.size = size;
        }

        static Stamp of(Path f) {
            try {
                BasicFileAttributes a = Files.readAttributes(f, BasicFileAttributes.class);
                return new Stamp(a.lastModifiedTime(), a.size());
            } catch (IOException e) {
                return new Stamp(null, null);
            }
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Stamp && Objects.equals(mtime, ((Stamp) o).mtime) && Objects.equals(size, ((Stamp) o).size);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mtime, size);
        }
    }

    /** A read of the file: the object, or none (absent or malformed), or an I/O failure. */
    private static final class Tree {
        final JsonObject root; // null: absent or malformed
        final IOException failed; // non-null: could not be read at all

        Tree(JsonObject root, IOException failed) {
            this.root = root;
            this.failed = failed;
        }
    }

    private static Tree readTree(Path f) {
        try {
            String text = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
            JsonElement e = JsonParser.parseString(text);
            return new Tree(e.isJsonObject() ? e.getAsJsonObject() : null, null);
        } catch (NoSuchFileException e) {
            return new Tree(null, null);
        } catch (JsonParseException e) {
            LOG.warning("Ignoring malformed " + f + ": " + e);
            return new Tree(null, null);
        } catch (IOException e) {
            return new Tree(null, e);
        }
    }

    private Map<String, Object> load() { // caller holds lock
        Stamp now = file == null ? null : Stamp.of(file);
        if (values == null || (file != null && !now.equals(stamp))) {
            Map<String, Object> v = new LinkedHashMap<>(DEFAULTS);
            if (file != null) {
                Tree t = readTree(file);
                if (t.failed != null) {
                    LOG.log(Level.WARNING, "Ignoring unreadable " + file, t.failed);
                }
                if (t.root != null) {
                    for (Map.Entry<String, Object> d : DEFAULTS.entrySet()) {
                        int dot = d.getKey().indexOf('.');
                        JsonElement s = t.root.get(d.getKey().substring(0, dot));
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
            }
            v.putAll(dirty); // unsaved sets win over the file
            values = v;
            stamp = now;
        }
        return values;
    }

    /** {@code e} as {@code def}'s type, or null if it is not that type (fractional for a whole-number key, ...). */
    private static Object fromJson(JsonElement e, Object def) {
        if (!e.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (def instanceof Boolean) {
            return p.isBoolean() ? (Object) p.getAsBoolean() : null;
        }
        if (def instanceof Number) {
            return p.isNumber() ? normalise(p.getAsBigDecimal(), def) : null;
        }
        return p.isString() ? p.getAsString() : null;
    }
}
