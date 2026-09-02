/*
 * This file is part of Agram and is licensed under GNU GPL v2 or later.
 */
package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.text.TextUtils;
import android.util.Base64;
import android.util.SparseArray;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Local registry that turns every Telegram engine slot into an explicitly
 * named, independently keyed Agram container. The registry itself contains
 * only slot-to-random-id mappings; private metadata is encrypted with a key
 * that never leaves Android Keystore.
 */
public final class AgramContainerManager {

    public static final int PROFILE_COMPATIBLE = 0;
    public static final int PROFILE_MINIMAL = 1;
    public static final int PROFILE_CUSTOM = 2;
    public static final int PROFILE_PRESET = 3;

    public static final String NETWORK_DIRECT = "direct";
    public static final String NETWORK_PROXY = "custom";
    public static final String NETWORK_TOR = "tor";
    public static final String PUSH_DIRECT = "direct";
    public static final String PUSH_AGRAM = "agram";
    private static final String LEGACY_PUSH_UNIFIED = "unifiedpush";

    public static final int NOTIFICATION_HIDDEN = 0;
    public static final int NOTIFICATION_AUTHOR = 1;
    public static final int NOTIFICATION_FULL = 2;

    public static final String STORAGE_READY = "ready";
    public static final String STORAGE_LOCKED = "locked";
    public static final String STORAGE_QUARANTINED = "quarantined";

    private static final String REGISTRY = "agram_container_registry";
    private static final String SLOT_PREFIX = "slot_";
    private static final String METADATA_PREFIX = "metadata_";
    private static final String PUSH_INSTANCE_HASH_PREFIX = "push_instance_hash_";
    private static final String QUARANTINE_PREFIX = "quarantine_";
    private static final int SCHEMA_VERSION = 6;
    private static final String LEGACY_DURESS_PREFS = "agram_duress_registry";
    private static final String LEGACY_DURESS_SCOPE = "agram_global_duress_v1";
    private static final String LEGACY_CODES_PURGED = "legacy_false_codes_purged_v2";
    private static final int PIN_ITERATIONS = 160_000;

    private static volatile AgramContainerManager instance;

    private final SharedPreferences preferences;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Object sync = new Object();
    private final SparseArray<ContainerRecord> recordCache = new SparseArray<>();

    private static final ProfilePreset[] PROFILE_PRESETS = {
            new ProfilePreset("Google Pixel 9", "Pixel 9", "Android 16"),
            new ProfilePreset("Google Pixel 8", "Pixel 8", "Android 15"),
            new ProfilePreset("Google Pixel 7", "Pixel 7", "Android 14"),
            new ProfilePreset("Samsung Galaxy S24", "SM-S921B", "Android 15"),
            new ProfilePreset("Samsung Galaxy S23", "SM-S911B", "Android 14"),
            new ProfilePreset("OnePlus 12", "CPH2581", "Android 15"),
            new ProfilePreset("Xiaomi 14", "Xiaomi 14", "Android 15"),
            new ProfilePreset("Nothing Phone (2)", "A065", "Android 14"),
            new ProfilePreset("Fairphone 5", "FP5", "Android 14"),
            new ProfilePreset("Sony Xperia 1 VI", "XQ-EC54", "Android 15")
    };

    public static AgramContainerManager getInstance() {
        AgramContainerManager local = instance;
        if (local == null) {
            synchronized (AgramContainerManager.class) {
                local = instance;
                if (local == null) {
                    instance = local = new AgramContainerManager();
                }
            }
        }
        return local;
    }

    private AgramContainerManager() {
        preferences = ApplicationLoader.applicationContext.getSharedPreferences(REGISTRY, Context.MODE_PRIVATE);
        purgeLegacyFalseCodes();
    }

    public ContainerRecord ensureContainer(int account) {
        synchronized (sync) {
            ContainerRecord cached = recordCache.get(account);
            if (cached != null) {
                return cached;
            }
            String id = preferences.getString(SLOT_PREFIX + account, null);
            if (!TextUtils.isEmpty(id)) {
                ContainerRecord record = readRecord(account, id);
                if (record.isStorageAccessible()) {
                    ensureUniquePushInstanceLocked(record);
                }
                recordCache.put(account, record);
                return record;
            }
            ContainerRecord record = createDefault(account);
            ensureUniquePushInstanceLocked(record);
            saveRecord(record);
            return record;
        }
    }

    public ContainerRecord getContainer(int account) {
        synchronized (sync) {
            ContainerRecord cached = recordCache.get(account);
            if (cached != null) {
                return cached;
            }
            String id = preferences.getString(SLOT_PREFIX + account, null);
            ContainerRecord record = TextUtils.isEmpty(id) ? null : readRecord(account, id);
            if (record != null) {
                if (record.isStorageAccessible()) {
                    ensureUniquePushInstanceLocked(record);
                }
                recordCache.put(account, record);
            }
            return record;
        }
    }

    /** Retries a container after a transient Keystore failure without changing its mapping. */
    public ContainerRecord retryContainerAccess(int account) {
        synchronized (sync) {
            recordCache.remove(account);
            return getContainer(account);
        }
    }

    public boolean isContainerAccessible(int account) {
        ContainerRecord record = getContainer(account);
        return record != null && record.isStorageAccessible();
    }

