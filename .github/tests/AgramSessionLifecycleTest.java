package org.telegram.messenger;

import java.util.HashMap;
import java.util.Map;

/** Runs the production callback/authorization policy and durability primitive without Android. */
public final class AgramSessionLifecycleTest {
    public static void main(String[] args) {
        AgramSessionLifecycle slot = new AgramSessionLifecycle();
        long oldCallback = slot.generation();
        check(slot.isCurrent(oldCallback), "current callback");
        slot.advance();
        check(!slot.isCurrent(oldCallback), "logout must fence queued account writes");
        long loggedOutCallback = slot.generation();
        slot.advance();
        check(!slot.isCurrent(loggedOutCallback), "new login must fence logout callbacks");

        check(!AgramSessionLifecycle.canInstallAuthorization(false, true, 2, 2),
                "inactive startup with persisted user cannot be replaced");
        check(!AgramSessionLifecycle.canInstallAuthorization(true, false, 2, 2), "active runtime protected");
        check(!AgramSessionLifecycle.canInstallAuthorization(false, false, 1, 2), "stale authorization rejected");
        check(AgramSessionLifecycle.canInstallAuthorization(false, false, 2, 2), "fresh empty slot accepted");
        check(!AgramSessionLifecycle.contactSyncAllowed(false, true), "legacy default is not consent");
        check(!AgramSessionLifecycle.contactSyncAllowed(true, false), "explicit opt-out");
        check(AgramSessionLifecycle.contactSyncAllowed(true, true), "explicit opt-in");

        Map<String, String> live = new HashMap<>();
        live.put("slot", "old-container");
        live.put("user", "old-user");
        Map<String, String> before = new HashMap<>(live);
        boolean committed = AgramSessionLifecycle.commitPreserving(() -> {
            live.clear(); // SharedPreferences changes memory before reporting disk failure.
            live.put("slot", "replacement");
            return false;
        }, () -> { live.clear(); live.putAll(before); });
        check(!committed && live.equals(before), "failed disk write restores prior identity");
        try {
            AgramSessionLifecycle.commitPreserving(() -> {
                live.clear();
                throw new IllegalStateException("injected storage failure");
            }, () -> { live.clear(); live.putAll(before); });
            throw new AssertionError("exception expected");
        } catch (IllegalStateException expected) {
            check(live.equals(before), "exception also restores prior identity");
        }
        int[] rollbacks = {0};
        check(AgramSessionLifecycle.commitPreserving(() -> true, () -> rollbacks[0]++), "successful durable write");
        check(rollbacks[0] == 0, "success does not restore obsolete state");
        System.out.println("AgramSessionLifecycleTest: passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
