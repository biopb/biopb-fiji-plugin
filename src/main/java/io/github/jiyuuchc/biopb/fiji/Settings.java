package io.github.jiyuuchc.biopb.fiji;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Typed, in-memory settings for the plugin, keyed by dotted path ({@code "section.key"}).
 *
 * <p>Nothing is persisted: the napari widget's {@code napari-widget.json} holds only the
 * ProcessImage widgets' settings, which this plugin has no use for, so reads return the registered
 * defaults and {@link #set} lasts for the life of the instance. The class is the hook for a feature
 * that needs a tunable: register its key and default in {@link #DEFAULTS}, read it with the typed
 * getters, and persistence can be added behind {@link #set} later without changing callers.
 */
public final class Settings {

    /** Registered keys and their defaults. Empty until a feature needs one. */
    public static final Map<String, Object> DEFAULTS;

    static {
        DEFAULTS = Collections.unmodifiableMap(new LinkedHashMap<String, Object>());
    }

    private final Map<String, Object> values = new LinkedHashMap<>();

    public Settings() {
        this(DEFAULTS);
    }

    /** For tests and callers that bring their own keys. */
    Settings(Map<String, Object> defaults) {
        defaults.forEach(this::check);
        values.putAll(defaults);
    }

    private final Map<String, Class<?>> types = new LinkedHashMap<>();

    private void check(String path, Object value) {
        if (value == null) {
            throw new IllegalArgumentException("null default for " + path);
        }
        types.put(path, kind(value));
    }

    private static Class<?> kind(Object v) {
        if (v instanceof Boolean) return Boolean.class;
        if (v instanceof String) return String.class;
        if (v instanceof Number) return Number.class;
        throw new IllegalArgumentException("unsupported setting type " + v.getClass().getName());
    }

    public synchronized String getString(String path) {
        return (String) get(path, String.class);
    }

    public synchronized boolean getBoolean(String path) {
        return (Boolean) get(path, Boolean.class);
    }

    public synchronized long getLong(String path) {
        return ((Number) get(path, Number.class)).longValue();
    }

    public synchronized int getInt(String path) {
        return Math.toIntExact(getLong(path));
    }

    public synchronized double getDouble(String path) {
        return ((Number) get(path, Number.class)).doubleValue();
    }

    /** Sets a registered key for the life of this instance. Unknown keys and wrong types are refused. */
    public synchronized void set(String path, Object value) {
        Class<?> want = types.get(path);
        if (want == null) {
            throw new IllegalArgumentException("unknown setting " + path);
        }
        if (value == null || kind(value) != want) {
            throw new IllegalArgumentException("wrong type for " + path);
        }
        values.put(path, value);
    }

    private Object get(String path, Class<?> want) {
        Object v = values.get(path);
        if (v == null) {
            throw new IllegalArgumentException("unknown setting " + path);
        }
        if (kind(v) != want) {
            throw new IllegalArgumentException(path + " is not a " + want.getSimpleName());
        }
        return v;
    }
}
