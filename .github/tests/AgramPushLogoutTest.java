package org.telegram.messenger;

/** Regression for a queued logout racing with slot reuse or another account. */
public final class AgramPushLogoutTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        AgramPushState.Binding old = AgramPushState.bind(0, "old-container", "old-endpoint");
        long oldStream = AgramPushState.beginStream(old);
        AgramPushState.Binding replacement = AgramPushState.bind(0, "new-container", "new-endpoint");
        long newStream = AgramPushState.beginStream(replacement);
        AgramPushState.stream(replacement, newStream, "connected", "");
        AgramPushState.registration(replacement, true, "");
        AgramPushState.clear(0, "old-container");
        AgramPushState.clear(0, null);
        AgramPushState.stream(old, oldStream, "connected", "");
        check("connected".equals(AgramPushState.snapshot(0, "new-container").stream),
                "old logout must not stop replacement stream");
        check("registered".equals(AgramPushState.snapshot(0, "new-container").registration),
                "old logout must not clear replacement registration");

        AgramPushState.Binding other = AgramPushState.bind(1, "other-container", "other-endpoint");
        AgramPushState.registration(other, true, "");
        AgramPushState.clear(0, "new-container");
        AgramPushState.registration(replacement, true, "late callback");
        AgramPushState.stream(replacement, newStream, "connected", "late callback");
        check("unknown".equals(AgramPushState.snapshot(0, "new-container").registration),
                "retired registration callback must not recreate state");
        check("stopped".equals(AgramPushState.snapshot(0, "new-container").stream),
                "retired stream callback must not recreate state");
        check("registered".equals(AgramPushState.snapshot(1, "other-container").registration),
                "logout affects only its bound container");
        System.out.println("PASS AgramPushLogout: bound cleanup, slot reuse, stale callbacks, account isolation");
    }
}
