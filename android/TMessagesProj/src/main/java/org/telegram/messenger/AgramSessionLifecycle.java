/* This file is part of Agram and is licensed under GNU GPL v2 or later. */
package org.telegram.messenger;

import java.util.function.BooleanSupplier;

/** Android-independent durability and callback fencing used by account storage. */
public final class AgramSessionLifecycle {
    private long generation;

    public synchronized long generation() {
        return generation;
    }

    public synchronized boolean isCurrent(long expected) {
        return generation == expected;
    }

    public synchronized void advance() {
        generation++;
    }

    /** A failed write can already have changed an in-memory preference map. */
    public static boolean commitPreserving(BooleanSupplier write, Runnable restore) {
        boolean committed = false;
        try {
            committed = write.getAsBoolean();
            return committed;
        } finally {
            if (!committed) {
                restore.run();
            }
        }
    }

    public static boolean contactSyncAllowed(boolean explicitChoice, boolean enabled) {
        return explicitChoice && enabled;
    }

    /** Never interpret an inactive/unloaded runtime slot as permission to overwrite disk. */
    public static boolean canInstallAuthorization(boolean runtimeActive, boolean persistedUser,
                                                   long expectedGeneration, long generation) {
        return !runtimeActive && !persistedUser && expectedGeneration == generation;
    }
}
