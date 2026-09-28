package org.telegram.messenger;

public final class AgramNativeOwnerStateTest {
    private static int assertions;

    private static void require(boolean value, String description) {
        assertions++;
        if (!value) throw new AssertionError(description);
    }

    public static void main(String[] args) {
        AgramNativeOwnerState state = new AgramNativeOwnerState();
        require(!state.isReadyFor("") && !state.isPending(), "uninitialized state is blocked");
        state.initialize("a", true);
        require(state.isReadyFor("a") && !state.isReadyFor("b"), "exact startup owner only");
        state.initialize("a", false);
        require(!state.isReadyFor("a") && state.owner().equals("a"), "active/inactive startup mismatch preserves owner blocked");
        state.initialize("a", true);
        long first = state.begin("b", 7);
        require(state.isPending() && !state.isReadyFor("a") && state.owner().equals("a") && state.target().equals("b"), "begin blocks without prematurely changing owner");
        require(!state.complete(first - 1, "b", 7, true, true) && state.isPending(), "stale id is ignored");
        require(!state.complete(first, "c", 7, true, true) && state.isPending(), "changed target is ignored");
        require(!state.complete(first, "b", 8, true, true) && state.isPending(), "replacement session generation is ignored");
        require(state.complete(first, "b", 7, true, true), "durable native-ready success accepted");
        require(state.isReadyFor("b") && !state.isPending() && state.owner().equals("b"), "success changes owner");
        require(!state.complete(first, "b", 7, true, true), "duplicate acknowledgement ignored");
        long failed = state.begin("c", 7);
        require(!state.complete(failed, "c", 7, false, true), "failed persistence rejected");
        require(state.owner().equals("b") && !state.isPending() && !state.isReadyFor("b"), "disk-full failure preserves old owner but blocks transport");
        long unready = state.begin("c", 7);
        require(!state.complete(unready, "c", 7, true, false) && state.owner().equals("b"), "nonempty owner needs native readiness");
        long retirement = state.begin("", 7);
        require(state.complete(retirement, "", 7, true, false), "durable empty retirement accepted without native readiness");
        require(state.owner().isEmpty() && !state.isReadyFor("") && !state.isPending(), "retired transport stays blocked");
        long stale = state.begin("a", 8);
        long latest = state.begin("b", 9);
        require(!state.complete(stale, "a", 8, true, true) && state.isPending(), "new transition invalidates old callback");
        require(state.complete(latest, "b", 9, true, true) && state.isReadyFor("b"), "latest transition succeeds");
        long preRestart = state.begin("c", 9);
        state.initialize("b", false);
        require(!state.complete(preRestart, "c", 9, true, true) && !state.isReadyFor("b"), "restart with pending durable marker remains blocked");
        state.initialize("b", true);
        require(state.isReadyFor("b"), "verified same-owner restart preserves active session");
        state.initialize(null, true);
        require(!state.isReadyFor("") && state.owner().isEmpty(), "unknown startup owner cannot become ready");
        try {
            state.begin(null, 10);
            throw new AssertionError("null target silently retired owner");
        } catch (IllegalArgumentException expected) {
            assertions++;
        }
        System.out.println("AgramNativeOwnerStateTest: " + assertions + " assertions passed");
    }
}
