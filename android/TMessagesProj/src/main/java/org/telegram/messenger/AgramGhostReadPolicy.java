package org.telegram.messenger;

/** Read receipt policy shared by the controller and the visible-message paths. */
final class AgramGhostReadPolicy {
    private AgramGhostReadPolicy() {
    }

    static boolean suppressAutomaticRead(boolean suppressReads, boolean secretChat, boolean explicit) {
        return suppressReads && !secretChat && !explicit;
    }

    static boolean suppressContentRead(boolean suppressReads, boolean secretChat,
                                       int messageTtl, int mediaTtl) {
        // Expiring/view-once content must keep Telegram's read and destruction lifecycle.
        return suppressAutomaticRead(suppressReads, secretChat, false)
                && messageTtl == 0 && mediaTtl == 0;
    }
}
