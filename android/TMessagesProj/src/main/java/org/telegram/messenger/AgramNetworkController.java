/*
 * This file is part of Agram and is licensed under GNU GPL v2 or later.
 */
package org.telegram.messenger;

import android.text.TextUtils;
import org.telegram.tgnet.ConnectionsManager;

/** Applies per-container proxy policy and keeps routed profiles fail-closed. */
public final class AgramNetworkController {
    private static final AgramNetworkController INSTANCE = new AgramNetworkController();
    private final Object stateLock = new Object();
    private final String[] state = new String[UserConfig.MAX_ACCOUNT_COUNT];
    private final boolean[] managedAccounts = new boolean[UserConfig.MAX_ACCOUNT_COUNT];

    public static AgramNetworkController getInstance() {
        return INSTANCE;
    }

    private AgramNetworkController() {
    }

    public void apply(int account) {
        apply(account, true);
        // The push stream is a separate HTTPS connection. Recreate it after
        // every user-visible route change so it cannot keep using the previous
        // direct/SOCKS path while MTProto has already switched routes.
        AgramPushController.getInstance().onNetworkRouteChanged(account);
    }

    public void prepare(int account) {
        apply(account, false);
    }

    private void apply(int account, boolean resume) {
        synchronized (stateLock) {
            managedAccounts[account] = true;
        }
        AgramContainerManager.ProxyProfile proxy = AgramContainerManager.getInstance().getProxyProfile(account);
        if (AgramContainerManager.NETWORK_PROXY.equals(proxy.mode)) {
            if (TextUtils.isEmpty(proxy.address)) {
                ConnectionsManager.native_setProxySettings(account, "", 1080, "", "", "");
                if (proxy.killSwitch) {
                    pause(account, "proxy_required");
                }
                return;
            }
            ConnectionsManager.native_setProxySettings(
                    account, proxy.address, proxy.port, proxy.username, proxy.password, proxy.secret);
            setState(account, "proxy_active");
            if (resume) {
                ConnectionsManager.native_resumeNetwork(account, false);
            }
            return;
        }
        ConnectionsManager.native_setProxySettings(account, "", 1080, "", "", "");
        setState(account, "direct");
        if (resume) {
            ConnectionsManager.native_resumeNetwork(account, false);
        }
    }

    public void onProxyError() {
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            if (!isManaged(account)) {
                continue;
            }
            AgramContainerManager.ContainerRecord record = AgramContainerManager.getInstance().getContainer(account);
            if (record != null && record.killSwitch
                    && !AgramContainerManager.NETWORK_DIRECT.equals(record.proxyMode)) {
                pause(account, "proxy_error");
            }
        }
    }

    public String getState(int account) {
        synchronized (stateLock) {
            String value = state[account];
            return value == null ? "not_applied" : value;
        }
    }

    private void pause(int account, String reason) {
        setState(account, reason);
        ConnectionsManager.native_pauseNetwork(account);
    }

    private boolean isManaged(int account) {
        synchronized (stateLock) {
            return managedAccounts[account];
        }
    }

    private void setState(int account, String value) {
        synchronized (stateLock) {
            state[account] = value;
        }
    }
}
