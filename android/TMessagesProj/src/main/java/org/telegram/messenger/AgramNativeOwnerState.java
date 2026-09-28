/* Agram, GPL v2 or later. */
package org.telegram.messenger;

/** Android-independent state for durable native-owner acknowledgements. No I/O or callbacks. */
public final class AgramNativeOwnerState {
    private String owner = "";
    private String target = "";
    private long transitionId;
    private long generation;
    private boolean ready;
    private boolean pending;

    public synchronized void initialize(String owner, boolean ready) {
        this.owner = owner == null ? "" : owner;
        target = this.owner;
        this.ready = ready && !this.owner.isEmpty();
        pending = false;
        transitionId++;
    }

    public synchronized String owner() {
        return owner;
    }

    public synchronized boolean isReadyFor(String expected) {
        return ready && !pending && !owner.isEmpty() && owner.equals(expected);
    }

    public synchronized long begin(String target, long sessionGeneration) {
        if (target == null) throw new IllegalArgumentException("Native owner target must be explicit");
        this.target = target;
        generation = sessionGeneration;
        ready = false;
        pending = true;
        return ++transitionId;
    }

    /** Stale acknowledgements are ignored; an accepted failure remains blocked with its old owner. */
    public synchronized boolean complete(long id, String stillExpectedTarget, long currentSessionGeneration,
                                         boolean durableSuccess, boolean nativeReady) {
        if (!pending || id != transitionId || !target.equals(stillExpectedTarget)
                || generation != currentSessionGeneration) {
            return false;
        }
        pending = false;
        if (!durableSuccess || (!target.isEmpty() && !nativeReady)) {
            ready = false;
            return false;
        }
        owner = target;
        ready = !owner.isEmpty() && nativeReady;
        return true;
    }

    public synchronized String target() {
        return target;
    }

    public synchronized boolean isPending() {
        return pending;
    }
}
