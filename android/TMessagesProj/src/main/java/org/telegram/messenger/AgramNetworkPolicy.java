package org.telegram.messenger;

/** No implicit permission to use a direct transport for an unknown/routed account. */
public final class AgramNetworkPolicy {
    private AgramNetworkPolicy() { }

    public static boolean allowDirect(String mode, boolean storageAccessible, boolean authQuarantined) {
        return storageAccessible && !authQuarantined && "direct".equals(mode);
    }
}
