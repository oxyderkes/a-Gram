package org.telegram.messenger;

/** Privacy boundaries: automatic ordinary receipts versus explicit and protected reads. */
public final class AgramGhostReadPolicyTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        check(AgramGhostReadPolicy.suppressContentRead(true, false, 0, 0),
                "ordinary incoming voice/media content remains unread in Ghost Mode");
        check(!AgramGhostReadPolicy.suppressContentRead(false, false, 0, 0),
                "normal receipt behavior resumes when Ghost read suppression is off");
        check(!AgramGhostReadPolicy.suppressContentRead(true, true, 0, 0),
                "secret chats retain their existing read protocol");
        for (int ttl : new int[] {1, 60, Integer.MAX_VALUE}) {
            check(!AgramGhostReadPolicy.suppressContentRead(true, false, ttl, 0),
                    "message TTL/view-once lifecycle must run: " + ttl);
            check(!AgramGhostReadPolicy.suppressContentRead(true, false, 0, ttl),
                    "media TTL/view-once lifecycle must run: " + ttl);
        }
        check(AgramGhostReadPolicy.suppressAutomaticRead(true, false, false),
                "automatic mentions/reactions/poll reads are suppressed");
        check(!AgramGhostReadPolicy.suppressAutomaticRead(true, false, true),
                "explicit mark-as-read/read-on-interaction overrides suppression");
        check(!AgramGhostReadPolicy.suppressAutomaticRead(true, true, false),
                "automatic secret chat reads retain Telegram semantics");
        check(!AgramGhostReadPolicy.suppressAutomaticRead(false, false, false),
                "master/options disabled must not suppress automatic reads");
        System.out.println("PASS AgramGhostReadPolicy: ordinary reads, explicit overrides, secret/TTL/view-once exclusions");
    }
}
