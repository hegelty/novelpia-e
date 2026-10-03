package me.crema.novelia.input;

import android.content.SharedPreferences;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Minimal in-memory SharedPreferences for JVM tests. It intentionally has no
 * real storage and is only used by {@code KeyBindingsTest} to exercise the
 * save/migrate contract on the key-value layer without an Android runtime.
 */
final class TestSharedPreferences implements SharedPreferences {

    private final Map<String, Object> values = new LinkedHashMap<String, Object>();

    void put(String key, Object value) {
        values.put(key, value);
    }

    Map<String, Object> dump() {
        return new LinkedHashMap<String, Object>(values);
    }

    @Override
    public boolean contains(String key) {
        return values.containsKey(key);
    }

    @Override public String getString(String key, String defValue) {
        Object value = values.get(key);
        return value instanceof String ? (String) value : defValue;
    }

    @Override public Set<String> getStringSet(String key, Set<String> defValues) {
        Object value = values.get(key);
        if (value instanceof Set) {
            @SuppressWarnings("unchecked")
            Set<String> set = (Set<String>) value;
            return set;
        }
        return defValues;
    }

    @Override public int getInt(String key, int defValue) {
        Object value = values.get(key);
        return value instanceof Integer ? ((Integer) value).intValue() : defValue;
    }

    @Override public long getLong(String key, long defValue) {
        Object value = values.get(key);
        return value instanceof Long ? ((Long) value).longValue() : defValue;
    }

    @Override public float getFloat(String key, float defValue) {
        Object value = values.get(key);
        return value instanceof Float ? ((Float) value).floatValue() : defValue;
    }

    @Override public boolean getBoolean(String key, boolean defValue) {
        Object value = values.get(key);
        return value instanceof Boolean ? ((Boolean) value).booleanValue() : defValue;
    }

    @Override public Map<String, ?> getAll() {
        return dump();
    }

    @Override public Editor edit() {
        return new Editor() {
            @Override public Editor putString(String key, String value) {
                values.put(key, value);
                return this;
            }

            @Override public Editor putStringSet(String key, Set<String> valuesSet) {
                values.put(key, valuesSet);
                return this;
            }

            @Override public Editor putInt(String key, int value) {
                values.put(key, Integer.valueOf(value));
                return this;
            }

            @Override public Editor putLong(String key, long value) {
                values.put(key, Long.valueOf(value));
                return this;
            }

            @Override public Editor putFloat(String key, float value) {
                values.put(key, Float.valueOf(value));
                return this;
            }

            @Override public Editor putBoolean(String key, boolean value) {
                values.put(key, Boolean.valueOf(value));
                return this;
            }

            @Override public Editor remove(String key) {
                values.remove(key);
                return this;
            }

            @Override public Editor clear() {
                values.clear();
                return this;
            }

            @Override public boolean commit() {
                return true;
            }

            @Override public void apply() {
                // In-memory: already applied.
            }
        };
    }

    @Override public void registerOnSharedPreferenceChangeListener(
            OnSharedPreferenceChangeListener listener) {
    }

    @Override public void unregisterOnSharedPreferenceChangeListener(
            OnSharedPreferenceChangeListener listener) {
    }
}
