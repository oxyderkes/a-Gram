/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Base64;
import android.util.LongSparseArray;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;

import java.io.File;
import java.util.Arrays;

public class UserConfig extends BaseController {

    public static volatile int selectedAccount;
    public static final int ACCOUNT_STATE_EMPTY = 0;
    public static final int ACCOUNT_STATE_ACTIVE = 1;
    public static final int ACCOUNT_STATE_FROZEN = 2;
    public static final int ACCOUNT_STATE_BLOCKED = 3;
    private static final String ACCOUNT_STATE_PREFERENCES = "agram_account_states";
    private static final String ACCOUNT_SELECTION_PREFERENCES = "agram_account_selection";
    private static final String SELECTED_ACCOUNT_KEY = "selected_account";
    private static final String ENCRYPTED_ACCOUNT_CARD_PREFIX = "v1:";
    /**
     * Number of local account slots supported by this fork.
     *
     * Telegram's upstream UI applies a Premium-dependent limit to these slots.
     * This fork deliberately exposes every slot to every user. Keeping a finite
     * pool avoids unbounded native resources while providing a practical
     * "no account limit" experience on a phone.
     */
    public final static int MAX_ACCOUNT_COUNT = 32;

    private final Object sync = new Object();
    private final AgramSessionLifecycle sessionLifecycle = new AgramSessionLifecycle();
    private static final String CONTACTS_CHOICE = "agram_contacts_choice_v1";
    private boolean contactSyncChoiceMade;
    private long contactSyncGeneration;
    private volatile boolean configLoaded;
    private TLRPC.User currentUser;
    private TLRPC.User retainedUser;
    private int retainedAccountState = ACCOUNT_STATE_EMPTY;
    public boolean registeredForPush;
    public int lastSendMessageId = -210000;
    public int lastBroadcastId = -1;
    public int contactsSavedCount;
    public long clientUserId;
    public int lastContactsSyncTime;
    public int lastHintsSyncTime;
    public boolean draftsLoaded;
    public boolean unreadDialogsLoaded = true;
    public TL_account.tmpPassword tmpPassword;
    public int ratingLoadTime;
    public int botRatingLoadTime;
    public int botGuestRatingLoadTime;
    public int webappRatingLoadTime;
    public boolean contactsReimported;
    public boolean hasValidDialogLoadIds;
    public int migrateOffsetId = -1;
    public int migrateOffsetDate = -1;
    public long migrateOffsetUserId = -1;
    public long migrateOffsetChatId = -1;
    public long migrateOffsetChannelId = -1;
    public long migrateOffsetAccess = -1;
    public boolean filtersLoaded;

    public int sharingMyLocationUntil;
    public int lastMyLocationShareTime;

    public boolean notificationsSettingsLoaded;
    public boolean notificationsSignUpSettingsLoaded;
    public volatile boolean syncContacts;
    public boolean suggestContacts = true;
    public boolean showCallsTab;
    public boolean hasSecureData;
    public int loginTime;
    public TLRPC.TL_help_termsOfService unacceptedTermsOfService;
    public long autoDownloadConfigLoadTime;

    public String premiumGiftsStickerPack;
    public String premiumTonStickerPack;
    public String genericAnimationsStickerPack;
    public String defaultTopicIcons;
    public long lastUpdatedPremiumGiftsStickerPack;
    public long lastUpdatedTonGiftsStickerPack;
    public long lastUpdatedGenericAnimations;
    public long lastUpdatedDefaultTopicIcons;

    public volatile byte[] savedPasswordHash;
    public volatile byte[] savedSaltedPassword;
    public volatile long savedPasswordTime;
    LongSparseArray<SaveToGallerySettingsHelper.DialogException> userSaveGalleryExceptions;
    LongSparseArray<SaveToGallerySettingsHelper.DialogException> chanelSaveGalleryExceptions;
    LongSparseArray<SaveToGallerySettingsHelper.DialogException> groupsSaveGalleryExceptions;


    private static volatile UserConfig[] Instance = new UserConfig[UserConfig.MAX_ACCOUNT_COUNT];
    public static UserConfig getInstance(int num) {
        UserConfig localInstance = Instance[num];
        if (localInstance == null) {
            synchronized (UserConfig.class) {
                localInstance = Instance[num];
                if (localInstance == null) {
                    Instance[num] = localInstance = new UserConfig(num);
                }
            }
        }
        return localInstance;
    }

    public static int getActivatedAccountsCount() {
        int count = 0;
        for (int a = 0; a < MAX_ACCOUNT_COUNT; a++) {
            if (getInstance(a).isClientActivated()) {
                count++;
            }
        }
        return count;
    }

    public static int getVisibleAccountsCount() {
        int count = 0;
        for (int a = 0; a < MAX_ACCOUNT_COUNT; a++) {
            if (getInstance(a).hasAccountEntry()) {
                count++;
            }
        }
        return count;
    }

    public static int getAvailableAccountSlot() {
        int empty = -1;
        for (int a = MAX_ACCOUNT_COUNT - 1; a >= 0; a--) {
            UserConfig config = getInstance(a);
            if (!config.isClientActivated() && !config.hasPersistedSession()
                    && AgramContainerManager.getInstance().canUseForNewLogin(a)) {
                empty = a;
            }
        }
        return empty;
    }

    /**
     * The selected engine slot is process-global state, not account-0 state.
     * Persist it synchronously in a dedicated registry so clearing account 0
     * or killing the process for an APK update cannot roll the pointer back.
     */
    public static boolean setSelectedAccountPersisted(int account) {
        if (account < 0 || account >= MAX_ACCOUNT_COUNT) {
            throw new IllegalArgumentException("Invalid account slot " + account);
        }
        synchronized (UserConfig.class) {
            boolean committed = getAccountSelectionPreferences().edit()
                    .putInt(SELECTED_ACCOUNT_KEY, account)
                    .commit();
            if (!committed) {
                FileLog.e("Unable to persist selected Agram account " + account);
                return false;
            }
            selectedAccount = account;
            return true;
        }
    }

