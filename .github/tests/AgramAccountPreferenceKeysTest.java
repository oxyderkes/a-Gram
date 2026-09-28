package org.telegram.messenger;

import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Uses the production key selector to exercise slot-0 logout isolation. */
public final class AgramAccountPreferenceKeysTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        Map<String, Object> mixed = new HashMap<>();
        String[] globals = {"passcodeHash1", "passcodeSalt", "appLocked", "useFingerprint", "autoLockIn",
                "allowScreenCapture", "passportConfigJson", "storageCacheDir", "selectedAccount", "futureGlobalSetting"};
        String[] accounts = {"user", "tmpPassword", "terms", "syncContacts", "agram_contacts_choice_v1",
                "contactsSavedCount", "loginTime", "6migrateOffsetAccess", "2pinnedDialogsLoaded0",
                "2pinnedDialogsLoaded19", "2dialogsLoadOffsetId", "2dialogsLoadOffsetId2", "2totalDialogsLoadCount1"};
        for (String key : globals) mixed.put(key, "keep");
        for (String key : accounts) mixed.put(key, "remove");
        for (String key : AgramAccountPreferenceKeys.removalKeys(mixed.keySet())) mixed.remove(key);
        for (String key : globals) check("keep".equals(mixed.get(key)), "global preference erased: " + key);
        for (String key : accounts) check(!mixed.containsKey(key), "account state retained: " + key);
        check(mixed.size() == globals.length, "exact account-owned key removal");
        check(!AgramAccountPreferenceKeys.isAccountOwned("2dialogsLoadOffsetIdBackup"), "prefix overmatch");
        check(!AgramAccountPreferenceKeys.isAccountOwned(null), "null is not account state");
        check(AgramAccountPreferenceKeys.removalKeys(mixed.keySet()).length == 0, "idempotent cleanup");

        Fake preferences = new Fake();
        for (String key : globals) preferences.live.put(key, "keep");
        preferences.live.put("user", "original");
        preferences.live.put("tmpPassword", "old-password");
        preferences.disk.putAll(preferences.live);
        Map<String, Object> before = new HashMap<>(preferences.live);
        preferences.failNext = true;
        check(!removeAccount(preferences), "failed account cleanup reported");
        check(preferences.live.equals(before) && preferences.disk.equals(before), "failed cleanup restores account and globals");
        preferences.throwNext = true;
        try {
            removeAccount(preferences);
            throw new AssertionError("Commit exception not raised");
        } catch (IllegalStateException expected) { }
        check(preferences.live.equals(before) && preferences.disk.equals(before), "throwing commit restores account and globals");
        check(removeAccount(preferences), "durable account cleanup succeeds");
        for (String key : globals) check("keep".equals(preferences.disk.get(key)), "durable global erased: " + key);
        check(!preferences.disk.containsKey("user") && !preferences.disk.containsKey("tmpPassword"), "retired credentials removed");
        check(AgramPreferenceTransaction.commit(preferences, preferences.edit()
                .putString("user", "new-user").putBoolean("agram_contacts_choice_v1", true)
                .putBoolean("syncContacts", false).putInt("loginTime", 123),
                "user", "agram_contacts_choice_v1", "syncContacts", "loginTime"), "new login commits");
        for (String key : globals) check("keep".equals(preferences.disk.get(key)), "new login erased global: " + key);
        System.out.println("PASS AgramAccountPreferenceKeys: slot-zero logout preserves app lock and shared settings");
    }

    private static boolean removeAccount(Fake preferences) {
        String[] keys = AgramAccountPreferenceKeys.removalKeys(preferences.getAll().keySet());
        SharedPreferences.Editor editor = preferences.edit();
        for (String key : keys) editor.remove(key);
        return AgramPreferenceTransaction.commit(preferences, editor, keys);
    }

    /** Models Android committing to its in-memory map before disk IO succeeds. */
    private static final class Fake implements SharedPreferences {
        final Map<String, Object> live = new HashMap<>(), disk = new HashMap<>();
        boolean failNext, throwNext;
        public Map<String, ?> getAll() { return new HashMap<>(live); }
        public Editor edit() {
            return new Editor() {
                final Map<String, Object> updates = new HashMap<>();
                private Editor put(String key, Object value) { updates.put(key, value); return this; }
                public Editor putString(String key, String value) { return put(key, value); }
                public Editor putBoolean(String key, boolean value) { return put(key, value); }
                public Editor putInt(String key, int value) { return put(key, value); }
                public Editor putLong(String key, long value) { return put(key, value); }
                public Editor putFloat(String key, float value) { return put(key, value); }
                public Editor putStringSet(String key, Set<String> value) { return put(key, value); }
                public Editor remove(String key) { return put(key, null); }
                public Editor clear() { throw new AssertionError("Mixed preference file must never be cleared"); }
                public boolean commit() {
                    for (Map.Entry<String, Object> entry : updates.entrySet()) {
                        if (entry.getValue() == null) live.remove(entry.getKey());
                        else live.put(entry.getKey(), entry.getValue());
                    }
                    if (throwNext) { throwNext = false; throw new IllegalStateException("disk unavailable"); }
                    if (failNext) { failNext = false; return false; }
                    disk.clear(); disk.putAll(live);
                    return true;
                }
            };
        }
    }
}
