package android.content;

import java.util.Map;
import java.util.Set;

/** Minimal compile surface for the real Agram preference transaction adapter. */
public interface SharedPreferences {
    Map<String, ?> getAll();
    Editor edit();
    interface Editor {
        Editor putString(String key, String value);
        Editor putBoolean(String key, boolean value);
        Editor putInt(String key, int value);
        Editor putLong(String key, long value);
        Editor putFloat(String key, float value);
        Editor putStringSet(String key, Set<String> value);
        Editor remove(String key);
        Editor clear();
        boolean commit();
    }
}
