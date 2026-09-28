/* Agram, GPL v2 or later. Pure Java ownership rules for mixed account-0 preferences. */
package org.telegram.messenger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Account zero shares its preference file with app-wide passcode/settings state. */
public final class AgramAccountPreferenceKeys {
    private static final Set<String> ACCOUNT_KEYS = new HashSet<>(Arrays.asList(
            "user", "tmpPassword", "terms", "agram_contacts_choice_v1", "syncContacts",
            "registeredForPush", "lastSendMessageId", "contactsSavedCount", "lastBroadcastId",
            "lastContactsSyncTime", "lastHintsSyncTime", "draftsLoaded", "unreadDialogsLoaded",
            "ratingLoadTime", "botRatingLoadTime", "botGuestRatingLoadTime", "webappRatingLoadTime",
            "contactsReimported", "loginTime", "showCallsTab", "suggestContacts", "hasSecureData",
            "notificationsSettingsLoaded4", "notificationsSignUpSettingsLoaded", "autoDownloadConfigLoadTime",
            "hasValidDialogLoadIds", "sharingMyLocationUntil", "lastMyLocationShareTime", "filtersLoaded",
            "premiumGiftsStickerPack", "lastUpdatedPremiumGiftsStickerPack",
            "genericAnimationsStickerPack", "lastUpdatedGenericAnimations",
            "6migrateOffsetId", "6migrateOffsetDate", "6migrateOffsetUserId", "6migrateOffsetChatId",
            "6migrateOffsetChannelId", "6migrateOffsetAccess"
    ));
    private static final String[] FOLDER_KEYS = {
            "2pinnedDialogsLoaded", "2totalDialogsLoadCount", "2dialogsLoadOffsetId", "2dialogsLoadOffsetDate",
            "2dialogsLoadOffsetUserId", "2dialogsLoadOffsetChatId", "2dialogsLoadOffsetChannelId", "2dialogsLoadOffsetAccess"
    };

    private AgramAccountPreferenceKeys() { }

    public static boolean isAccountOwned(String key) {
        if (ACCOUNT_KEYS.contains(key)) return true;
        if (key == null) return false;
        for (String prefix : FOLDER_KEYS) {
            if (!key.startsWith(prefix)) continue;
            boolean numericSuffix = true;
            for (int i = prefix.length(); i < key.length(); i++) {
                char value = key.charAt(i);
                if (value < '0' || value > '9') { numericSuffix = false; break; }
            }
            if (numericSuffix) return true;
        }
        return false;
    }

    public static String[] removalKeys(Set<String> existing) {
        ArrayList<String> keys = new ArrayList<>();
        for (String key : existing) if (isAccountOwned(key)) keys.add(key);
        return keys.toArray(new String[0]);
    }
}