    /**
     * Reconciles the profile lock without inferring logout from a transient
     * inactive UserConfig. Confirmed manual/server logout paths delete the
     * container explicitly; cold-start restoration must never do so.
     */
    public ContainerRecord ensureFreshContainerForSessionState(int account) {
        synchronized (sync) {
            ContainerRecord record = getContainer(account);
            boolean active = UserConfig.getInstance(account).isClientActivated();
            if (record == null) {
                record = ensureContainer(account);
            } else if (record.isStorageAccessible() && active && !record.profileLocked) {
                record.profileLocked = true;
                saveRecord(record);
            }
            return record;
        }
    }

    public void updatePreLoginProfile(int account, int profileMode, int presetIndex,
                                      String deviceModel, String systemVersion,
                                      String systemLanguageCode, String clientLanguageCode,
                                      boolean fixedTimezone, int timezoneOffset, String profileId,
                                      String pin, boolean biometricEnabled, int notificationPrivacy) {
        synchronized (sync) {
            ContainerRecord record = ensureContainer(account);
            if (record.profileLocked) {
                throw new IllegalStateException("Session profile is already locked for this container");
            }
            record.name = defaultName(account);
            record.profileMode = normalizeProfileMode(profileMode);
            record.presetIndex = normalizePresetIndex(presetIndex);
            record.deviceModel = normalizeProfileValue(deviceModel, 64);
            record.systemVersion = normalizeProfileValue(systemVersion, 64);
            // appVersion is deliberately not user-controlled. It is resolved
            // from the installed package each time initConnection is built.
            record.appVersion = "";
            record.systemLanguageCode = normalizeLanguage(systemLanguageCode);
            record.clientLanguageCode = normalizeLanguage(clientLanguageCode);
            record.languageCode = record.clientLanguageCode;
            record.fixedTimezone = fixedTimezone;
            record.timezoneOffset = fixedTimezone ? timezoneOffset : systemTimezoneOffset();
            record.profileId = isUuid(profileId) ? profileId : UUID.randomUUID().toString();
            record.profileGeneratedAt = System.currentTimeMillis();
            record.biometricEnabled = biometricEnabled;
            record.notificationPrivacy = normalizeNotificationPrivacy(notificationPrivacy);
            if (!TextUtils.isEmpty(pin)) {
                setPin(record, pin);
            } else {
                record.pinSalt = null;
                record.pinHash = null;
            }
            saveRecord(record);
        }
    }

    public void markAuthorized(int account) {
        synchronized (sync) {
            ContainerRecord record = ensureContainer(account);
            record.profileLocked = true;
            saveRecord(record);
        }
    }

    /** A logged-out engine slot never retains its old container identity. */
    public void markLoggedOut(int account) {
        deleteContainer(account);
    }

    public boolean verifyPin(int account, String pin) {
        ContainerRecord record = getContainer(account);
        if (record == null || TextUtils.isEmpty(record.pinSalt) || TextUtils.isEmpty(record.pinHash)) {
            return true;
        }
        try {
            byte[] salt = Base64.decode(record.pinSalt, Base64.NO_WRAP);
            byte[] expected = Base64.decode(record.pinHash, Base64.NO_WRAP);
            byte[] actual = derivePin(pin, salt);
            int diff = expected.length ^ actual.length;
            for (int i = 0; i < Math.min(expected.length, actual.length); i++) {
                diff |= expected[i] ^ actual[i];
            }
            return diff == 0;
        } catch (Exception e) {
            FileLog.e("Unable to verify Agram container PIN", e);
            return false;
        }
    }

    public void setNotificationPrivacy(int account, int notificationPrivacy) {
        synchronized (sync) {
            ContainerRecord record = ensureContainer(account);
            record.notificationPrivacy = normalizeNotificationPrivacy(notificationPrivacy);
            saveRecord(record);
        }
    }

    public void updateContainerSecurity(int account, String name, String newPin,
                                        boolean biometricEnabled, int notificationPrivacy) {
        synchronized (sync) {
            ContainerRecord record = ensureContainer(account);
            record.name = TextUtils.isEmpty(name) ? defaultName(account) : name.trim();
            if (!TextUtils.isEmpty(newPin)) {
                setPin(record, newPin);
            }
            record.biometricEnabled = biometricEnabled && record.hasPin();
            record.notificationPrivacy = normalizeNotificationPrivacy(notificationPrivacy);
            saveRecord(record);
        }
    }

    public void updateGhostMode(int account, boolean enabled, boolean suppressReadReceipts,
                                boolean suppressStoryViews, boolean suppressTyping,
                                boolean minimizeOnline, boolean readOnInteraction,
                                boolean warnBeforeInteraction) {
        synchronized (sync) {
            ContainerRecord record = ensureContainer(account);
            record.ghostModeEnabled = enabled;
            record.ghostSuppressReadReceipts = suppressReadReceipts;
            record.ghostSuppressStoryViews = suppressStoryViews;
            record.ghostSuppressTyping = suppressTyping;
            record.ghostMinimizeOnline = minimizeOnline;
            record.ghostReadOnInteraction = readOnInteraction;
            record.ghostWarnBeforeInteraction = warnBeforeInteraction;
            saveRecord(record);
        }
    }

    public boolean isGhostModeEnabled(int account) {
        return ensureContainer(account).ghostModeEnabled;
    }

    public void setGhostModeEnabled(int account, boolean enabled) {
        synchronized (sync) {
            ContainerRecord record = ensureContainer(account);
            if (record.ghostModeEnabled == enabled) {
                return;
            }
            record.ghostModeEnabled = enabled;
            saveRecord(record);
        }
    }

    public boolean shouldSuppressTyping(int account) {
        ContainerRecord record = ensureContainer(account);
        return record.ghostModeEnabled && record.ghostSuppressTyping;
    }

