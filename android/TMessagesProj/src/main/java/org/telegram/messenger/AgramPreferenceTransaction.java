/* This file is part of Agram and is licensed under GNU GPL v2 or later. */
package org.telegram.messenger;

import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Call under the preference owner's lock; rollback only the affected keys. */
final class AgramPreferenceTransaction {
    private AgramPreferenceTransaction() { }

    static boolean commit(SharedPreferences preferences, SharedPreferences.Editor editor, String... keys) {
        Map<String, ?> before = new HashMap<>(preferences.getAll());
        return AgramSessionLifecycle.commitPreserving(editor::commit, () -> {
            SharedPreferences.Editor rollback = preferences.edit();
            for (String key : keys) {
                restore(rollback, key, before.get(key));
            }
            if (!rollback.commit()) {
                FileLog.e("Agram preference rollback remains pending on disk");
            }
        });
    }

    static boolean replace(SharedPreferences preferences, SharedPreferences.Editor editor, String... newKeys) {
        Set<String> keys = new HashSet<>(preferences.getAll().keySet());
        java.util.Collections.addAll(keys, newKeys);
        return commit(preferences, editor, keys.toArray(new String[0]));
    }

    @SuppressWarnings("unchecked")
    private static void restore(SharedPreferences.Editor editor, String key, Object value) {
        if (value == null) editor.remove(key);
        else if (value instanceof String) editor.putString(key, (String) value);
        else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
        else if (value instanceof Integer) editor.putInt(key, (Integer) value);
        else if (value instanceof Long) editor.putLong(key, (Long) value);
        else if (value instanceof Float) editor.putFloat(key, (Float) value);
        else if (value instanceof Set) editor.putStringSet(key, new HashSet<>((Set<String>) value));
    }
}
