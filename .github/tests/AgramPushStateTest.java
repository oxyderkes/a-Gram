package org.telegram.messenger;

/** Executes production state transitions, without Android mocks or a live account. */
public final class AgramPushStateTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        AgramPushState.Binding first = AgramPushState.bind(0, "container-a", "endpoint-a");
        long stream1 = AgramPushState.beginStream(first);
        check(AgramPushState.beginRegistration(first, 100_000), "registration starts");
        check(!AgramPushState.beginRegistration(first, 100_001), "duplicate request suppressed");
        AgramPushState.registration(first, false, "Telegram: ошибка 500");
        AgramPushState.stream(first, stream1, "connected", "");
        AgramPushState.Snapshot status = AgramPushState.snapshot(0, "container-a");
        check("connected".equals(status.stream) && "error".equals(status.registration),
                "live stream must not erase registration failure");
        check(!AgramPushState.beginRegistration(first, 110_000), "error backoff");
        check(AgramPushState.beginRegistration(first, 131_000), "retry allowed after backoff");
        AgramPushState.registration(first, true, "");
        AgramPushState.stream(first, stream1, "reconnecting", "timeout");
        status = AgramPushState.snapshot(0, "container-a");
        check("reconnecting".equals(status.stream) && "registered".equals(status.registration),
                "registration must not hide broken stream");
        long stream2 = AgramPushState.beginStream(first);
        AgramPushState.stream(first, stream1, "connected", "late old response");
        check("connecting".equals(AgramPushState.snapshot(0, "container-a").stream), "stale socket ignored");
        AgramPushState.stopStream(first, stream1);
        check("connecting".equals(AgramPushState.snapshot(0, "container-a").stream), "stale stop ignored");
        AgramPushState.stopStream(first, stream2);
        AgramPushState.stream(first, stream2, "connected", "late callback");
        check("stopped".equals(AgramPushState.snapshot(0, "container-a").stream), "stopped socket cannot revive");

        AgramPushState.Binding second = AgramPushState.bind(0, "container-b", "endpoint-b");
        AgramPushState.registration(first, true, "");
        AgramPushState.stream(first, stream1, "connected", "");
        check("unknown".equals(AgramPushState.snapshot(0, "container-b").registration), "slot reuse rejects old registration");
        check("stopped".equals(AgramPushState.snapshot(0, "container-a").stream), "old container cannot observe new status");

        Thread[] accounts = new Thread[31];
        for (int i = 1; i < 32; i++) {
            final int account = i;
            accounts[i - 1] = new Thread(() -> {
                AgramPushState.Binding binding = AgramPushState.bind(account, "container-" + account, "endpoint-" + account);
                long revision = AgramPushState.beginStream(binding);
                AgramPushState.stream(binding, revision, "connected", "");
                AgramPushState.registration(binding, true, "");
            });
            accounts[i - 1].start();
        }
        for (Thread thread : accounts) thread.join();
        for (int i = 1; i < 32; i++) {
            check("connected".equals(AgramPushState.snapshot(i, "container-" + i).stream), "independent account " + i);
        }
        check("stopped".equals(AgramPushState.snapshot(0, "container-b").stream), "other accounts do not mutate account zero");
        AgramPushState.clear(0);
        AgramPushState.registration(second, true, "");
        check("unknown".equals(AgramPushState.snapshot(0, "container-b").registration), "logout invalidates callbacks");
        boolean rejected = false;
        try { AgramPushState.bind(32, "invalid", "invalid"); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "bounded slots");
        System.out.println("PASS AgramPushState: independent status, retries, stale callbacks, logout, 32 accounts");
    }
}