    public boolean shouldSuppressStoryViews(int account) {
        ContainerRecord record = ensureContainer(account);
        return record.ghostModeEnabled && record.ghostSuppressStoryViews;
    }

    public boolean shouldMinimizeOnline(int account) {
        ContainerRecord record = ensureContainer(account);
        return record.ghostModeEnabled && record.ghostMinimizeOnline;
    }

    public boolean shouldWarnBeforeInteraction(int account) {
        ContainerRecord record = ensureContainer(account);
        return record.ghostModeEnabled && record.ghostWarnBeforeInteraction;
    }

    public boolean isReadOnInteractionEnabled(int account) {
        ContainerRecord record = ensureContainer(account);
        return record.ghostModeEnabled && record.ghostSuppressReadReceipts && record.ghostReadOnInteraction;
    }

    public boolean shouldSuppressReadReceipt(int account) {
        ContainerRecord record = ensureContainer(account);
        return record.ghostModeEnabled && record.ghostSuppressReadReceipts;
    }

    public void deleteContainer(int account) {
        synchronized (sync) {
            String id = preferences.getString(SLOT_PREFIX + account, null);
            if (TextUtils.isEmpty(id)) {
                return;
            }
            // Delete the wrapping key first. Any residual ciphertext becomes
            // irrecoverable before best-effort file cleanup starts.
            AgramSecureStore.deleteKey(id);
            preferences.edit()
                    .remove(SLOT_PREFIX + account)
                    .remove(METADATA_PREFIX + id)
                    .remove(PUSH_INSTANCE_HASH_PREFIX + id)
                    .remove(QUARANTINE_PREFIX + id)
                    .commit();
            recordCache.remove(account);
            Utilities.globalQueue.postRunnable(() -> deleteRecursively(getContainerDirectory(id)));
        }
    }

    public File getContainerDirectory(int account) {
        ContainerRecord record = ensureContainer(account);
        return getContainerDirectory(record.id);
    }

    public SessionProfile resolveSessionProfile(int account, String compatibleDeviceModel,
                                                String compatibleSystemVersion, String appVersion,
                                                String compatibleLanguage, String compatibleSystemLanguage,
                                                int compatibleTimezoneOffset) {
        ContainerRecord record = ensureContainer(account);
        if (record.profileMode == PROFILE_COMPATIBLE) {
            return new SessionProfile(
                    compatibleDeviceModel,
                    compatibleSystemVersion,
                    appVersion,
                    compatibleLanguage,
                    compatibleSystemLanguage,
                    compatibleTimezoneOffset
            );
        }
        if (record.profileMode == PROFILE_CUSTOM) {
            return new SessionProfile(
                    fallbackProfileValue(record.deviceModel, compatibleDeviceModel),
                    fallbackProfileValue(record.systemVersion, compatibleSystemVersion),
                    appVersion,
                    normalizeLanguage(record.clientLanguageCode),
                    normalizeLanguage(record.systemLanguageCode),
                    record.fixedTimezone ? record.timezoneOffset : compatibleTimezoneOffset
            );
        }
        if (record.profileMode == PROFILE_PRESET) {
            ProfilePreset preset = getProfilePreset(record.presetIndex);
            return new SessionProfile(
                    preset.deviceModel,
                    preset.systemVersion,
                    appVersion,
                    normalizeLanguage(record.clientLanguageCode),
                    normalizeLanguage(record.systemLanguageCode),
                    record.fixedTimezone ? record.timezoneOffset : compatibleTimezoneOffset
            );
        }
        String language = normalizeLanguage(record.languageCode);
        return new SessionProfile(
                "Agram Android",
                "Android " + androidMajorVersion(),
                appVersion,
                language,
                language,
                record.fixedTimezone ? record.timezoneOffset : systemTimezoneOffset()
        );
    }

    public static final class SessionProfile {
        public final String deviceModel;
        public final String systemVersion;
        public final String appVersion;
        public final String languageCode;
        public final String systemLanguageCode;
        public final int timezoneOffset;

        private SessionProfile(String deviceModel, String systemVersion, String appVersion,
                               String languageCode, String systemLanguageCode, int timezoneOffset) {
            this.deviceModel = deviceModel;
            this.systemVersion = systemVersion;
            this.appVersion = appVersion;
            this.languageCode = languageCode;
            this.systemLanguageCode = systemLanguageCode;
            this.timezoneOffset = timezoneOffset;
        }
    }

    public static final class ContainerRecord {
        public String id;
        public int account;
        public String name;
        public int color;
        public long createdAt;
        public int profileMode;
        public int presetIndex;
        public String deviceModel;
        public String systemVersion;
        public String appVersion;
        public String profileId;
        public long profileGeneratedAt;
        public boolean profileLocked;
        public String languageCode;
        public String systemLanguageCode;
        public String clientLanguageCode;
        public boolean fixedTimezone;
        public int timezoneOffset;
        public String proxyMode;
        public boolean killSwitch;
        public boolean proxyEnabled;
        public String proxyAddress;
        public int proxyPort;
        public String proxyUsername;
        public String proxyPassword;
        public String proxySecret;
        public String torIsolationId;
        public long torIsolationChangedAt;
        public String pushMode;
        public String agramPushInstance;
        public String agramPushEndpoint;
        public String agramPushStatus;
        public int notificationPrivacy;
        public String pinSalt;
        public String pinHash;
        public boolean biometricEnabled;
        public boolean ghostModeEnabled;
        public boolean ghostSuppressReadReceipts;
        public boolean ghostSuppressStoryViews;
        public boolean ghostSuppressTyping;
        public boolean ghostMinimizeOnline;
        public boolean ghostReadOnInteraction;
        public boolean ghostWarnBeforeInteraction;
        /** Runtime-only recovery state; never written into encrypted metadata. */
        public String storageState = STORAGE_READY;
        public String storageError = "";

