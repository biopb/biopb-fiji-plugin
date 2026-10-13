package io.github.jiyuuchc.biopb.fiji;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

public class SettingsTest {

    private static Settings sample() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("a.name", "x");
        d.put("a.flag", false);
        d.put("a.count", 5);
        d.put("a.ratio", 0.5);
        return new Settings(d);
    }

    @Test
    public void readsTheDefaults() {
        Settings s = sample();
        assertEquals("x", s.getString("a.name"));
        assertFalse(s.getBoolean("a.flag"));
        assertEquals(5, s.getInt("a.count"));
        assertEquals(5L, s.getLong("a.count"));
        assertEquals(0.5, s.getDouble("a.ratio"), 0);
    }

    @Test
    public void setLastsForTheInstanceOnly() {
        Settings s = sample();
        s.set("a.count", 9);
        assertEquals(9, s.getInt("a.count"));
        assertEquals(5, sample().getInt("a.count"));
    }

    @Test
    public void refusesUnknownKeysAndWrongTypes() {
        Settings s = sample();
        for (Object[] bad : new Object[][] {{"nope", 1}, {"a.flag", "x"}, {"a.count", true}, {"a.name", 3}, {"a.name", null}}) {
            try {
                s.set((String) bad[0], bad[1]);
                fail("accepted " + bad[0]);
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
    }

    @Test
    public void aGetterOfTheWrongKindIsRefused() {
        try {
            sample().getBoolean("a.name");
            fail();
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void noKeysAreRegisteredByDefault() {
        assertEquals(0, Settings.DEFAULTS.size());
    }
}