    /** Repairs a stale slot pointer after every cold start. */
    public static int reconcileSelectedAccount() {
        int account = selectedAccount;
        if (account >= 0 && account < MAX_ACCOUNT_COUNT && getInstance(account).isClientActivated()) {
            return persistReconciledAccount(account);
        }
        for (int a = 0; a < MAX_ACCOUNT_COUNT; a++) {
            if (getInstance(a).isClientActivated()) {
                return persistReconciledAccount(a);
            }
        }
        account = getLoginTargetAccount();
        return persistReconciledAccount(account);
    }

    private static int persistReconciledAccount(int account) {
        if (setSelectedAccountPersisted(account)) {
            return account;
        }
        // Do not report an unsaved selection as active. Keep the last durable
        // in-memory pointer and let the next resume/startup retry reconciliation.
        return selectedAccount >= 0 && selectedAccount < MAX_ACCOUNT_COUNT ? selectedAccount : 0;
    }

    /** Returns the canonical lowest free slot for an explicit new login. */
    public static int getLoginTargetAccount() {
        int account = getAvailableAccountSlot();
        if (account >= 0) {
            return account;
        }
        return selectedAccount >= 0 && selectedAccount < MAX_ACCOUNT_COUNT ? selectedAccount : 0;
    }

    private static SharedPreferences getAccountSelectionPreferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences(ACCOUNT_SELECTION_PREFERENCES, Context.MODE_PRIVATE);
    }

    public UserConfig(int instance) {
        super(instance);
    }

    public static boolean hasPremiumOnAccounts() {
        for (int a = 0; a < MAX_ACCOUNT_COUNT; a++) {
            if (getInstance(a).isClientActivated() && getInstance(a).isPremium()) {
                return true;
            }
        }
        return false;
    }

    public static int getMaxAccountCount() {
        return MAX_ACCOUNT_COUNT;
    }

    public int getNewMessageId() {
        int id;
        synchronized (sync) {
            id = lastSendMessageId;
            lastSendMessageId--;
        }
        return id;
    }

    public void saveConfig(boolean withFile) {
        final long expectedGeneration = getSessionGeneration();
        Runnable save = () -> {
            if (!configLoaded) {
                return;
            }
            synchronized (sync) {
                if (!sessionLifecycle.isCurrent(expectedGeneration)) {
                    return;
                }
                try {
                    SharedPreferences.Editor editor = getPreferences().edit();
                    editor.putBoolean("registeredForPush", registeredForPush);
                    editor.putInt("lastSendMessageId", lastSendMessageId);
                    editor.putInt("contactsSavedCount", contactsSavedCount);
                    editor.putInt("lastBroadcastId", lastBroadcastId);
                    editor.putInt("lastContactsSyncTime", lastContactsSyncTime);
                    editor.putInt("lastHintsSyncTime", lastHintsSyncTime);
                    editor.putBoolean("draftsLoaded", draftsLoaded);
                    editor.putBoolean("unreadDialogsLoaded", unreadDialogsLoaded);
                    editor.putInt("ratingLoadTime", ratingLoadTime);
                    editor.putInt("botRatingLoadTime", botRatingLoadTime);
                    editor.putInt("botGuestRatingLoadTime", botGuestRatingLoadTime);
                    editor.putInt("webappRatingLoadTime", webappRatingLoadTime);
                    editor.putBoolean("contactsReimported", contactsReimported);
                    editor.putInt("loginTime", loginTime);
                    editor.putBoolean("syncContacts", AgramSessionLifecycle.contactSyncAllowed(contactSyncChoiceMade, syncContacts));
                    editor.putBoolean("showCallsTab", showCallsTab);
                    editor.putBoolean("suggestContacts", suggestContacts);
                    editor.putBoolean("hasSecureData", hasSecureData);
                    editor.putBoolean("notificationsSettingsLoaded4", notificationsSettingsLoaded);
                    editor.putBoolean("notificationsSignUpSettingsLoaded", notificationsSignUpSettingsLoaded);
                    editor.putLong("autoDownloadConfigLoadTime", autoDownloadConfigLoadTime);
                    editor.putBoolean("hasValidDialogLoadIds", hasValidDialogLoadIds);
                    editor.putInt("sharingMyLocationUntil", sharingMyLocationUntil);
                    editor.putInt("lastMyLocationShareTime", lastMyLocationShareTime);
                    editor.putBoolean("filtersLoaded", filtersLoaded);
                    editor.putString("premiumGiftsStickerPack", premiumGiftsStickerPack);
                    editor.putLong("lastUpdatedPremiumGiftsStickerPack", lastUpdatedPremiumGiftsStickerPack);

                    editor.putString("genericAnimationsStickerPack", genericAnimationsStickerPack);
                    editor.putLong("lastUpdatedGenericAnimations", lastUpdatedGenericAnimations);

                    editor.putInt("6migrateOffsetId", migrateOffsetId);
                    if (migrateOffsetId != -1) {
                        editor.putInt("6migrateOffsetDate", migrateOffsetDate);
                        editor.putLong("6migrateOffsetUserId", migrateOffsetUserId);
                        editor.putLong("6migrateOffsetChatId", migrateOffsetChatId);
                        editor.putLong("6migrateOffsetChannelId", migrateOffsetChannelId);
                        editor.putLong("6migrateOffsetAccess", migrateOffsetAccess);
                    }

                    if (unacceptedTermsOfService != null) {
                        try {
                            SerializedData data = new SerializedData(unacceptedTermsOfService.getObjectSize());
                            unacceptedTermsOfService.serializeToStream(data);
                            editor.putString("terms", Base64.encodeToString(data.toByteArray(), Base64.DEFAULT));
                            data.cleanup();
                        } catch (Exception ignore) {

                        }
                    } else {
                        editor.remove("terms");
                    }

                    SharedConfig.saveConfig();

                    if (tmpPassword != null) {
                        SerializedData data = new SerializedData();
                        tmpPassword.serializeToStream(data);
                        String string = Base64.encodeToString(data.toByteArray(), Base64.DEFAULT);
                        editor.putString("tmpPassword", string);
                        data.cleanup();
                    } else {
                        editor.remove("tmpPassword");
                    }

                    if (currentUser != null) {
                        if (withFile) {
                            SerializedData data = new SerializedData();
                            currentUser.serializeToStream(data);
                            String string = Base64.encodeToString(data.toByteArray(), Base64.DEFAULT);
                            editor.putString("user", string);
                            data.cleanup();
                        }
                    }

                    // Only confirmed clearConfig() may erase a persisted identity. A temporarily
                    // missing runtime user must not turn an ordinary settings save into logout.
                    if (!AgramPreferenceTransaction.commit(getPreferences(), editor, "user")) {
                        FileLog.e("Unable to durably save account config " + currentAccount);
                        NotificationCenter.getInstance(currentAccount).postNotificationName(
                                NotificationCenter.agramContainerPersistenceFailed, currentAccount, "account_save");
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
        };
        if (withFile) {
            save.run();
        } else {
            NotificationCenter.getInstance(currentAccount).doOnIdle(save);
        }
    }

    public long getSessionGeneration() {
        return sessionLifecycle.generation();
    }

    public boolean isSessionGenerationCurrent(long generation) {
        return sessionLifecycle.isCurrent(generation);
    }

    public boolean hasPersistedSession() {
        return getPreferences().contains("user");
    }

    public boolean isContactSyncAllowed() {
        synchronized (sync) {
            return currentUser != null
                    && AgramSessionLifecycle.contactSyncAllowed(contactSyncChoiceMade, syncContacts);
        }
    }

    public long getContactSyncGeneration() {
        synchronized (sync) {
            return contactSyncGeneration;
        }
    }

    public void runIfContactSyncAllowed(long generation, long consentGeneration, Runnable action) {
        synchronized (sync) {
            if (sessionLifecycle.isCurrent(generation) && contactSyncGeneration == consentGeneration
                    && isContactSyncAllowed()) {
                action.run();
            }
        }
    }

    /** Local opt-in only. Disabling never deletes contacts from Telegram. */
    public boolean setContactSyncEnabled(boolean enabled) {
        return setContactSyncEnabled(enabled, getSessionGeneration());
    }

    public boolean setContactSyncEnabled(boolean enabled, long expectedGeneration) {
        synchronized (sync) {
            if (!sessionLifecycle.isCurrent(expectedGeneration)) {
                return false;
            }
            try {
                SharedPreferences preferences = getPreferences();
                if (!AgramPreferenceTransaction.commit(preferences, preferences.edit()
                        .putBoolean(CONTACTS_CHOICE, true).putBoolean("syncContacts", enabled),
                        CONTACTS_CHOICE, "syncContacts")) {
                    notifyContactSyncPersistenceFailure();
                    return false;
                }
            } catch (RuntimeException error) {
                FileLog.e("Unable to persist account contact sync preference");
                notifyContactSyncPersistenceFailure();
                return false;
            }
            contactSyncChoiceMade = true;
            syncContacts = enabled;
            contactSyncGeneration++;
            return true;
        }
    }

    private void notifyContactSyncPersistenceFailure() {
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(currentAccount).postNotificationName(
                NotificationCenter.agramContainerPersistenceFailed, currentAccount, "contact_sync"));
    }

    /** Persist only for the container and session originally displayed by the settings screen. */
    boolean setKeepDeletedMessagesEnabled(boolean enabled, String expectedContainerId, long expectedGeneration) {
        final boolean[] committed = {false};
        try {
            AgramContainerManager manager = AgramContainerManager.getInstance();
            manager.runBoundSettingsUpdate(currentAccount, expectedContainerId, () -> {
                // Lock only the generation monitor. UserConfig.sync may already be held by
                // contact work entering the container manager in the opposite direction.
                synchronized (sessionLifecycle) {
                    if (!sessionLifecycle.isCurrent(expectedGeneration)) {
                        return;
                    }
                    manager.updateKeepDeletedMessages(currentAccount, enabled);
                    committed[0] = true;
                }
            });
        } catch (RuntimeException error) {
            FileLog.e("Unable to persist account deleted-message preference");
        }
        return committed[0];
    }

    /** Persist authorization before native/UI state changes, without clearing an old session. */
    public boolean installAuthorizedUser(TLRPC.User user, boolean contactsEnabled, long expectedGeneration) {
        synchronized (sync) {
            if (user == null || !AgramSessionLifecycle.canInstallAuthorization(currentUser != null,
                    hasPersistedSession(), expectedGeneration, sessionLifecycle.generation())) {
                return false;
            }
            SerializedData data = new SerializedData();
            try {
                user.serializeToStream(data);
                SharedPreferences preferences = getPreferences();
                int authorizationTime = (int) (System.currentTimeMillis() / 1000);
                // Account zero shares this file with SharedConfig's app lock.
                // A new authorization owns only identity/consent/login fields.
                if (!AgramPreferenceTransaction.commit(preferences, preferences.edit()
                        .putString("user", Base64.encodeToString(data.toByteArray(), Base64.DEFAULT))
                        .putBoolean(CONTACTS_CHOICE, true).putBoolean("syncContacts", contactsEnabled)
                        .putInt("loginTime", authorizationTime),
                        "user", CONTACTS_CHOICE, "syncContacts", "loginTime")) {
                    return false;
                }
                sessionLifecycle.advance();
                contactSyncGeneration++;
                contactSyncChoiceMade = true;
                syncContacts = contactsEnabled;
                loginTime = authorizationTime;
                configLoaded = true;
                setCurrentUser(user);
                return true;
            } catch (Exception e) {
                FileLog.e("Unable to save authorized account", e);
                return false;
            } finally {
                data.cleanup();
            }
        }
    }

    public static boolean isValidAccount(int num) {
         return num >= 0 && num < UserConfig.MAX_ACCOUNT_COUNT && getInstance(num).isClientActivated();
    }

    public boolean isClientActivated() {
        synchronized (sync) {
            return currentUser != null;
        }
    }

    public long getClientUserId() {
        synchronized (sync) {
            return currentUser != null ? currentUser.id : 0;
        }
    }

    public String getClientPhone() {
        synchronized (sync) {
            return currentUser != null && currentUser.phone != null ? currentUser.phone : "";
        }
    }

    public TLRPC.User getCurrentUser() {
        synchronized (sync) {
            return currentUser;
        }
    }

    public TLRPC.User getDisplayUser() {
        synchronized (sync) {
            return currentUser;
        }
    }

    public boolean hasAccountEntry() {
        synchronized (sync) {
            return currentUser != null;
        }
    }

    public int getAccountState() {
        synchronized (sync) {
            return currentUser == null ? ACCOUNT_STATE_EMPTY : ACCOUNT_STATE_ACTIVE;
        }
    }

    public String getAccountStatusText() {
        return "";
    }

    public void setCurrentUser(TLRPC.User user) {
        synchronized (sync) {
            TLRPC.User oldUser = currentUser;
            if (retainedUser != null && retainedUser.id != user.id) {
                clearLocalAccountAvatarLocked();
            }
            currentUser = user;
            clientUserId = user.id;
            clearRetainedAccountStateLocked();
            checkPremiumSelf(oldUser, user);
        }
    }

    public void markAccountAccessBlocked() {
        synchronized (sync) {
            clearRetainedAccountStateLocked();
        }
    }

    public boolean clearRetainedAccountState() {
        synchronized (sync) {
            return clearRetainedAccountStateLocked();
        }
    }

    private boolean clearRetainedAccountStateLocked() {
        SharedPreferences preferences = getAccountStatePreferences();
        if (retainedUser == null
                && retainedAccountState == ACCOUNT_STATE_EMPTY
                && !preferences.contains("state_" + currentAccount)
                && !preferences.contains("user_" + currentAccount)) {
            return true;
        }
        boolean committed = preferences.edit()
                .remove("state_" + currentAccount)
                .remove("user_" + currentAccount)
                .commit();
        if (!committed) {
            FileLog.e("Unable to clear retained Agram account state " + currentAccount);
            return false;
        }
        retainedUser = null;
        retainedAccountState = ACCOUNT_STATE_EMPTY;
        clearLocalAccountAvatarLocked();
        return true;
    }

    private SharedPreferences getAccountStatePreferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences(ACCOUNT_STATE_PREFERENCES, Context.MODE_PRIVATE);
    }

    private void loadRetainedAccountState() {
        loadRetainedAccountStateLocked();
    }

    private void loadRetainedAccountStateLocked() {
        retainedUser = null;
        retainedAccountState = ACCOUNT_STATE_EMPTY;
        SharedPreferences preferences = getAccountStatePreferences();
        int state = preferences.getInt("state_" + currentAccount, ACCOUNT_STATE_EMPTY);
        String string = preferences.getString("user_" + currentAccount, null);
        if (string == null) {
            return;
        }
        try {
            boolean encrypted = string.startsWith(ENCRYPTED_ACCOUNT_CARD_PREFIX);
            String encoded = encrypted ? string.substring(ENCRYPTED_ACCOUNT_CARD_PREFIX.length()) : string;
            byte[] bytes = Base64.decode(encoded, Base64.DEFAULT);
            if (encrypted) {
                AgramContainerManager.ContainerRecord container = AgramContainerManager.getInstance().ensureContainer(currentAccount);
                if (!container.isStorageAccessible()) {
                    FileLog.e("Retaining encrypted Agram account card while its local key is unavailable for account " + currentAccount);
                    return;
                }
                bytes = AgramSecureStore.decrypt(
                        container.id,
                        bytes,
                        AgramSecureStore.aad(container.id, "account-card")
                );
            }
            SerializedData data = new SerializedData(bytes);
            retainedUser = TLRPC.User.TLdeserialize(data, data.readInt32(false), false);
            retainedAccountState = retainedUser != null ? ACCOUNT_STATE_BLOCKED : ACCOUNT_STATE_EMPTY;
            data.cleanup();
            if (retainedUser != null) {
                saveLocalAccountAvatarLocked(retainedUser);
            }
            if (retainedUser != null && state != ACCOUNT_STATE_BLOCKED) {
                preferences.edit().putInt("state_" + currentAccount, ACCOUNT_STATE_BLOCKED).commit();
            }
            if (retainedUser != null && !encrypted) {
                saveLocalAccountSnapshotLocked(retainedUser, retainedAccountState, true);
            }
        } catch (AgramSecureStore.KeyUnavailableException | AgramContainerManager.ContainerPersistenceException e) {
            // Keystore and persistence outages are recoverable. Keep the
            // encrypted card untouched so a later cold start can retry it.
            FileLog.e("Retaining encrypted Agram account card after a local storage failure", e);
        } catch (Exception e) {
            FileLog.e(e);
            clearRetainedAccountStateLocked();
        }
    }

    /**
     * Keeps a small, server-independent account card. It is refreshed while the
     * session is healthy and survives a server-side authorization revocation.
     * This deliberately stores profile identity only, not chats or credentials.
     */
    private void saveLocalAccountSnapshotLocked(TLRPC.User user, int state, boolean synchronous) {
        if (user == null) {
            return;
        }
        TLRPC.User previousUser = retainedUser;
        int previousState = retainedAccountState;
        retainedUser = user;
        retainedAccountState = state;
        SerializedData data = null;
        try {
            data = new SerializedData(user.getObjectSize());
            user.serializeToStream(data);
            AgramContainerManager.ContainerRecord container = AgramContainerManager.getInstance().ensureContainer(currentAccount);
            byte[] encrypted = AgramSecureStore.encrypt(
                    container.id,
                    data.toByteArray(),
                    AgramSecureStore.aad(container.id, "account-card")
            );
            SharedPreferences.Editor editor = getAccountStatePreferences().edit()
                    .putInt("state_" + currentAccount, state)
                    .putString("user_" + currentAccount, ENCRYPTED_ACCOUNT_CARD_PREFIX
                            + Base64.encodeToString(encrypted, Base64.NO_WRAP));
            // This snapshot is an identity/recovery boundary. A best-effort
            // apply must not make the in-memory card look durably saved.
            if (!editor.commit()) {
                retainedUser = previousUser;
                retainedAccountState = previousState;
                FileLog.e("Unable to persist retained Agram account state " + currentAccount);
            }
        } catch (Exception e) {
            retainedUser = previousUser;
            retainedAccountState = previousState;
            FileLog.e(e);
        } finally {
            if (data != null) {
                data.cleanup();
            }
        }
    }

    private File getLocalAccountAvatarFile() {
        File directory = new File(ApplicationLoader.applicationContext.getFilesDir(), "agram_account_cards");
        return new File(directory, "account_" + currentAccount + ".jpg");
    }

    private void saveLocalAccountAvatarLocked(TLRPC.User user) {
        if (user == null || user.photo == null || user.photo.photo_small == null) {
            return;
        }
        try {
            File source = FileLoader.getInstance(currentAccount).getPathToAttach(user.photo.photo_small, true);
            if (source == null || !source.isFile() || source.length() == 0) {
                return;
            }
            File destination = getLocalAccountAvatarFile();
            File parent = destination.getParentFile();
            if ((parent.isDirectory() || parent.mkdirs()) && !source.equals(destination)) {
                AndroidUtilities.copyFile(source, destination);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void clearLocalAccountAvatarLocked() {
        try {
            File avatar = getLocalAccountAvatarFile();
            if (avatar.exists() && !avatar.delete()) {
                FileLog.e("Unable to delete retained account avatar " + avatar);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public String getLocalAccountAvatarPath() {
        synchronized (sync) {
            File avatar = getLocalAccountAvatarFile();
            return avatar.isFile() && avatar.length() > 0 ? avatar.getAbsolutePath() : null;
        }
    }

    private void checkPremiumSelf(TLRPC.User oldUser, TLRPC.User newUser) {
        if (oldUser != null && newUser != null && oldUser.premium != newUser.premium) {
            AndroidUtilities.runOnUIThread(() -> {
                getMessagesController().updatePremium(newUser.premium);
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.currentUserPremiumStatusChanged);
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.premiumStatusChangedGlobal);

                getMediaDataController().loadPremiumPromo(false);
                getMediaDataController().loadReactions(false, null);
                getMessagesController().getStoriesController().invalidateStoryLimit();
            });
        } else if (oldUser == null) {
            AndroidUtilities.runOnUIThread(() -> {
                getMessagesController().updatePremium(newUser.premium);
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.currentUserPremiumStatusChanged);
                getMediaDataController().loadPremiumPromo(true);
            });
        }
    }

    public void
    loadConfig() {
        synchronized (sync) {
            if (configLoaded) {
                return;
            }
            SharedPreferences preferences = getPreferences();
            if (currentAccount == 0) {
                SharedPreferences accountSelection = getAccountSelectionPreferences();
                int legacySelectedAccount = preferences.getInt("selectedAccount", 0);
                int storedSelectedAccount = accountSelection.getInt(SELECTED_ACCOUNT_KEY, legacySelectedAccount);
                if (storedSelectedAccount < 0 || storedSelectedAccount >= MAX_ACCOUNT_COUNT) {
                    storedSelectedAccount = 0;
                }
                selectedAccount = storedSelectedAccount;
                if (!accountSelection.contains(SELECTED_ACCOUNT_KEY)) {
                    accountSelection.edit().putInt(SELECTED_ACCOUNT_KEY, storedSelectedAccount).commit();
                }
            }
            registeredForPush = preferences.getBoolean("registeredForPush", false);
            lastSendMessageId = preferences.getInt("lastSendMessageId", -210000);
            contactsSavedCount = preferences.getInt("contactsSavedCount", 0);
            lastBroadcastId = preferences.getInt("lastBroadcastId", -1);
            lastContactsSyncTime = preferences.getInt("lastContactsSyncTime", (int) (System.currentTimeMillis() / 1000) - 23 * 60 * 60);
            lastHintsSyncTime = preferences.getInt("lastHintsSyncTime", (int) (System.currentTimeMillis() / 1000) - 25 * 60 * 60);
            draftsLoaded = preferences.getBoolean("draftsLoaded", false);
            unreadDialogsLoaded = preferences.getBoolean("unreadDialogsLoaded", false);
            contactsReimported = preferences.getBoolean("contactsReimported", false);
            ratingLoadTime = preferences.getInt("ratingLoadTime", 0);
            botRatingLoadTime = preferences.getInt("botRatingLoadTime", 0);
            botGuestRatingLoadTime = preferences.getInt("botGuestRatingLoadTime", 0);
            webappRatingLoadTime = preferences.getInt("webappRatingLoadTime", 0);
            loginTime = preferences.getInt("loginTime", currentAccount);
            // Legacy true was a default, not evidence of consent. Keep existing remote
            // contacts, but require a separate choice before reading/uploading this phonebook.
            contactSyncChoiceMade = preferences.getBoolean(CONTACTS_CHOICE, false);
            syncContacts = AgramSessionLifecycle.contactSyncAllowed(contactSyncChoiceMade,
                    preferences.getBoolean("syncContacts", false));
            showCallsTab = preferences.getBoolean("showCallsTab", false);
            suggestContacts = preferences.getBoolean("suggestContacts", true);
            hasSecureData = preferences.getBoolean("hasSecureData", false);
            notificationsSettingsLoaded = preferences.getBoolean("notificationsSettingsLoaded4", false);
            notificationsSignUpSettingsLoaded = preferences.getBoolean("notificationsSignUpSettingsLoaded", false);
            autoDownloadConfigLoadTime = preferences.getLong("autoDownloadConfigLoadTime", 0);
            hasValidDialogLoadIds = preferences.contains("2dialogsLoadOffsetId") || preferences.getBoolean("hasValidDialogLoadIds", false);
            sharingMyLocationUntil = preferences.getInt("sharingMyLocationUntil", 0);
            lastMyLocationShareTime = preferences.getInt("lastMyLocationShareTime", 0);
            filtersLoaded = preferences.getBoolean("filtersLoaded", false);
            premiumGiftsStickerPack = preferences.getString("premiumGiftsStickerPack", null);
            lastUpdatedPremiumGiftsStickerPack = preferences.getLong("lastUpdatedPremiumGiftsStickerPack", 0);

            genericAnimationsStickerPack = preferences.getString("genericAnimationsStickerPack", null);
            lastUpdatedGenericAnimations = preferences.getLong("lastUpdatedGenericAnimations", 0);


            try {
                String terms = preferences.getString("terms", null);
                if (terms != null) {
                    byte[] arr = Base64.decode(terms, Base64.DEFAULT);
                    if (arr != null) {
                        SerializedData data = new SerializedData(arr);
                        unacceptedTermsOfService = TLRPC.TL_help_termsOfService.TLdeserialize(data, data.readInt32(false), false);
                        data.cleanup();
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            }

            migrateOffsetId = preferences.getInt("6migrateOffsetId", 0);
            if (migrateOffsetId != -1) {
                migrateOffsetDate = preferences.getInt("6migrateOffsetDate", 0);
                migrateOffsetUserId = AndroidUtilities.getPrefIntOrLong(preferences, "6migrateOffsetUserId", 0);
                migrateOffsetChatId = AndroidUtilities.getPrefIntOrLong(preferences, "6migrateOffsetChatId", 0);
                migrateOffsetChannelId = AndroidUtilities.getPrefIntOrLong(preferences, "6migrateOffsetChannelId", 0);
                migrateOffsetAccess = preferences.getLong("6migrateOffsetAccess", 0);
            }

            String string = preferences.getString("tmpPassword", null);
            if (string != null) {
                byte[] bytes = Base64.decode(string, Base64.DEFAULT);
                if (bytes != null) {
                    SerializedData data = new SerializedData(bytes);
                    tmpPassword = TL_account.tmpPassword.TLdeserialize(data, data.readInt32(false), false);
                    data.cleanup();
                }
            }

            string = preferences.getString("user", null);
            try {
                if (string != null) {
                    byte[] bytes = Base64.decode(string, Base64.DEFAULT);
                    if (bytes != null) {
                        SerializedData data = new SerializedData(bytes);
                        try {
                            currentUser = TLRPC.User.TLdeserialize(data, data.readInt32(false), false);
                        } finally {
                            data.cleanup();
                        }
                    }
                }
            } catch (Exception e) {
                // Preserve the persisted user/native session and reserve its slot for recovery.
                // A parsing/local-storage failure is not a Telegram logout confirmation.
                FileLog.e("Unable to restore account identity; persisted session retained", e);
            }
            if (currentUser != null) {
                checkPremiumSelf(null, currentUser);
                clientUserId = currentUser.id;
            }
            if (currentUser != null) {
                clearRetainedAccountStateLocked();
            }
            configLoaded = true;
        }
    }

    public boolean isConfigLoaded() {
        return configLoaded;
    }

    public void savePassword(byte[] hash, byte[] salted) {
        savedPasswordTime = SystemClock.elapsedRealtime();
        savedPasswordHash = hash;
        savedSaltedPassword = salted;
    }

    public void checkSavedPassword() {
        if (savedSaltedPassword == null && savedPasswordHash == null || Math.abs(SystemClock.elapsedRealtime() - savedPasswordTime) < 30 * 60 * 1000) {
            return;
        }
        resetSavedPassword();
    }

    public void resetSavedPassword() {
        savedPasswordTime = 0;
        if (savedPasswordHash != null) {
            Arrays.fill(savedPasswordHash, (byte) 0);
            savedPasswordHash = null;
        }
        if (savedSaltedPassword != null) {
            Arrays.fill(savedSaltedPassword, (byte) 0);
            savedSaltedPassword = null;
        }
    }

    public SharedPreferences getPreferences() {
        if (currentAccount == 0) {
            return ApplicationLoader.applicationContext.getSharedPreferences("userconfing", Context.MODE_PRIVATE);
        } else {
            return ApplicationLoader.applicationContext.getSharedPreferences("userconfig" + currentAccount, Context.MODE_PRIVATE);
        }
    }

    public LongSparseArray<SaveToGallerySettingsHelper.DialogException> getSaveGalleryExceptions(int type) {
        if (type == SharedConfig.SAVE_TO_GALLERY_FLAG_PEER) {
            if (userSaveGalleryExceptions == null) {
                userSaveGalleryExceptions = SaveToGallerySettingsHelper.loadExceptions(ApplicationLoader.applicationContext.getSharedPreferences(SaveToGallerySettingsHelper.USERS_PREF_NAME + "_" + currentAccount, Context.MODE_PRIVATE));
            }
            return userSaveGalleryExceptions;
        } else if (type == SharedConfig.SAVE_TO_GALLERY_FLAG_GROUP) {
            if (groupsSaveGalleryExceptions == null) {
                groupsSaveGalleryExceptions = SaveToGallerySettingsHelper.loadExceptions(ApplicationLoader.applicationContext.getSharedPreferences(SaveToGallerySettingsHelper.GROUPS_PREF_NAME + "_" + currentAccount, Context.MODE_PRIVATE));
            }
            return groupsSaveGalleryExceptions;
        } else  if (type == SharedConfig.SAVE_TO_GALLERY_FLAG_CHANNELS) {
            if (chanelSaveGalleryExceptions == null) {
                chanelSaveGalleryExceptions = SaveToGallerySettingsHelper.loadExceptions(ApplicationLoader.applicationContext.getSharedPreferences(SaveToGallerySettingsHelper.CHANNELS_PREF_NAME + "_" + currentAccount, Context.MODE_PRIVATE));
            }
            return chanelSaveGalleryExceptions;
        }
        return null;
    }

    public void updateSaveGalleryExceptions(int type, LongSparseArray<SaveToGallerySettingsHelper.DialogException> exceptions) {
        if (type == SharedConfig.SAVE_TO_GALLERY_FLAG_PEER) {
            userSaveGalleryExceptions = exceptions;
            SaveToGallerySettingsHelper.saveExceptions(
                    ApplicationLoader.applicationContext.getSharedPreferences(SaveToGallerySettingsHelper.USERS_PREF_NAME + "_" + currentAccount, Context.MODE_PRIVATE),
                    userSaveGalleryExceptions
            );
        } else if (type == SharedConfig.SAVE_TO_GALLERY_FLAG_GROUP) {
            groupsSaveGalleryExceptions = exceptions;
            SaveToGallerySettingsHelper.saveExceptions(
                    ApplicationLoader.applicationContext.getSharedPreferences(SaveToGallerySettingsHelper.GROUPS_PREF_NAME + "_" + currentAccount, Context.MODE_PRIVATE),
                    groupsSaveGalleryExceptions
            );
        } else  if (type == SharedConfig.SAVE_TO_GALLERY_FLAG_CHANNELS) {
            chanelSaveGalleryExceptions = exceptions;
            SaveToGallerySettingsHelper.saveExceptions(
                    ApplicationLoader.applicationContext.getSharedPreferences(SaveToGallerySettingsHelper.CHANNELS_PREF_NAME + "_" + currentAccount, Context.MODE_PRIVATE),
                    chanelSaveGalleryExceptions
            );
        }
    }

    public boolean clearConfig() {
        return clearConfig(false);
    }

    public boolean clearConfig(boolean preserveBlockedAccount) {
        return clearConfig(preserveBlockedAccount, getSessionGeneration());
    }

    public boolean clearConfig(boolean preserveBlockedAccount, long expectedGeneration) {
        synchronized (sync) {
        if (!sessionLifecycle.isCurrent(expectedGeneration)) {
            return false;
        }
        try {
            // Clear the small cross-session identity card first. If that fails,
            // leave the primary account preferences and runtime session untouched.
            if (!clearRetainedAccountState()) {
                return false;
            }
            SharedPreferences accountPreferences = getPreferences();
            String[] accountKeys = AgramAccountPreferenceKeys.removalKeys(accountPreferences.getAll().keySet());
            SharedPreferences.Editor accountCleanup = accountPreferences.edit();
            for (String key : accountKeys) accountCleanup.remove(key);
            if (!AgramPreferenceTransaction.commit(accountPreferences, accountCleanup, accountKeys)) {
                FileLog.e("Unable to durably clear account config " + currentAccount);
                return false;
            }
        } catch (RuntimeException error) {
            FileLog.e("Unable to durably clear account config " + currentAccount);
            return false;
        }
        sessionLifecycle.advance();
        contactSyncGeneration++;
        contactSyncChoiceMade = false;

        sharingMyLocationUntil = 0;
        lastMyLocationShareTime = 0;
        currentUser = null;
        tmpPassword = null;
        clientUserId = 0;
        registeredForPush = false;
        contactsSavedCount = 0;
        lastSendMessageId = -210000;
        lastBroadcastId = -1;
        notificationsSettingsLoaded = false;
        notificationsSignUpSettingsLoaded = false;
        migrateOffsetId = -1;
        migrateOffsetDate = -1;
        migrateOffsetUserId = -1;
        migrateOffsetChatId = -1;
        migrateOffsetChannelId = -1;
        migrateOffsetAccess = -1;
        ratingLoadTime = 0;
        botRatingLoadTime = 0;
        botGuestRatingLoadTime = 0;
        webappRatingLoadTime = 0;
        draftsLoaded = false;
        contactsReimported = true;
        syncContacts = false;
        showCallsTab = false;
        suggestContacts = true;
        unreadDialogsLoaded = true;
        hasValidDialogLoadIds = true;
        unacceptedTermsOfService = null;
        filtersLoaded = false;
        hasSecureData = false;
        loginTime = (int) (System.currentTimeMillis() / 1000);
        lastContactsSyncTime = (int) (System.currentTimeMillis() / 1000) - 23 * 60 * 60;
        lastHintsSyncTime = (int) (System.currentTimeMillis() / 1000) - 25 * 60 * 60;
        resetSavedPassword();
        }
        boolean hasActivated = false;
        for (int a = 0; a < MAX_ACCOUNT_COUNT; a++) {
            try {
                UserConfig other = getInstance(a);
                if (other.isClientActivated() || other.hasPersistedSession()) {
                    hasActivated = true;
                    break;
                }
            } catch (RuntimeException unavailablePreferences) {
                // Unknown/unloaded is not proof that no other session exists.
                // Preserve the application lock and shared settings on failure.
                hasActivated = true;
                break;
            }
        }
        if (!hasActivated) {
            SharedConfig.clearConfig();
        }
        saveConfig(true);
        return true;
    }

    public boolean isPinnedDialogsLoaded(int folderId) {
        return getPreferences().getBoolean("2pinnedDialogsLoaded" + folderId, false);
    }

    public void setPinnedDialogsLoaded(int folderId, boolean loaded) {
        getPreferences().edit().putBoolean("2pinnedDialogsLoaded" + folderId, loaded).commit();
    }

    public void clearPinnedDialogsLoaded() {
        SharedPreferences.Editor editor = getPreferences().edit();
        for (String key : getPreferences().getAll().keySet()) {
            if (key.startsWith("2pinnedDialogsLoaded")) {
                editor.remove(key);
            }
        }
        editor.apply();
    }

    public static final int i_dialogsLoadOffsetId = 0;
    public static final int i_dialogsLoadOffsetDate = 1;
    public static final int i_dialogsLoadOffsetUserId = 2;
    public static final int i_dialogsLoadOffsetChatId = 3;
    public static final int i_dialogsLoadOffsetChannelId = 4;
    public static final int i_dialogsLoadOffsetAccess = 5;

    public int getTotalDialogsCount(int folderId) {
        return getPreferences().getInt("2totalDialogsLoadCount" + (folderId == 0 ? "" : folderId), 0);
    }

    public void setTotalDialogsCount(int folderId, int totalDialogsLoadCount) {
        getPreferences().edit().putInt("2totalDialogsLoadCount" + (folderId == 0 ? "" : folderId), totalDialogsLoadCount).commit();
    }

    public long[] getDialogLoadOffsets(int folderId) {
        SharedPreferences preferences = getPreferences();
        int dialogsLoadOffsetId = preferences.getInt("2dialogsLoadOffsetId" + (folderId == 0 ? "" : folderId), hasValidDialogLoadIds ? 0 : -1);
        int dialogsLoadOffsetDate = preferences.getInt("2dialogsLoadOffsetDate" + (folderId == 0 ? "" : folderId), hasValidDialogLoadIds ? 0 : -1);
        long dialogsLoadOffsetUserId = AndroidUtilities.getPrefIntOrLong(preferences, "2dialogsLoadOffsetUserId" + (folderId == 0 ? "" : folderId), hasValidDialogLoadIds ? 0 : -1);
        long dialogsLoadOffsetChatId = AndroidUtilities.getPrefIntOrLong(preferences, "2dialogsLoadOffsetChatId" + (folderId == 0 ? "" : folderId), hasValidDialogLoadIds ? 0 : -1);
        long dialogsLoadOffsetChannelId = AndroidUtilities.getPrefIntOrLong(preferences, "2dialogsLoadOffsetChannelId" + (folderId == 0 ? "" : folderId), hasValidDialogLoadIds ? 0 : -1);
        long dialogsLoadOffsetAccess = preferences.getLong("2dialogsLoadOffsetAccess" + (folderId == 0 ? "" : folderId), hasValidDialogLoadIds ? 0 : -1);
        return new long[]{dialogsLoadOffsetId, dialogsLoadOffsetDate, dialogsLoadOffsetUserId, dialogsLoadOffsetChatId, dialogsLoadOffsetChannelId, dialogsLoadOffsetAccess};
    }

    public void setDialogsLoadOffset(int folderId, int dialogsLoadOffsetId, int dialogsLoadOffsetDate, long dialogsLoadOffsetUserId, long dialogsLoadOffsetChatId, long dialogsLoadOffsetChannelId, long dialogsLoadOffsetAccess) {
        SharedPreferences.Editor editor = getPreferences().edit();
        editor.putInt("2dialogsLoadOffsetId" + (folderId == 0 ? "" : folderId), dialogsLoadOffsetId);
        editor.putInt("2dialogsLoadOffsetDate" + (folderId == 0 ? "" : folderId), dialogsLoadOffsetDate);
        editor.putLong("2dialogsLoadOffsetUserId" + (folderId == 0 ? "" : folderId), dialogsLoadOffsetUserId);
        editor.putLong("2dialogsLoadOffsetChatId" + (folderId == 0 ? "" : folderId), dialogsLoadOffsetChatId);
        editor.putLong("2dialogsLoadOffsetChannelId" + (folderId == 0 ? "" : folderId), dialogsLoadOffsetChannelId);
        editor.putLong("2dialogsLoadOffsetAccess" + (folderId == 0 ? "" : folderId), dialogsLoadOffsetAccess);
        editor.putBoolean("hasValidDialogLoadIds", true);
        editor.commit();
    }

    public void setShowCallsTab(boolean show) {
        if (showCallsTab != show) {
            showCallsTab = show;
            saveConfig(false);
        }
    }

    public boolean isPremium() {
        TLRPC.User user = currentUser;
        if (user == null) {
            return false;
        }
        return user.premium;
    }

    public Long getEmojiStatus() {
        return UserObject.getEmojiStatusDocumentId(currentUser);
    }


    int globalTtl = 0;
    boolean ttlIsLoading = false;
    long lastLoadingTime;

    public int getGlobalTTl() {
        return globalTtl;
    }

    public void loadGlobalTTl() {
        if (ttlIsLoading || System.currentTimeMillis() - lastLoadingTime < 60 * 1000) {
            return;
        }
        ttlIsLoading = true;
        TLRPC.TL_messages_getDefaultHistoryTTL getDefaultHistoryTTL = new TLRPC.TL_messages_getDefaultHistoryTTL();
        getConnectionsManager().sendRequest(getDefaultHistoryTTL, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (response != null) {
                globalTtl = ((TLRPC.TL_defaultHistoryTTL) response).period / 60;
                getNotificationCenter().postNotificationName(NotificationCenter.didUpdateGlobalAutoDeleteTimer);
                ttlIsLoading = false;
                lastLoadingTime = System.currentTimeMillis();
            }
        }));

    }

    public void setGlobalTtl(int ttl) {
        globalTtl = ttl;
    }

    public void clearFilters() {
        getPreferences().edit().remove("filtersLoaded").apply();
        filtersLoaded = false;
    }

    public static int getProductionAccount() {
        for (int i = -1; i < MAX_ACCOUNT_COUNT; ++i) {
            final int account = i < 0 ? selectedAccount : i;
            if (getInstance(account).isClientActivated() && !ConnectionsManager.getInstance(account).isTestBackend())
                return account;
        }
        return selectedAccount;
    }
}