        public boolean isStorageAccessible() {
            return STORAGE_READY.equals(storageState);
        }

        public boolean hasPin() {
            return !TextUtils.isEmpty(pinHash) && !TextUtils.isEmpty(pinSalt);
        }
    }

    public void saveProxySettings(int account, boolean enabled, String address, int port,
                                  String username, String password, String secret) {
        synchronized (sync) {
            ContainerRecord record = ensureContainer(account);
            record.proxyEnabled = enabled && !TextUtils.isEmpty(address);
            record.proxyMode = record.proxyEnabled ? NETWORK_PROXY : NETWORK_DIRECT;
            record.proxyAddress = safe(address);
            record.proxyPort = port > 0 && port <= 65535 ? port : 1080;
            record.proxyUsername = safe(username);
            record.proxyPassword = safe(password);
            record.proxySecret = safe(secret);
            saveRecord(record);
        }
    }

    public ProxyProfile getProxyProfile(int account) {
        ContainerRecord record = ensureContainer(account);
        return new ProxyProfile(
                record.proxyEnabled,
                safe(record.proxyMode),
                record.killSwitch,
                safe(record.proxyAddress),
                record.proxyPort > 0 ? record.proxyPort : 1080,
                safe(record.proxyUsername),
                safe(record.proxyPassword),
                safe(record.proxySecret)
        );
    }

    /**
     * Projects the selected container's encrypted proxy record into Telegram's
     * legacy settings UI. The native engines still receive settings per account.
     */
    public void publishProxyForSelectedContainer(int account) {
        ProxyProfile proxy = getProxyProfile(account);
        boolean tor = NETWORK_TOR.equals(proxy.mode);
        int torPort = tor ? AgramTorManager.getInstance().getSocksPort() : 0;
        boolean enabled = proxy.enabled && (!tor || torPort > 0);
        ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("proxy_enabled", enabled)
                .putString("proxy_ip", enabled ? proxy.address : "")
                .putInt("proxy_port", tor && torPort > 0 ? torPort : proxy.port)
                .putString("proxy_user", tor && enabled ? "<torS0X>0" : proxy.username)
                .putString("proxy_pass", tor && enabled
                        ? ensureContainer(account).torIsolationId : proxy.password)
                .putString("proxy_secret", tor ? "" : proxy.secret)
                .commit();
    }

    public static final class ProxyProfile {
        public final boolean enabled;
        public final String mode;
        public final boolean killSwitch;
        public final String address;
        public final int port;
        public final String username;
        public final String password;
        public final String secret;

        private ProxyProfile(boolean enabled, String mode, boolean killSwitch, String address, int port, String username, String password, String secret) {
            this.enabled = enabled;
            this.mode = mode;
            this.killSwitch = killSwitch;
            this.address = address;
            this.port = port;
            this.username = username;
            this.password = password;
            this.secret = secret;
        }
    }

    public void saveNetworkSettings(int account, String mode, boolean killSwitch,
                                    String address, int port, String username,
                                    String password, String secret) {
        synchronized (sync) {
            ContainerRecord record = ensureContainer(account);
            String normalizedMode = normalizeNetworkMode(mode);
            record.proxyMode = normalizedMode;
            // Embedded Tor is always fail-closed. It must never silently fall
            // back to a direct MTProto connection while Tor is bootstrapping.
            record.killSwitch = NETWORK_TOR.equals(normalizedMode)
                    || (killSwitch && !NETWORK_DIRECT.equals(normalizedMode));
            record.proxyEnabled = !NETWORK_DIRECT.equals(normalizedMode);
            record.proxyAddress = NETWORK_TOR.equals(normalizedMode) ? "127.0.0.1" : safe(address).trim();
            // The embedded daemon allocates its listener dynamically. Zero is
            // a persisted marker, never a network fallback port.
            record.proxyPort = NETWORK_TOR.equals(normalizedMode) ? 0 : normalizePort(port);
            record.proxyUsername = NETWORK_TOR.equals(normalizedMode) ? "" : safe(username);
            record.proxyPassword = NETWORK_TOR.equals(normalizedMode) ? "" : safe(password);
            record.proxySecret = NETWORK_TOR.equals(normalizedMode) ? "" : safe(secret);
            saveRecord(record);
        }
    }

    /** Rotates only this container's SOCKS-auth isolation group. */
    public String rotateTorIsolation(int account) {
        synchronized (sync) {
            ContainerRecord record = ensureContainer(account);
            record.torIsolationId = newTorIsolationId();
            record.torIsolationChangedAt = System.currentTimeMillis();
            saveRecord(record);
            return record.torIsolationId;
        }
    }

    public void savePushSettings(int account, String pushMode) {
        synchronized (sync) {
            ContainerRecord record = ensureContainer(account);
            record.pushMode = PUSH_AGRAM.equals(pushMode) ? PUSH_AGRAM : PUSH_DIRECT;
            if (!PUSH_AGRAM.equals(record.pushMode)) {
                record.agramPushEndpoint = "";
                record.agramPushStatus = "direct";
            }
            saveRecord(record);
        }
    }

