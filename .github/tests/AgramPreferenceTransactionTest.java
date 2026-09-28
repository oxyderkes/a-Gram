package org.telegram.messenger;

import android.content.SharedPreferences;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Exercises the production Android adapter with Android's apply-before-disk-commit semantics. */
public final class AgramPreferenceTransactionTest {
    public static void main(String[] args) {
        Fake preferences = new Fake();
        preferences.live.put("user", "original");
        preferences.live.put("slot", "container-a");
        preferences.live.put("consent", false);
        preferences.live.put("count", 4);
        preferences.live.put("time", 8L);
        preferences.live.put("fraction", 0.5f);
        preferences.live.put("set", new HashSet<>(Arrays.asList("a", "b")));
        preferences.disk.putAll(preferences.live);
        Map<String, Object> before = new HashMap<>(preferences.live);
        preferences.results.add(false);
        preferences.results.add(false); // rollback disk failure still restores the process map
        check(!AgramPreferenceTransaction.replace(preferences,
                preferences.edit().clear().putString("user", "replacement").putBoolean("new", true), "new"),
                "failed replacement reported");
        check(preferences.live.equals(before), "all preference types restored in memory");
        check(preferences.disk.equals(before), "previous durable session preserved");

        preferences.results.add(false);
        check(!AgramPreferenceTransaction.commit(preferences,
                preferences.edit().putString("slot", "container-b").putBoolean("new", true), "slot", "new"),
                "failed registry write reported");
        check(preferences.live.equals(before) && preferences.disk.equals(before), "mapping unchanged on failed write");
        check(AgramPreferenceTransaction.commit(preferences,
                preferences.edit().putBoolean("consent", true), "consent"), "successful opt-in");
        check(Boolean.TRUE.equals(preferences.disk.get("consent")), "consent actually durable");

        preferences.results.add(false);
        check(!AgramPreferenceTransaction.replace(preferences, preferences.edit().clear()), "failed logout reported");
        check("original".equals(preferences.live.get("user")), "failed logout preserves runtime preferences");
        check("original".equals(preferences.disk.get("user")), "failed logout preserves disk identity");
        check(AgramPreferenceTransaction.replace(preferences, preferences.edit().clear()), "confirmed durable logout");
        check(preferences.live.isEmpty() && preferences.disk.isEmpty(), "successful clear removes exact account prefs");
        System.out.println("AgramPreferenceTransactionTest: passed");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static final class Fake implements SharedPreferences {
        final Map<String, Object> live = new HashMap<>();
        final Map<String, Object> disk = new HashMap<>();
        final ArrayDeque<Boolean> results = new ArrayDeque<>();
        public Map<String, ?> getAll() { return new HashMap<>(live); }
        public Editor edit() {
            return new Editor() {
                final Map<String, Object> updates = new HashMap<>();
                boolean clear;
                private Editor put(String key, Object value) { updates.put(key, value); return this; }
                public Editor putString(String k, String v) { return put(k, v); }
                public Editor putBoolean(String k, boolean v) { return put(k, v); }
                public Editor putInt(String k, int v) { return put(k, v); }
                public Editor putLong(String k, long v) { return put(k, v); }
                public Editor putFloat(String k, float v) { return put(k, v); }
                public Editor putStringSet(String k, Set<String> v) { return put(k, v); }
                public Editor remove(String k) { return put(k, null); }
                public Editor clear() { clear = true; return this; }
                public boolean commit() {
                    if (clear) live.clear();
                    for (Map.Entry<String, Object> entry : updates.entrySet()) {
                        if (entry.getValue() == null) live.remove(entry.getKey());
                        else live.put(entry.getKey(), entry.getValue());
                    }
                    boolean success = results.isEmpty() || results.removeFirst();
                    if (success) { disk.clear(); disk.putAll(live); }
                    return success;
                }
            };
        }
    }
}
