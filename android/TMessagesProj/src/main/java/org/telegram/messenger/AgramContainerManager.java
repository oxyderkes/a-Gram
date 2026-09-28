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
import java.io.IOException;
import java.io.FileDescriptor;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    private static final String LEGACY_NETWORK_TOR = "tor";
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
    private static final String DELETION_INTENT_PREFIX = "deletion_intent_";
    private static final String NATIVE_RETIREMENT_PREFIX = "native_retirement_";
    private static final String NATIVE_RETIRED_PREFIX = "native_retired_";
    private static final int SCHEMA_VERSION = 7;
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
        sweepContainerTombstones();
    }

    public ContainerRecord ensureContainer(int account) {
        synchronized (sync) {
            ContainerRecord cached = recordCache.get(account);
            if (cached != null) {
                return copyRecord(cached);
            }
            String id = preferences.getString(SLOT_PREFIX + account, null);
            if (!TextUtils.isEmpty(id)) {
                ContainerRecord record = readRecord(account, id);
                if (record.isStorageAccessible()) {
                    ensureUniquePushInstanceLocked(record);
                }
                recordCache.put(account, copyRecord(record));
                return copyRecord(record);
            }
            ContainerRecord record = createDefault(account);
            ensureUniquePushInstanceLocked(record);
            saveRecord(record);
            return copyRecord(record);
        }
    }

    public ContainerRecord getContainer(int account) {
        synchronized (sync) {
            ContainerRecord cached = recordCache.get(account);
            if (cached != null) {
                return copyRecord(cached);
            }
            String id = preferences.getString(SLOT_PREFIX + account, null);
            ContainerRecord record = TextUtils.isEmpty(id) ? null : readRecord(account, id);
            if (record != null) {
                if (record.isStorageAccessible()) {
                    ensureUniquePushInstanceLocked(record);
                }
                recordCache.put(account, copyRecord(record));
            }
            return copyRecord(record);
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

    public boolean isCurrentContainer(int account, String expectedId) {
        synchronized (sync) {
            return !TextUtils.isEmpty(expectedId)
                    && TextUtils.equals(expectedId, preferences.getString(SLOT_PREFIX + account, null))
                    && !preferences.getBoolean(DELETION_INTENT_PREFIX + expectedId, false);
        }
    }

    public boolean canUseForNewLogin(int account) {
        synchronized (sync) {
            ContainerRecord record = getContainer(account);
            return record == null || (record.isStorageAccessible() && !record.profileLocked
                    && !preferences.getBoolean(DELETION_INTENT_PREFIX + record.id, false)
                    && !preferences.getBoolean(NATIVE_RETIREMENT_PREFIX + record.id, false));
        }
    }

    /** Explicit durable logout intent, never inferred from an inactive runtime slot. */
    public boolean prepareNativeRetirement(int account, String expectedId) {
        synchronized (sync) {
            ContainerRecord record = getContainer(account);
            return record != null && record.isStorageAccessible()
                    && TextUtils.equals(record.id, expectedId)
                    && AgramPreferenceTransaction.commit(preferences, preferences.edit()
                    .putBoolean(NATIVE_RETIREMENT_PREFIX + expectedId, true),
                    NATIVE_RETIREMENT_PREFIX + expectedId);
        }
    }

    public boolean hasPendingNativeRetirement(int account, String expectedId) {
        synchronized (sync) {
            return !TextUtils.isEmpty(expectedId)
                    && TextUtils.equals(expectedId, preferences.getString(SLOT_PREFIX + account, null))
                    && preferences.getBoolean(NATIVE_RETIREMENT_PREFIX + expectedId, false);
        }
    }

    /** Call only after native confirms its durable old-UUID -> empty transition. */
    public boolean acknowledgeNativeRetirement(int account, String expectedId) {
        synchronized (sync) {
            return hasPendingNativeRetirement(account, expectedId) && !hasPersistedUser(account)
                    && AgramPreferenceTransaction.commit(preferences, preferences.edit()
                    .putBoolean(NATIVE_RETIRED_PREFIX + expectedId, true),
                    NATIVE_RETIRED_PREFIX + expectedId);
        }
    }

    private static boolean hasPersistedUser(int account) {
        // No UserConfig construction during the registry's startup sweep.
        return ApplicationLoader.applicationContext.getSharedPreferences(
                account == 0 ? "userconfing" : "userconfig" + account, Context.MODE_PRIVATE).contains("user");
    }

    private boolean canDeleteRetiredContainer(String id) {
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            if (TextUtils.equals(id, preferences.getString(SLOT_PREFIX + account, null))) {
                return !hasPersistedUser(account)
                        && preferences.getBoolean(NATIVE_RETIRED_PREFIX + id, false);
            }
        }
        return true; // An already-unmapped tombstone cannot own a live slot.
    }

    /**
     * Runs a bounded group of settings writes only while the slot still owns
     * the container that the UI originally displayed. Nested manager mutators
     * are safe because Java monitors are re-entrant. Individual mutators remain
     * separately durable, so callers must report a possible partial update if
     * a later write fails.
     */
    public void runBoundSettingsUpdate(int account, String expectedContainerId, Runnable changes) {
        if (changes == null) {
            throw new IllegalArgumentException("Missing Agram container settings update");
        }
        synchronized (sync) {
            ContainerRecord current = getContainer(account);
            if (current == null || !current.isStorageAccessible()
                    || !TextUtils.equals(expectedContainerId, current.id)
                    || preferences.getBoolean(DELETION_INTENT_PREFIX + current.id, false)) {
                throw new ContainerIdentityException("Agram container changed while saving settings");
            }
            changes.run();
        }
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
        markAuthorized(account, ensureContainer(account).id);
    }

    public void markAuthorized(int account, String expectedContainerId) {
        synchronized (sync) {
            if (!isCurrentContainer(account, expectedContainerId)) {
                throw new ContainerIdentityException("Authorization belongs to an old container");
            }
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
        if (record == null || !record.isStorageAccessible()) {
            return false;
        }
        if (TextUtils.isEmpty(record.pinSalt) && TextUtils.isEmpty(record.pinHash)) {
            return true;
        }
        if (TextUtils.isEmpty(record.pinSalt) || TextUtils.isEmpty(record.pinHash)) {
            return false;
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

    public interface PinVerificationCallback {
        void onComplete(boolean verified);
    }

    /** PBKDF2 is intentionally kept off the UI thread. */
    public void verifyPinAsync(int account, String pin, PinVerificationCallback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("Missing Agram PIN verification callback");
        }
        final ContainerRecord record = getContainer(account);
        final String candidate = pin == null ? "" : pin;
        Utilities.globalQueue.postRunnable(() -> {
            boolean verified = verifyPinSnapshot(record, candidate);
            AndroidUtilities.runOnUIThread(() -> {
                ContainerRecord current;
                try {
                    current = getContainer(account);
                } catch (ContainerPersistenceException e) {
                    FileLog.e("Unable to revalidate Agram container after PIN verification", e);
                    current = null;
                }
                boolean sameContainer = record != null && current != null
                        && TextUtils.equals(record.id, current.id)
                        && TextUtils.equals(record.pinSalt, current.pinSalt)
                        && TextUtils.equals(record.pinHash, current.pinHash)
                        && current.isStorageAccessible();
                callback.onComplete(verified && sameContainer);
            });
        });
    }

    private static boolean verifyPinSnapshot(ContainerRecord record, String pin) {
        if (record == null || !record.isStorageAccessible()) {
            return false;
        }
        if (TextUtils.isEmpty(record.pinSalt) && TextUtils.isEmpty(record.pinHash)) {
            return true;
        }
        if (TextUtils.isEmpty(record.pinSalt) || TextUtils.isEmpty(record.pinHash)) {
            return false;
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

    public boolean isKeepDeletedMessagesEnabled(int account) {
        try {
            ContainerRecord record = getContainer(account);
            // Unreadable metadata must not turn a preservation setting off and purge rows.
            return record == null || !record.isStorageAccessible() || record.keepDeletedMessages;
        } catch (RuntimeException unavailable) {
            return true;
        }
    }

    /** Called from the UUID/session-bound UserConfig setter. */
    void updateKeepDeletedMessages(int account, boolean enabled) {
        synchronized (sync) {
            ContainerRecord record = getContainer(account);
            if (record == null || !record.isStorageAccessible()) {
                throw new ContainerIdentityException("Agram container is unavailable");
            }
            if (record.keepDeletedMessages == enabled) {
                return;
            }
            record.keepDeletedMessages = enabled;
            saveRecord(record);
        }
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
        String expectedId;
        synchronized (sync) {
            expectedId = preferences.getString(SLOT_PREFIX + account, null);
        }
        deleteContainer(account, expectedId);
    }

    public boolean deleteContainer(int account, String expectedId) {
        final String id;
        synchronized (sync) {
            id = preferences.getString(SLOT_PREFIX + account, null);
            if (TextUtils.isEmpty(id)) {
                return TextUtils.isEmpty(expectedId);
            }
            if (!TextUtils.equals(expectedId, id)) {
                return false;
            }
            if (!canDeleteRetiredContainer(id)) {
                notifyPersistenceFailure(account, "native_retirement_pending");
                return false;
            }
            File containerDirectory = getValidatedContainerChild(id, false);
            File tombstone = getValidatedContainerChild(".deleting-" + id, true);
            if (containerDirectory == null || tombstone == null) {
                FileLog.e("Refusing unsafe Agram container deletion for id=" + id);
                notifyPersistenceFailure(account, "container_delete");
                return false;
            }
            // Persist the exact validated UUID before touching the directory. This is the
            // durable recovery point if the process dies after rename, or if directory fsync
            // is unavailable on a particular Android filesystem.
            if (!AgramPreferenceTransaction.commit(preferences,
                    preferences.edit().putBoolean(DELETION_INTENT_PREFIX + id, true), DELETION_INTENT_PREFIX + id)) {
                FileLog.e("Unable to persist Agram container deletion intent for " + id);
                notifyPersistenceFailure(account, "container_delete");
                return false;
            }
        }

        // Do not hold the manager lock while waiting for archive I/O: an in-flight worker may
        // already be validating the current container through this manager. Invalidation is
        // installed first inside purgeContainer, so after the barrier no worker can recreate it.
        AgramDeletedMediaStore.purgeContainer(account, id);

        synchronized (sync) {
            if (!TextUtils.equals(id, preferences.getString(SLOT_PREFIX + account, null))) {
                notifyPersistenceFailure(account, "container_delete_identity");
                return false;
            }
            File containerDirectory = getValidatedContainerChild(id, false);
            File tombstone = getValidatedContainerChild(".deleting-" + id, true);
            if (containerDirectory == null || tombstone == null) {
                FileLog.e("Refusing unsafe Agram container deletion for id=" + id);
                notifyPersistenceFailure(account, "container_delete");
                return false;
            }
            if (containerDirectory.exists()) {
                if (tombstone.exists()) {
                    FileLog.e("Refusing Agram container deletion because tombstone exists " + tombstone);
                    scheduleContainerDeletionRetry(id);
                    notifyPersistenceFailure(account, "container_delete");
                    return false;
                }
                try {
                    moveAtomically(containerDirectory, tombstone);
                    syncDirectory(tombstone.getParentFile());
                } catch (Throwable e) {
                    FileLog.e("Unable to tombstone Agram container " + containerDirectory, e);
                    scheduleContainerDeletionRetry(id);
                    notifyPersistenceFailure(account, "container_delete");
                    return false;
                }
            }
            // Keep the wrapping key until the registry no longer maps this UUID.
            // Otherwise a failed preferences commit would turn a retryable cleanup
            // into an unrecoverable, still-mapped container.
            SharedPreferences.Editor deletion = preferences.edit()
                    .remove(SLOT_PREFIX + account)
                    .remove(METADATA_PREFIX + id)
                    .remove(PUSH_INSTANCE_HASH_PREFIX + id)
                    .remove(QUARANTINE_PREFIX + id);
            boolean committed = AgramPreferenceTransaction.commit(preferences, deletion,
                    SLOT_PREFIX + account, METADATA_PREFIX + id,
                    PUSH_INSTANCE_HASH_PREFIX + id, QUARANTINE_PREFIX + id);
            if (!committed) {
                FileLog.e("Unable to finalize Agram container registry deletion for " + id);
                scheduleContainerDeletionRetry(id);
                notifyPersistenceFailure(account, "container_delete");
                return false;
            }
            recordCache.remove(account);
            AgramSecureStore.deleteKey(id);
            if (tombstone.exists()) {
                Utilities.globalQueue.postRunnable(() -> deleteTombstone(tombstone));
            } else {
                clearDeletionIntent(id);
            }
            return true;
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
        public String pushMode;
        public String agramPushInstance;
        public String agramPushEndpoint;
        public String agramPushStatus;
        public int notificationPrivacy;
        public String pinSalt;
        public String pinHash;
        public boolean biometricEnabled;
        public boolean keepDeletedMessages;
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

    /** The requested state was not durably written and must not be presented as saved. */
    public static class ContainerPersistenceException extends IllegalStateException {
        public ContainerPersistenceException(String message) {
            super(message);
        }

        public ContainerPersistenceException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final class ContainerIdentityException extends ContainerPersistenceException {
        public ContainerIdentityException(String message) {
            super(message);
        }
    }

    public void saveProxySettings(int account, boolean enabled, String address, int port,
                                  String username, String password, String secret) {
        synchronized (sync) {
            ContainerRecord record = ensureContainer(account);
            record.proxyEnabled = enabled && !TextUtils.isEmpty(address);
            record.proxyMode = record.proxyEnabled ? NETWORK_PROXY : NETWORK_DIRECT;
            record.killSwitch = record.proxyEnabled && record.killSwitch;
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
        boolean enabled = proxy.enabled
                && NETWORK_PROXY.equals(proxy.mode)
                && !TextUtils.isEmpty(proxy.address);
        boolean committed = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("proxy_enabled", enabled)
                .putString("proxy_ip", proxy.address)
                .putInt("proxy_port", proxy.port)
                .putString("proxy_user", proxy.username)
                .putString("proxy_pass", proxy.password)
                .putString("proxy_secret", proxy.secret)
                .commit();
        if (!committed) {
            throw new ContainerPersistenceException("Unable to publish selected container proxy settings");
        }
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
            record.killSwitch = killSwitch && NETWORK_PROXY.equals(normalizedMode);
            record.proxyEnabled = NETWORK_PROXY.equals(normalizedMode);
            record.proxyAddress = safe(address).trim();
            record.proxyPort = normalizePort(port);
            record.proxyUsername = safe(username);
            record.proxyPassword = safe(password);
            record.proxySecret = safe(secret);
            saveRecord(record);
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
        record.proxyEnabled = false;
        record.proxyMode = NETWORK_DIRECT;
        record.killSwitch = false;
        record.pushMode = PUSH_AGRAM;
        record.agramPushInstance = "agram-" + UUID.randomUUID();
        record.agramPushEndpoint = "";
        record.agramPushStatus = "not_registered";
        record.notificationPrivacy = NOTIFICATION_HIDDEN;
        record.keepDeletedMessages = true;
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
        String mappedId = preferences.getString(SLOT_PREFIX + record.account, null);
        if (!TextUtils.isEmpty(mappedId) && !TextUtils.equals(mappedId, record.id)) {
            throw new ContainerIdentityException("Refusing to replace another container in this slot");
        }
        if (preferences.getBoolean(DELETION_INTENT_PREFIX + record.id, false)) {
            throw new ContainerIdentityException(
                    "Refusing to update an Agram container pending deletion " + record.account);
        }
        if (!record.isStorageAccessible()) {
            throw new ContainerPersistenceException(
                    "Refusing to overwrite unavailable Agram container " + record.account
                            + " (" + record.storageState + ":" + record.storageError + ")");
        }
        try {
            byte[] clear = toJson(record).toString().getBytes(StandardCharsets.UTF_8);
            byte[] encrypted = preferences.contains(METADATA_PREFIX + record.id)
                    ? AgramSecureStore.encryptExisting(record.id, clear, AgramSecureStore.aad(record.id, "metadata"))
                    : AgramSecureStore.encrypt(record.id, clear, AgramSecureStore.aad(record.id, "metadata"));
            SharedPreferences.Editor editor = preferences.edit()
                    .putString(SLOT_PREFIX + record.account, record.id)
                    .putString(METADATA_PREFIX + record.id, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .putString(PUSH_INSTANCE_HASH_PREFIX + record.id, pushInstanceHash(record.agramPushInstance))
                    .remove(QUARANTINE_PREFIX + record.id);
            boolean committed = AgramPreferenceTransaction.commit(preferences, editor,
                    SLOT_PREFIX + record.account, METADATA_PREFIX + record.id,
                    PUSH_INSTANCE_HASH_PREFIX + record.id, QUARANTINE_PREFIX + record.id);
            if (!committed) {
                throw new ContainerPersistenceException("Unable to commit Agram container " + record.account);
            }
            recordCache.put(record.account, copyRecord(record));
        } catch (ContainerPersistenceException e) {
            recordCache.remove(record.account);
            throw e;
        } catch (Exception e) {
            recordCache.remove(record.account);
            throw new ContainerPersistenceException("Unable to persist Agram container " + record.account, e);
        }
    }

    private static ContainerRecord copyRecord(ContainerRecord source) {
        if (source == null) {
            return null;
        }
        ContainerRecord copy = new ContainerRecord();
        copy.id = source.id;
        copy.account = source.account;
        copy.name = source.name;
        copy.color = source.color;
        copy.createdAt = source.createdAt;
        copy.profileMode = source.profileMode;
        copy.presetIndex = source.presetIndex;
        copy.deviceModel = source.deviceModel;
        copy.systemVersion = source.systemVersion;
        copy.appVersion = source.appVersion;
        copy.profileId = source.profileId;
        copy.profileGeneratedAt = source.profileGeneratedAt;
        copy.profileLocked = source.profileLocked;
        copy.languageCode = source.languageCode;
        copy.systemLanguageCode = source.systemLanguageCode;
        copy.clientLanguageCode = source.clientLanguageCode;
        copy.fixedTimezone = source.fixedTimezone;
        copy.timezoneOffset = source.timezoneOffset;
        copy.proxyMode = source.proxyMode;
        copy.killSwitch = source.killSwitch;
        copy.proxyEnabled = source.proxyEnabled;
        copy.proxyAddress = source.proxyAddress;
        copy.proxyPort = source.proxyPort;
        copy.proxyUsername = source.proxyUsername;
        copy.proxyPassword = source.proxyPassword;
        copy.proxySecret = source.proxySecret;
        copy.pushMode = source.pushMode;
        copy.agramPushInstance = source.agramPushInstance;
        copy.agramPushEndpoint = source.agramPushEndpoint;
        copy.agramPushStatus = source.agramPushStatus;
        copy.notificationPrivacy = source.notificationPrivacy;
        copy.pinSalt = source.pinSalt;
        copy.pinHash = source.pinHash;
        copy.biometricEnabled = source.biometricEnabled;
        copy.ghostModeEnabled = source.ghostModeEnabled;
        copy.keepDeletedMessages = source.keepDeletedMessages;
        copy.ghostSuppressReadReceipts = source.ghostSuppressReadReceipts;
        copy.ghostSuppressStoryViews = source.ghostSuppressStoryViews;
        copy.ghostSuppressTyping = source.ghostSuppressTyping;
        copy.ghostMinimizeOnline = source.ghostMinimizeOnline;
        copy.ghostReadOnInteraction = source.ghostReadOnInteraction;
        copy.ghostWarnBeforeInteraction = source.ghostWarnBeforeInteraction;
        copy.storageState = source.storageState;
        copy.storageError = source.storageError;
        return copy;
    }

    private ContainerRecord readRecord(int account, String id) {
        try {
            boolean wasUnavailable = preferences.contains(QUARANTINE_PREFIX + id);
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
            if (storedSchema < SCHEMA_VERSION || !json.has("keep_deleted_messages") || json.has("decoy_codes")
                    || LEGACY_NETWORK_TOR.equals(json.optString("proxy_mode", ""))) {
                // Version 2 removes legacy false-code hashes from encrypted
                // metadata instead of only hiding their settings UI.
                saveRecord(record);
            }
            record.storageState = STORAGE_READY;
            record.storageError = "";
            if (!preferences.edit().remove(QUARANTINE_PREFIX + id).commit()) {
                FileLog.e("Unable to clear Agram container recovery status for " + id);
            }
            if (wasUnavailable) {
                notifyStorageStateChanged(account, STORAGE_READY, "");
            }
            return record;
        } catch (ContainerPersistenceException e) {
            return quarantineRecord(account, id, STORAGE_LOCKED, "metadata_write_failed", e);
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
        if (!preferences.edit().putString(QUARANTINE_PREFIX + id, state + ":" + error).commit()) {
            FileLog.e("Unable to persist Agram container recovery status for " + id);
        }
        notifyStorageStateChanged(account, state, error);
        if (cause != null) {
            FileLog.e("Agram container " + account + " is " + state + " (" + error + ")", cause);
        } else {
            FileLog.e("Agram container " + account + " is " + state + " (" + error + ")");
        }
        return record;
    }

    private static void notifyStorageStateChanged(int account, String state, String error) {
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(account).postNotificationName(
                NotificationCenter.agramContainerStorageStateChanged,
                account,
                safe(state),
                safe(error)));
    }

    private static void notifyPersistenceFailure(int account, String operation) {
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(account).postNotificationName(
                NotificationCenter.agramContainerPersistenceFailed,
                account,
                operation));
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
        json.put("push_mode", record.pushMode);
        json.put("agram_push_instance", record.agramPushInstance);
        json.put("agram_push_endpoint", record.agramPushEndpoint);
        json.put("agram_push_status", record.agramPushStatus);
        json.put("notification_privacy", record.notificationPrivacy);
        json.put("pin_salt", record.pinSalt);
        json.put("pin_hash", record.pinHash);
        json.put("biometric", record.biometricEnabled);
        json.put("keep_deleted_messages", record.keepDeletedMessages);
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
        String storedProxyMode = json.optString("proxy_mode", NETWORK_DIRECT);
        boolean migratedFromTor = LEGACY_NETWORK_TOR.equals(storedProxyMode);
        record.proxyMode = normalizeNetworkMode(storedProxyMode);
        record.killSwitch = NETWORK_PROXY.equals(record.proxyMode)
                && json.optBoolean("kill_switch", false);
        record.proxyEnabled = NETWORK_PROXY.equals(record.proxyMode)
                && json.optBoolean("proxy_enabled", false);
        record.proxyAddress = json.optString("proxy_address", "");
        record.proxyPort = json.optInt("proxy_port", 1080);
        record.proxyUsername = json.optString("proxy_username", "");
        record.proxyPassword = json.optString("proxy_password", "");
        record.proxySecret = json.optString("proxy_secret", "");
        if (migratedFromTor) {
            // Embedded Tor was removed in schema 7. Retire only its route
            // metadata; the container id, Keystore key and Telegram session
            // remain untouched so an in-place update cannot log the user out.
            record.proxyMode = NETWORK_DIRECT;
            record.killSwitch = false;
            record.proxyEnabled = false;
            record.proxyAddress = "";
            record.proxyPort = 1080;
            record.proxyUsername = "";
            record.proxyPassword = "";
            record.proxySecret = "";
        }
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
        record.keepDeletedMessages = json.has("keep_deleted_messages")
                ? json.getBoolean("keep_deleted_messages")
                : ApplicationLoader.applicationContext.getSharedPreferences(
                        record.account == 0 ? "mainconfig" : "mainconfig" + record.account, Context.MODE_PRIVATE)
                        .getBoolean(MessagesController.AGRAM_KEEP_DELETED_MESSAGES, true);
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
        return NETWORK_PROXY.equals(value) ? NETWORK_PROXY : NETWORK_DIRECT;
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

    private static boolean isStrictUuid(String value) {
        if (TextUtils.isEmpty(value)) {
            return false;
        }
        try {
            return UUID.fromString(value).toString().equals(value.toLowerCase(Locale.US));
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

    private static File getValidatedContainerChild(String name, boolean tombstone) {
        String id = tombstone && name != null && name.startsWith(".deleting-")
                ? name.substring(".deleting-".length()) : name;
        if (!isStrictUuid(id) || tombstone != (name != null && name.startsWith(".deleting-"))) {
            return null;
        }
        try {
            File root = new File(ApplicationLoader.applicationContext.getFilesDir(),
                    "agram_containers").getCanonicalFile();
            File child = new File(root, name).getCanonicalFile();
            return root.equals(child.getParentFile()) ? child : null;
        } catch (IOException e) {
            FileLog.e("Unable to validate Agram container path", e);
            return null;
        }
    }

    private static void moveAtomically(File source, File destination) throws IOException {
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source.toPath(), destination.toPath());
        }
    }

    private void sweepContainerTombstones() {
        Set<String> deletionIds = new HashSet<>();
        for (Map.Entry<String, ?> preference : preferences.getAll().entrySet()) {
            String key = preference.getKey();
            if (key != null && key.startsWith(DELETION_INTENT_PREFIX)) {
                String id = key.substring(DELETION_INTENT_PREFIX.length());
                if (isStrictUuid(id) && Boolean.TRUE.equals(preference.getValue())) {
                    deletionIds.add(id);
                }
            }
        }
        File root;
        try {
            root = new File(ApplicationLoader.applicationContext.getFilesDir(),
                    "agram_containers").getCanonicalFile();
        } catch (IOException e) {
            FileLog.e("Unable to resolve Agram containers root", e);
            return;
        }
        File[] children = root.listFiles();
        if (children != null) {
            for (File child : children) {
                String name = child == null ? null : child.getName();
                if (name == null || !name.startsWith(".deleting-")
                        || !isStrictUuid(name.substring(".deleting-".length()))) {
                    continue;
                }
                String id = name.substring(".deleting-".length());
                // Upgrade tombstones created by an older build to the durable intent protocol.
                if (preferences.edit().putBoolean(DELETION_INTENT_PREFIX + id, true).commit()) {
                    deletionIds.add(id);
                }
            }
        }
        for (String id : deletionIds) {
            resumeContainerDeletion(id);
        }
    }

    private void resumeContainerDeletion(String id) {
        if (!canDeleteRetiredContainer(id)) return;
        File containerDirectory = getValidatedContainerChild(id, false);
        File tombstone = getValidatedContainerChild(".deleting-" + id, true);
        if (containerDirectory == null || tombstone == null) {
            FileLog.e("Refusing unsafe persisted Agram container deletion for id=" + id);
            return;
        }
        if (containerDirectory.exists()) {
            if (tombstone.exists()) {
                // Never guess which duplicate tree is authoritative. Keep the durable intent
                // and retry safely on a later startup after the existing tombstone is removed.
                Utilities.globalQueue.postRunnable(() -> deleteTombstone(tombstone));
                return;
            }
            try {
                moveAtomically(containerDirectory, tombstone);
                syncDirectory(tombstone.getParentFile());
            } catch (Throwable e) {
                FileLog.e("Unable to resume Agram container tombstoning " + containerDirectory, e);
                return;
            }
        }
        if (!finalizeTombstonedContainer(id)) {
            scheduleContainerDeletionRetry(id);
            return;
        }
        if (tombstone.exists()) {
            Utilities.globalQueue.postRunnable(() -> deleteTombstone(tombstone));
        } else {
            clearDeletionIntent(id);
        }
    }

    private boolean finalizeTombstonedContainer(String id) {
        if (!isStrictUuid(id) || !canDeleteRetiredContainer(id)) {
            return false;
        }
        SharedPreferences.Editor editor = preferences.edit();
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            if (TextUtils.equals(id, preferences.getString(SLOT_PREFIX + account, null))) {
                editor.remove(SLOT_PREFIX + account);
            }
        }
        boolean committed = editor.remove(METADATA_PREFIX + id)
                .remove(PUSH_INSTANCE_HASH_PREFIX + id)
                .remove(QUARANTINE_PREFIX + id)
                .commit();
        if (!committed) {
            FileLog.e("Unable to finalize Agram container registry deletion for " + id);
            return false;
        }
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            ContainerRecord cached = recordCache.get(account);
            if (cached != null && TextUtils.equals(id, cached.id)) {
                recordCache.remove(account);
            }
        }
        // Retire the wrapping key only after the registry commit is durable.
        AgramSecureStore.deleteKey(id);
        return true;
    }

    private void scheduleContainerDeletionRetry(String id) {
        Utilities.globalQueue.postRunnable(() -> {
            synchronized (sync) {
                resumeContainerDeletion(id);
            }
        }, 30_000);
    }

    private void deleteTombstone(File tombstone) {
        File validated = tombstone == null ? null
                : getValidatedContainerChild(tombstone.getName(), true);
        if (validated == null || !validated.equals(tombstone)) {
            FileLog.e("Refusing unsafe Agram tombstone deletion " + tombstone);
            return;
        }
        if (!canDeleteRetiredContainer(validated.getName().substring(".deleting-".length()))) return;
        deleteRecursively(validated, validated);
        syncDirectory(validated.getParentFile());
        if (validated.exists()) {
            Utilities.globalQueue.postRunnable(() -> deleteTombstone(validated), 30_000);
        } else {
            String id = validated.getName().substring(".deleting-".length());
            File original = getValidatedContainerChild(id, false);
            if (original != null && original.exists()
                    && preferences.getBoolean(DELETION_INTENT_PREFIX + id, false)) {
                synchronized (sync) {
                    resumeContainerDeletion(id);
                }
            } else {
                clearDeletionIntent(id);
            }
        }
    }

    private void clearDeletionIntent(String id) {
        if (!isStrictUuid(id)) {
            return;
        }
        synchronized (sync) {
            if (hasMappedSlot(id)) {
                // A prior registry commit may have failed after the tombstone was made. Retry
                // retiring the exact mapped UUID before dropping the only durable recovery hint.
                if (!finalizeTombstonedContainer(id)) {
                    scheduleContainerDeletionRetry(id);
                    return;
                }
            }
            File original = getValidatedContainerChild(id, false);
            File tombstone = getValidatedContainerChild(".deleting-" + id, true);
            if (hasMappedSlot(id) || (original != null && original.exists())
                    || (tombstone != null && tombstone.exists())) {
                scheduleContainerDeletionRetry(id);
                return;
            }
            if (!preferences.edit().remove(DELETION_INTENT_PREFIX + id)
                    .remove(NATIVE_RETIREMENT_PREFIX + id).remove(NATIVE_RETIRED_PREFIX + id).commit()) {
                FileLog.e("Unable to clear Agram container deletion intent for " + id);
            }
        }
    }

    private boolean hasMappedSlot(String id) {
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            if (TextUtils.equals(id, preferences.getString(SLOT_PREFIX + account, null))) {
                return true;
            }
        }
        return false;
    }

    private static void syncDirectory(File directory) {
        FileDescriptor descriptor = null;
        try {
            descriptor = android.system.Os.open(directory.getAbsolutePath(),
                    android.system.OsConstants.O_RDONLY, 0);
            android.system.Os.fsync(descriptor);
        } catch (Throwable e) {
            FileLog.e("Unable to fsync Agram container directory " + directory, e);
        } finally {
            if (descriptor != null) {
                try {
                    android.system.Os.close(descriptor);
                } catch (Throwable e) {
                    FileLog.e("Unable to close Agram container directory " + directory, e);
                }
            }
        }
    }

    private static void deleteRecursively(File file, File validatedRoot) {
        if (file == null || !file.exists()) {
            return;
        }
        try {
            File canonical = file.getCanonicalFile();
            String rootPath = validatedRoot.getCanonicalPath();
            if (!canonical.equals(validatedRoot)
                    && !canonical.getPath().startsWith(rootPath + File.separator)) {
                // Do not follow a symlink or corrupt directory entry outside the tombstone.
                if (Files.isSymbolicLink(file.toPath()) && !file.delete()) {
                    FileLog.e("Unable to delete unsafe Agram tombstone link " + file);
                }
                return;
            }
        } catch (IOException e) {
            FileLog.e("Unable to validate Agram tombstone child " + file, e);
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child, validatedRoot);
            }
        }
        if (!file.delete()) {
            FileLog.e("Unable to delete Agram container file " + file);
        }
    }
}