    public void saveAgramPushEndpoint(int account, String endpoint, String status) {
        synchronized (sync) {
            ContainerRecord record = ensureContainer(account);
            record.agramPushEndpoint = safe(endpoint);
            record.agramPushStatus = safe(status);
            saveRecord(record);
        }
    }

    public int findAccountByAgramPushInstance(String instance) {
        if (TextUtils.isEmpty(instance)) {
            return -1;
        }
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            ContainerRecord record = getContainer(account);
            if (record != null && instance.equals(record.agramPushInstance)) {
                return account;
            }
        }
        return -1;
    }

    public static int getProfilePresetCount() {
        return PROFILE_PRESETS.length;
    }

    public static ProfilePreset getProfilePreset(int index) {
        return PROFILE_PRESETS[normalizePresetIndex(index)];
    }

    public static final class ProfilePreset {
        public final String title;
        public final String deviceModel;
        public final String systemVersion;

        private ProfilePreset(String title, String deviceModel, String systemVersion) {
            this.title = title;
            this.deviceModel = deviceModel;
            this.systemVersion = systemVersion;
        }
    }

    private ContainerRecord createDefault(int account) {
        ContainerRecord record = new ContainerRecord();
        record.id = UUID.randomUUID().toString();
        record.account = account;
        record.name = defaultName(account);
        record.color = defaultColor(account);
        record.createdAt = System.currentTimeMillis();
        record.profileMode = PROFILE_PRESET;
        record.presetIndex = secureRandom.nextInt(PROFILE_PRESETS.length);
        record.deviceModel = "";
        record.systemVersion = "";
        record.appVersion = "";
        record.profileId = UUID.randomUUID().toString();
        record.profileGeneratedAt = System.currentTimeMillis();
        record.profileLocked = UserConfig.getInstance(account).isClientActivated();
        record.languageCode = normalizeLanguage(Locale.getDefault().getLanguage());
        record.systemLanguageCode = normalizeLanguage(Locale.getDefault().toLanguageTag());
        record.clientLanguageCode = normalizeLanguage(LocaleController.getInstance().getCurrentLocaleInfo() != null
                ? LocaleController.getInstance().getCurrentLocaleInfo().shortName : Locale.getDefault().toLanguageTag());
        record.timezoneOffset = systemTimezoneOffset();
        // A new account must never inherit another container's legacy proxy.
        record.proxyAddress = "";
        record.proxyPort = 1080;
        record.proxyUsername = "";
        record.proxyPassword = "";
        record.proxySecret = "";
        record.torIsolationId = newTorIsolationId();
        record.torIsolationChangedAt = record.createdAt;
        record.proxyEnabled = false;
        record.proxyMode = NETWORK_DIRECT;
        record.killSwitch = false;
        record.pushMode = PUSH_AGRAM;
        record.agramPushInstance = "agram-" + UUID.randomUUID();
        record.agramPushEndpoint = "";
        record.agramPushStatus = "not_registered";
        record.notificationPrivacy = NOTIFICATION_HIDDEN;
        record.ghostModeEnabled = false;
        record.ghostSuppressReadReceipts = true;
        record.ghostSuppressStoryViews = true;
        record.ghostSuppressTyping = true;
        record.ghostMinimizeOnline = true;
        record.ghostReadOnInteraction = true;
        record.ghostWarnBeforeInteraction = true;
        File directory = getContainerDirectory(record.id);
        if (!directory.exists() && !directory.mkdirs()) {
            FileLog.e("Unable to create Agram container directory " + directory);
        }
        return record;
    }

    private void saveRecord(ContainerRecord record) {
        if (!record.isStorageAccessible()) {
            FileLog.e("Refusing to overwrite unavailable Agram container " + record.account
                    + " (" + record.storageState + ":" + record.storageError + ")");
            return;
        }
        try {
            byte[] clear = toJson(record).toString().getBytes(StandardCharsets.UTF_8);
            byte[] encrypted = AgramSecureStore.encrypt(record.id, clear, AgramSecureStore.aad(record.id, "metadata"));
            preferences.edit()
                    .putString(SLOT_PREFIX + record.account, record.id)
                    .putString(METADATA_PREFIX + record.id, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .putString(PUSH_INSTANCE_HASH_PREFIX + record.id, pushInstanceHash(record.agramPushInstance))
                    .remove(QUARANTINE_PREFIX + record.id)
                    .commit();
            recordCache.put(record.account, record);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to persist Agram container", e);
        }
    }

    private ContainerRecord readRecord(int account, String id) {
        try {
            String encoded = preferences.getString(METADATA_PREFIX + id, null);
            if (TextUtils.isEmpty(encoded)) {
                return quarantineRecord(account, id, STORAGE_QUARANTINED, "metadata_missing", null);
            }
            byte[] encrypted = Base64.decode(encoded, Base64.NO_WRAP);
            byte[] clear = AgramSecureStore.decrypt(id, encrypted, AgramSecureStore.aad(id, "metadata"));
            JSONObject json = new JSONObject(new String(clear, StandardCharsets.UTF_8));
            int storedSchema = json.optInt("schema", 0);
            ContainerRecord record = fromJson(json);
            if (record.account != account || !id.equals(record.id)) {
                throw new GeneralSecurityException("Container registry mismatch");
            }
            // This non-sensitive digest lets us enforce per-container push
            // identities without decrypting every other account while the
            // registry lock is held. Older records are indexed lazily.
            String hashKey = PUSH_INSTANCE_HASH_PREFIX + id;
            String expectedHash = pushInstanceHash(record.agramPushInstance);
            if (!expectedHash.equals(preferences.getString(hashKey, ""))) {
                preferences.edit().putString(hashKey, expectedHash).apply();
            }
            if (storedSchema < SCHEMA_VERSION || json.has("decoy_codes")) {
                // Version 2 removes legacy false-code hashes from encrypted
                // metadata instead of only hiding their settings UI.
                saveRecord(record);
            }
            record.storageState = STORAGE_READY;
            record.storageError = "";
            preferences.edit().remove(QUARANTINE_PREFIX + id).apply();
            return record;
        } catch (Exception e) {
            boolean keyUnavailable = e instanceof AgramSecureStore.KeyUnavailableException;
            return quarantineRecord(account, id,
                    keyUnavailable ? STORAGE_LOCKED : STORAGE_QUARANTINED,
                    containerReadError(e), e);
        }
    }

    private ContainerRecord quarantineRecord(int account, String id, String state,
                                             String error, Exception cause) {
        // Keep SLOT_PREFIX and METADATA_PREFIX untouched. The placeholder is
        // deliberately fail-closed and can be retried if Keystore availability
        // was only transient.
        ContainerRecord record = new ContainerRecord();
        record.id = id;
        record.account = account;
        record.name = defaultName(account) + " · locked";
        record.color = defaultColor(account);
        record.profileMode = PROFILE_MINIMAL;
        record.profileLocked = true;
        record.languageCode = normalizeLanguage(Locale.getDefault().getLanguage());
        record.systemLanguageCode = normalizeLanguage(Locale.getDefault().toLanguageTag());
        record.clientLanguageCode = record.languageCode;
        record.timezoneOffset = systemTimezoneOffset();
        record.proxyMode = NETWORK_PROXY;
        record.proxyEnabled = true;
        record.killSwitch = true;
        record.proxyAddress = "127.0.0.1";
        record.proxyPort = 1;
        record.proxyUsername = "";
        record.proxyPassword = "";
        record.proxySecret = "";
        record.torIsolationId = "";
        record.pushMode = PUSH_DIRECT;
        record.agramPushInstance = "";
        record.agramPushEndpoint = "";
        record.agramPushStatus = "container_locked";
        record.notificationPrivacy = NOTIFICATION_HIDDEN;
        record.ghostSuppressReadReceipts = true;
        record.ghostSuppressStoryViews = true;
        record.ghostSuppressTyping = true;
        record.ghostMinimizeOnline = true;
        record.ghostReadOnInteraction = true;
        record.ghostWarnBeforeInteraction = true;
        record.storageState = state;
        record.storageError = error;
        preferences.edit().putString(QUARANTINE_PREFIX + id, state + ":" + error).commit();
        if (cause != null) {
            FileLog.e("Agram container " + account + " is " + state + " (" + error + ")", cause);
        } else {
            FileLog.e("Agram container " + account + " is " + state + " (" + error + ")");
        }
        return record;
    }

    private static String containerReadError(Exception error) {
        if (error instanceof AgramSecureStore.KeyUnavailableException) {
            return "keystore_key_unavailable";
        }
        if (error instanceof GeneralSecurityException) {
            return "metadata_authentication_failed";
        }
        if (error instanceof JSONException) {
            return "metadata_invalid";
        }
        if (error instanceof IllegalArgumentException) {
            return "metadata_encoding_invalid";
        }
        return "metadata_unreadable";
    }

    private JSONObject toJson(ContainerRecord record) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("schema", SCHEMA_VERSION);
        json.put("id", record.id);
        json.put("account", record.account);
        json.put("name", record.name);
        json.put("color", record.color);
        json.put("created_at", record.createdAt);
        json.put("profile_mode", record.profileMode);
        json.put("preset_index", record.presetIndex);
        json.put("device_model", safe(record.deviceModel));
        json.put("system_version", safe(record.systemVersion));
        json.put("app_version", safe(record.appVersion));
        json.put("profile_id", record.profileId);
        json.put("profile_generated_at", record.profileGeneratedAt);
        json.put("profile_locked", record.profileLocked);
        json.put("language", record.languageCode);
        json.put("system_language", record.systemLanguageCode);
        json.put("client_language", record.clientLanguageCode);
        json.put("fixed_timezone", record.fixedTimezone);
        json.put("timezone_offset", record.timezoneOffset);
        json.put("proxy_mode", record.proxyMode);
        json.put("kill_switch", record.killSwitch);
        json.put("proxy_enabled", record.proxyEnabled);
        json.put("proxy_address", record.proxyAddress);
        json.put("proxy_port", record.proxyPort);
        json.put("proxy_username", record.proxyUsername);
        json.put("proxy_password", record.proxyPassword);
        json.put("proxy_secret", record.proxySecret);
        json.put("tor_isolation_id", record.torIsolationId);
        json.put("tor_isolation_changed_at", record.torIsolationChangedAt);
        json.put("push_mode", record.pushMode);
        json.put("agram_push_instance", record.agramPushInstance);
        json.put("agram_push_endpoint", record.agramPushEndpoint);
        json.put("agram_push_status", record.agramPushStatus);
        json.put("notification_privacy", record.notificationPrivacy);
        json.put("pin_salt", record.pinSalt);
        json.put("pin_hash", record.pinHash);
        json.put("biometric", record.biometricEnabled);
        json.put("ghost_enabled", record.ghostModeEnabled);
        json.put("ghost_read", record.ghostSuppressReadReceipts);
        json.put("ghost_stories", record.ghostSuppressStoryViews);
        json.put("ghost_typing", record.ghostSuppressTyping);
        json.put("ghost_online", record.ghostMinimizeOnline);
        json.put("ghost_read_on_interaction", record.ghostReadOnInteraction);
        json.put("ghost_warn_interaction", record.ghostWarnBeforeInteraction);
        return json;
    }

    private ContainerRecord fromJson(JSONObject json) throws JSONException {
        int schema = json.optInt("schema", 0);
        if (schema < 1 || schema > SCHEMA_VERSION) {
            throw new JSONException("Unsupported Agram container schema");
        }
        ContainerRecord record = new ContainerRecord();
        record.id = json.getString("id");
        record.account = json.getInt("account");
        record.name = json.optString("name", defaultName(record.account));
        record.color = json.optInt("color", defaultColor(record.account));
        record.createdAt = json.optLong("created_at", 0);
        record.profileMode = normalizeProfileMode(json.optInt("profile_mode", PROFILE_MINIMAL));
        record.presetIndex = normalizePresetIndex(json.optInt("preset_index", 0));
        record.deviceModel = normalizeProfileValue(json.optString("device_model", ""), 64);
        record.systemVersion = normalizeProfileValue(json.optString("system_version", ""), 64);
        record.appVersion = normalizeProfileValue(json.optString("app_version", ""), 64);
        record.profileId = json.optString("profile_id", UUID.randomUUID().toString());
        record.profileGeneratedAt = json.optLong("profile_generated_at", record.createdAt);
        record.profileLocked = json.optBoolean("profile_locked", false);
        record.languageCode = normalizeLanguage(json.optString("language", "en"));
        record.systemLanguageCode = normalizeLanguage(json.optString("system_language", record.languageCode));
        record.clientLanguageCode = normalizeLanguage(json.optString("client_language", record.languageCode));
        record.fixedTimezone = json.optBoolean("fixed_timezone", false);
        record.timezoneOffset = json.optInt("timezone_offset", systemTimezoneOffset());
        record.proxyMode = json.optString("proxy_mode", "direct");
        record.killSwitch = json.optBoolean("kill_switch", false);
        record.proxyEnabled = json.optBoolean("proxy_enabled", false);
        record.proxyAddress = json.optString("proxy_address", "");
        record.proxyPort = json.optInt("proxy_port", 1080);
        record.proxyUsername = json.optString("proxy_username", "");
        record.proxyPassword = json.optString("proxy_password", "");
        record.proxySecret = json.optString("proxy_secret", "");
        record.torIsolationId = json.optString("tor_isolation_id", "");
        if (TextUtils.isEmpty(record.torIsolationId)) {
            record.torIsolationId = newTorIsolationId();
        }
        record.torIsolationChangedAt = json.optLong("tor_isolation_changed_at", record.createdAt);
        String storedPushMode = json.optString("push_mode", PUSH_AGRAM);
        record.pushMode = PUSH_DIRECT.equals(storedPushMode) ? PUSH_DIRECT : PUSH_AGRAM;
        record.agramPushInstance = json.optString("agram_push_instance",
                json.optString("unified_push_instance", "agram-" + UUID.randomUUID()));
        // The legacy endpoint is read once so the controller can unregister it
        // from Telegram before replacing it with an embedded Agram endpoint.
        record.agramPushEndpoint = json.optString("agram_push_endpoint",
                json.optString("unified_push_endpoint", ""));
        record.agramPushStatus = LEGACY_PUSH_UNIFIED.equals(storedPushMode)
                ? "migration_required"
                : json.optString("agram_push_status",
                        json.optString("unified_push_status", "not_registered"));
        record.notificationPrivacy = normalizeNotificationPrivacy(json.optInt("notification_privacy", NOTIFICATION_HIDDEN));
        record.pinSalt = nullable(json, "pin_salt");
        record.pinHash = nullable(json, "pin_hash");
        record.biometricEnabled = json.optBoolean("biometric", false);
        record.ghostModeEnabled = json.optBoolean("ghost_enabled", false);
        record.ghostSuppressReadReceipts = json.optBoolean("ghost_read", true);
        record.ghostSuppressStoryViews = json.optBoolean("ghost_stories", true);
        record.ghostSuppressTyping = json.optBoolean("ghost_typing", true);
        record.ghostMinimizeOnline = json.optBoolean("ghost_online", true);
        record.ghostReadOnInteraction = json.optBoolean("ghost_read_on_interaction", true);
        record.ghostWarnBeforeInteraction = json.optBoolean("ghost_warn_interaction", true);
        return record;
    }

    private void ensureUniquePushInstanceLocked(ContainerRecord record) {
        String instance = safe(record.agramPushInstance).trim();
        if (!TextUtils.isEmpty(instance) && !isPushInstanceInUseLocked(instance, record.account)) {
            return;
        }
        do {
            instance = "agram-" + UUID.randomUUID();
        } while (isPushInstanceInUseLocked(instance, record.account));
        record.agramPushInstance = instance;
        // Do not retain an endpoint that was registered under a duplicated
        // legacy instance. The embedded controller will register a fresh one.
        record.agramPushEndpoint = "";
        record.agramPushStatus = "not_registered";
        if (!TextUtils.isEmpty(record.id)) {
            saveRecord(record);
        }
    }

    private boolean isPushInstanceInUseLocked(String instance, int exceptAccount) {
        String candidateHash = pushInstanceHash(instance);
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            if (account == exceptAccount) {
                continue;
            }
            ContainerRecord cached = recordCache.get(account);
            if (cached != null) {
                if (instance.equals(cached.agramPushInstance)) {
                    return true;
                }
                continue;
            }
            String id = preferences.getString(SLOT_PREFIX + account, null);
            if (!TextUtils.isEmpty(id) && candidateHash.equals(
                    preferences.getString(PUSH_INSTANCE_HASH_PREFIX + id, ""))) {
                return true;
            }
        }
        return false;
    }

    private static String pushInstanceHash(String instance) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(safe(instance).trim().getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format(Locale.US, "%02x", value & 0xff));
            }
            return result.toString();
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private void setPin(ContainerRecord record, String pin) {
        if (pin.length() < 6) {
            throw new IllegalArgumentException("Container PIN must contain at least 6 characters");
        }
        try {
            byte[] salt = new byte[16];
            secureRandom.nextBytes(salt);
            record.pinSalt = Base64.encodeToString(salt, Base64.NO_WRAP);
            record.pinHash = Base64.encodeToString(derivePin(pin, salt), Base64.NO_WRAP);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to protect container PIN", e);
        }
    }

    private void purgeLegacyFalseCodes() {
        if (preferences.getBoolean(LEGACY_CODES_PURGED, false)) {
            return;
        }
        ApplicationLoader.applicationContext
                .getSharedPreferences(LEGACY_DURESS_PREFS, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
        // KeyStore deletion may block on some Android builds. The registry is
        // already unreachable, so finish the cryptographic cleanup off-main.
        Utilities.globalQueue.postRunnable(() -> AgramSecureStore.deleteKey(LEGACY_DURESS_SCOPE));
        // Container metadata migrates lazily when that account is opened.
        // Decrypting every record here stalls cold start on Android Keystore.
        preferences.edit().putBoolean(LEGACY_CODES_PURGED, true).commit();
    }

    private static byte[] derivePin(String pin, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, PIN_ITERATIONS, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    private static String nullable(JSONObject json, String key) {
        return json.isNull(key) ? null : json.optString(key, null);
    }

    private static String defaultName(int account) {
        return "Container " + (account + 1);
    }

    private static int defaultColor(int account) {
        int[] colors = {0xff2f5bea, 0xff4caf50, 0xff9c27b0, 0xffff8f00, 0xff00897b, 0xffd84315};
        return colors[Math.abs(account) % colors.length];
    }

    private static int systemTimezoneOffset() {
        TimeZone zone = TimeZone.getDefault();
        return zone.getOffset(System.currentTimeMillis()) / 1000;
    }

    private static String normalizeLanguage(String value) {
        if (TextUtils.isEmpty(value)) {
            return "en";
        }
        String normalized = value.trim().toLowerCase(Locale.US).replace('_', '-');
        return normalized.matches("[a-z]{2,3}(-[a-z0-9]{2,8})*") ? normalized : "en";
    }

    private static int normalizeNotificationPrivacy(int value) {
        if (value == NOTIFICATION_AUTHOR || value == NOTIFICATION_FULL) {
            return value;
        }
        return NOTIFICATION_HIDDEN;
    }

    private static int normalizeProfileMode(int value) {
        if (value == PROFILE_COMPATIBLE || value == PROFILE_CUSTOM || value == PROFILE_PRESET) {
            return value;
        }
        return PROFILE_MINIMAL;
    }

    private static int normalizePresetIndex(int value) {
        return value >= 0 && value < PROFILE_PRESETS.length ? value : 0;
    }

    private static String normalizeNetworkMode(String value) {
        if (NETWORK_PROXY.equals(value) || NETWORK_TOR.equals(value)) {
            return value;
        }
        return NETWORK_DIRECT;
    }

    private static int normalizePort(int value) {
        return value > 0 && value <= 65535 ? value : 1080;
    }

    private static boolean isUuid(String value) {
        if (TextUtils.isEmpty(value)) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException ignore) {
            return false;
        }
    }

    private static String normalizeProfileValue(String value, int maxLength) {
        if (TextUtils.isEmpty(value)) {
            return "";
        }
        StringBuilder normalized = new StringBuilder(Math.min(value.length(), maxLength));
        boolean previousWhitespace = false;
        for (int i = 0; i < value.length() && normalized.length() < maxLength; i++) {
            char character = value.charAt(i);
            if (Character.isISOControl(character)) {
                continue;
            }
            if (Character.isWhitespace(character)) {
                if (normalized.length() == 0 || previousWhitespace) {
                    continue;
                }
                normalized.append(' ');
                previousWhitespace = true;
            } else {
                normalized.append(character);
                previousWhitespace = false;
            }
        }
        return normalized.toString().trim();
    }

    private static String fallbackProfileValue(String value, String fallback) {
        String normalized = normalizeProfileValue(value, 64);
        return TextUtils.isEmpty(normalized) ? fallback : normalized;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String newTorIsolationId() {
        // Tor's IsolateSOCKSAuth groups streams by SOCKS username/password.
        // This opaque value is stored only inside the encrypted container record.
        return "agram-" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String androidMajorVersion() {
        String release = Build.VERSION.RELEASE;
        if (TextUtils.isEmpty(release)) {
            return Integer.toString(Build.VERSION.SDK_INT);
        }
        int dot = release.indexOf('.');
        return dot > 0 ? release.substring(0, dot) : release;
    }

    private static File getContainerDirectory(String id) {
        return new File(ApplicationLoader.applicationContext.getFilesDir(), "agram_containers/" + id);
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        if (!file.delete()) {
            FileLog.e("Unable to delete Agram container file " + file);
        }
    }
}
