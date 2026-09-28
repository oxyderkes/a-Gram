/*
 * This file is part of Agram and is licensed under GNU GPL v2 or later.
 */
package org.telegram.messenger;

import android.text.TextUtils;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.messenger.voip.VoIPService;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

/** Applies per-container proxy policy and keeps routed profiles fail-closed. */
public final class AgramNetworkController {
    private static final AgramNetworkController INSTANCE = new AgramNetworkController();
    private final Object stateLock = new Object();
    private final String[] state = new String[UserConfig.MAX_ACCOUNT_COUNT];
    private final boolean[] managedAccounts = new boolean[UserConfig.MAX_ACCOUNT_COUNT];
    private final Map<AgramDirectHttpConnection, DirectBinding> directRequests = new WeakHashMap<>();

    public static AgramNetworkController getInstance() {
        return INSTANCE;
    }

    private AgramNetworkController() {
    }

    public static boolean isDirectNetworkAllowed(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) return false;
        try {
            AgramContainerManager.ContainerRecord record = AgramContainerManager.getInstance().getContainer(account);
            return record != null && record.isStorageAccessible()
                    && AgramNetworkPolicy.allowDirect(record.proxyMode, true,
                    !ConnectionsManager.isAgramAuthTransportReady(account));
        } catch (RuntimeException unavailableMetadata) {
            return false;
        }
    }

    /** Unadapted HTTP transports fail closed instead of bypassing a selected proxy. */
    public static URLConnection openDirectHttpConnection(int account, URL url) throws IOException {
        if (!isDirectNetworkAllowed(account)) throw new IOException("Container route blocks direct HTTP");
        AgramContainerManager.ContainerRecord record = AgramContainerManager.getInstance().getContainer(account);
        if (record == null) throw new IOException("Container unavailable");
        return openDirectHttpConnection(account, url, record.id,
                UserConfig.getInstance(account).getSessionGeneration());
    }

    public static URLConnection openDirectHttpConnection(int account, URL url,
                                                         String expectedContainerId, long expectedGeneration) throws IOException {
        DirectBinding binding = new DirectBinding(account, expectedContainerId, expectedGeneration);
        if (!isDirectHttpOwnerCurrent(account, expectedContainerId, expectedGeneration)) {
            throw new IOException("Container route blocks direct HTTP");
        }
        URLConnection raw = url.openConnection();
        if (!(raw instanceof HttpURLConnection)) throw new IOException("Unsupported direct HTTP transport");
        AgramDirectHttpConnection connection = new AgramDirectHttpConnection((HttpURLConnection) raw,
                () -> isDirectHttpOwnerCurrent(account, expectedContainerId, expectedGeneration));
        synchronized (INSTANCE.stateLock) {
            INSTANCE.directRequests.put(connection, binding);
        }
        checkDirectHttpConnection(account, connection);
        return connection;
    }

    public static boolean isDirectHttpOwnerCurrent(int account, String expectedContainerId, long expectedGeneration) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) return false;
        try {
            AgramContainerManager.ContainerRecord record = AgramContainerManager.getInstance().getContainer(account);
            return record != null && record.id.equals(expectedContainerId)
                    && UserConfig.getInstance(account).isSessionGenerationCurrent(expectedGeneration)
                    && isDirectNetworkAllowed(account);
        } catch (RuntimeException unavailableMetadata) {
            return false;
        }
    }

    public static void checkDirectHttpConnection(int account, URLConnection connection) throws IOException {
        DirectBinding binding;
        synchronized (INSTANCE.stateLock) { binding = INSTANCE.directRequests.get(connection); }
        if (!(connection instanceof AgramDirectHttpConnection) || binding == null || binding.account != account) {
            if (connection instanceof HttpURLConnection) ((HttpURLConnection) connection).disconnect();
            throw new IOException("Container route or owner changed");
        }
        ((AgramDirectHttpConnection) connection).checkValid();
    }

    private void closeDirectHttpConnections(int account) {
        ArrayList<AgramDirectHttpConnection> revoked = new ArrayList<>();
        synchronized (stateLock) {
            Iterator<Map.Entry<AgramDirectHttpConnection, DirectBinding>> entries = directRequests.entrySet().iterator();
            while (entries.hasNext()) {
                Map.Entry<AgramDirectHttpConnection, DirectBinding> entry = entries.next();
                if (entry.getValue().account == account) {
                    AgramDirectHttpConnection connection = entry.getKey();
                    entries.remove();
                    if (connection != null) {
                        connection.revoke();
                        revoked.add(connection);
                    }
                }
            }
        }
        // Platform disconnect can block; never perform it under the registry lock.
        for (AgramDirectHttpConnection connection : revoked) connection.invalidate();
    }

    private static final class DirectBinding {
        final int account;
        final String containerId;
        final long sessionGeneration;
        DirectBinding(int account, String containerId, long sessionGeneration) {
            this.account = account; this.containerId = containerId; this.sessionGeneration = sessionGeneration;
        }
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
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) return;
        synchronized (stateLock) {
            managedAccounts[account] = true;
        }
        AgramContainerManager.ContainerRecord record = AgramContainerManager.getInstance().getContainer(account);
        if (record == null || !record.isStorageAccessible()
                || !AgramContainerManager.NETWORK_DIRECT.equals(record.proxyMode)) {
            closeDirectHttpConnections(account);
            VoIPService.onAgramNetworkRouteChanged(account);
            org.telegram.ui.Stories.LivePlayer.onAgramNetworkRouteChanged(account);
        }
        if (record == null) {
            // Constructor initialization creates a container before prepare().
            // A retired slot must not be recreated by getProxyProfile().
            pause(account, "container_unavailable");
            return;
        }
        if (!record.isStorageAccessible()) {
            pause(account, "local_storage_unavailable");
            return;
        }
        if (resume && ConnectionsManager.getInstance(account).isLocalAuthConfigQuarantined()) {
            pause(account, "local_auth_unavailable");
            return;
        }
        // Use the record already read above; getProxyProfile() internally calls
        // ensureContainer() and could recreate a slot deleted during this call.
        if (AgramContainerManager.NETWORK_PROXY.equals(record.proxyMode)) {
            if (TextUtils.isEmpty(record.proxyAddress)) {
                // An incomplete selected proxy is never permission to connect directly.
                pause(account, "proxy_required");
                return;
            }
            ConnectionsManager.native_setProxySettings(
                    account, record.proxyAddress, record.proxyPort > 0 ? record.proxyPort : 1080,
                    safe(record.proxyUsername), safe(record.proxyPassword), safe(record.proxySecret));
            setState(account, "proxy_active");
            if (resume) {
                ConnectionsManager.native_resumeNetwork(account, false);
            }
            return;
        }
        if (!AgramContainerManager.NETWORK_DIRECT.equals(record.proxyMode)) {
            pause(account, "route_unavailable");
            return;
        }
        ConnectionsManager.native_setProxySettings(account, "", 1080, "", "", "");
        setState(account, "direct");
        if (resume) {
            ConnectionsManager.native_resumeNetwork(account, false);
        }
    }

    public void onProxyError(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT || !isManaged(account)) return;
        AgramContainerManager.ContainerRecord record = AgramContainerManager.getInstance().getContainer(account);
        if (record != null && !AgramContainerManager.NETWORK_DIRECT.equals(record.proxyMode)) {
            if (record.killSwitch) pause(account, "proxy_error");
            else setState(account, "proxy_reconnecting");
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public String getState(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) return "invalid_account";
        synchronized (stateLock) {
            String value = state[account];
            return value == null ? "not_applied" : value;
        }
    }

    private void pause(int account, String reason) {
        setState(account, reason);
        closeDirectHttpConnections(account);
        VoIPService.onAgramNetworkRouteChanged(account);
        org.telegram.ui.Stories.LivePlayer.onAgramNetworkRouteChanged(account);
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
