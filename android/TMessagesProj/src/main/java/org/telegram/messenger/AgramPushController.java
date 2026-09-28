/*
 * This file is part of Agram and is licensed under GNU GPL v2 or later.
 */
package org.telegram.messenger;

import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.util.Base64;
import android.util.SparseArray;

import androidx.core.content.ContextCompat;

import org.json.JSONObject;
import org.telegram.tgnet.ConnectionsManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

import javax.net.ssl.HttpsURLConnection;

/**
 * Owns the built-in Simple Push transport. The Android service is shared only
 * for lifecycle management; each subscription is permanently bound to one
 * immutable container id, account slot and random endpoint.
 */
public final class AgramPushController {

    private static final AgramPushController INSTANCE = new AgramPushController();
    private static final String TOPIC_PREFIX = "agram_";
    private static final long INITIAL_RECONNECT_DELAY_MS = 2_000L;
    private static final long MAX_RECONNECT_DELAY_MS = 60_000L;
    private static final Object PROXY_AUTH_LOCK = new Object();

    private final Object sync = new Object();
    private final SparseArray<Subscription> subscriptions = new SparseArray<>();
    private final SecureRandom secureRandom = new SecureRandom();
    private boolean serviceRunning;

    public static AgramPushController getInstance() {
        return INSTANCE;
    }

    private AgramPushController() {
    }

    public void restoreActiveRegistrations() {
        if (hasActiveAgramPushAccounts()) {
            requestServiceStart();
        } else {
            requestServiceStop();
        }
    }

    public void onAccountAuthorized(int account) {
        AgramContainerManager.ContainerRecord record = AgramContainerManager.getInstance().getContainer(account);
        if (record != null && record.isStorageAccessible()
                && UserConfig.getInstance(account).isClientActivated()
                && AgramContainerManager.PUSH_AGRAM.equals(record.pushMode)) {
            requestServiceStart();
            refreshSubscriptions();
        }
    }

    public void onPushSettingsChanged(int account) {
        AgramContainerManager.ContainerRecord record = AgramContainerManager.getInstance().getContainer(account);
        if (record != null && record.isStorageAccessible()
                && AgramContainerManager.PUSH_AGRAM.equals(record.pushMode)
                && UserConfig.getInstance(account).isClientActivated()) {
            requestServiceStart();
        }
        refreshSubscriptions();
    }

    /** Reconnects the selected subscription after its direct/proxy route changes. */
    public void onNetworkRouteChanged(int account) {
        synchronized (sync) {
            stopSubscriptionLocked(account);
        }
        refreshSubscriptions();
    }

    public void unregisterAccount(int account, boolean notifyTelegram) {
        AgramContainerManager.ContainerRecord record = AgramContainerManager.getInstance().getContainer(account);
        unregisterAccount(account, record == null ? null : record.id, notifyTelegram);
    }

    public void unregisterAccount(int account, String expectedContainerId, boolean notifyTelegram) {
        AgramContainerManager manager = AgramContainerManager.getInstance();
        synchronized (sync) {
            Subscription subscription = subscriptions.get(account);
            if (subscription != null && subscription.containerId.equals(expectedContainerId)) {
                stopSubscriptionLocked(account);
            }
        }
        AgramPushState.clear(account, expectedContainerId);
        AgramContainerManager.ContainerRecord record = manager.getContainer(account);
        if (record != null && record.id.equals(expectedContainerId)) {
            try {
                manager.runBoundSettingsUpdate(account, expectedContainerId, () -> {
                    // A durable logout has already retired UserConfig. A server
                    // logout revokes its endpoint; never initialize a new slot.
                    if (notifyTelegram && UserConfig.getInstance(account).isClientActivated()
                            && !TextUtils.isEmpty(record.agramPushEndpoint)) {
                        MessagesController.getInstance(account).unregisterAgramPush(record.agramPushEndpoint);
                    }
                    manager.saveAgramPushEndpoint(account, "", "unregistered");
                });
            } catch (RuntimeException error) {
                // Stopping the socket and continuing logout must not depend on
                // a metadata write succeeding on a full/unavailable disk.
                FileLog.e("Agram Push stopped; endpoint cleanup could not be persisted");
            }
        }
        AgramPushService.updateForegroundNotification(subscriptionCount());
        AndroidUtilities.runOnUIThread(this::restoreActiveRegistrations);
    }

    void onServiceStarted() {
        synchronized (sync) {
            serviceRunning = true;
        }
        refreshSubscriptions();
    }

    void onServiceStopped() {
        synchronized (sync) {
            serviceRunning = false;
            for (int index = subscriptions.size() - 1; index >= 0; index--) {
                subscriptions.valueAt(index).stop();
            }
            subscriptions.clear();
        }
    }

    private void refreshSubscriptions() {
        synchronized (sync) {
            if (!serviceRunning) {
                return;
            }
            Set<Integer> requiredAccounts = new HashSet<>();
            for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
                AgramContainerManager.ContainerRecord record = AgramContainerManager.getInstance().getContainer(account);
                if (record == null
                        || !record.isStorageAccessible()
                        || !UserConfig.getInstance(account).isClientActivated()
                        || !AgramContainerManager.PUSH_AGRAM.equals(record.pushMode)) {
                    continue;
                }
                requiredAccounts.add(account);
                String endpoint;
                try {
                    endpoint = ensureEmbeddedEndpoint(account, record);
                } catch (RuntimeException error) {
                    stopSubscriptionLocked(account);
                    AgramPushState.Binding failed = AgramPushState.bind(account, record.id, "");
                    long revision = AgramPushState.beginStream(failed);
                    AgramPushState.stream(failed, revision, "configuration_error",
                            "Не удалось подготовить Push. Проверьте настройки и свободное место.");
                    continue;
                }
                AgramContainerManager.ContainerRecord current = AgramContainerManager.getInstance().getContainer(account);
                if (current == null || !current.isStorageAccessible()
                        || !record.id.equals(current.id) || !endpoint.equals(current.agramPushEndpoint)
                        || !AgramContainerManager.PUSH_AGRAM.equals(current.pushMode)
                        || !UserConfig.getInstance(account).isClientActivated()) {
                    stopSubscriptionLocked(account);
                    continue;
                }
                Subscription existing = subscriptions.get(account);
                if (existing == null || !existing.matches(current.id, endpoint)) {
                    stopSubscriptionLocked(account);
                    Subscription subscription = new Subscription(account, current.id, endpoint);
                    subscriptions.put(account, subscription);
                    subscription.start();
                }
                registerEndpointWithTelegram(account, current.id, endpoint,
                        UserConfig.getInstance(account).getSessionGeneration());
            }
            for (int index = subscriptions.size() - 1; index >= 0; index--) {
                int account = subscriptions.keyAt(index);
                if (!requiredAccounts.contains(account)) {
                    subscriptions.valueAt(index).stop();
                    subscriptions.removeAt(index);
                }
            }
            AgramPushService.updateForegroundNotification(subscriptions.size());
            if (subscriptions.size() == 0) {
                requestServiceStop();
            }
        }
    }

    private String ensureEmbeddedEndpoint(int account, AgramContainerManager.ContainerRecord record) {
        String baseUrl = pushBaseUrl();
        if (isEmbeddedEndpoint(record.agramPushEndpoint, baseUrl)) {
            return record.agramPushEndpoint;
        }
        String legacyEndpoint = record.agramPushEndpoint;
        byte[] random = new byte[32];
        secureRandom.nextBytes(random);
        String topic = TOPIC_PREFIX + Base64.encodeToString(
                random, Base64.NO_WRAP | Base64.NO_PADDING | Base64.URL_SAFE);
        String endpoint = baseUrl + "/" + topic;
        AgramContainerManager manager = AgramContainerManager.getInstance();
        manager.runBoundSettingsUpdate(account, record.id, () -> {
            if (!UserConfig.getInstance(account).isClientActivated()) {
                throw new IllegalStateException("Agram Push session was retired");
            }
            manager.saveAgramPushEndpoint(account, endpoint, "starting");
            if (!TextUtils.isEmpty(legacyEndpoint)) {
                MessagesController.getInstance(account).unregisterAgramPush(legacyEndpoint);
            }
        });
        return endpoint;
    }

    private void registerEndpointWithTelegram(int account, String containerId, String endpoint, long sessionGeneration) {
        AgramContainerManager.ContainerRecord current = AgramContainerManager.getInstance().getContainer(account);
        if (current != null && current.isStorageAccessible() && containerId.equals(current.id)
                && UserConfig.getInstance(account).isSessionGenerationCurrent(sessionGeneration)
                && UserConfig.getInstance(account).isClientActivated() && !TextUtils.isEmpty(endpoint)) {
            MessagesController.getInstance(account).registerAgramPush(endpoint);
        }
    }

    private void onMessage(int account, String containerId, String endpoint, long sessionGeneration) {
        AgramContainerManager.ContainerRecord current = AgramContainerManager.getInstance().getContainer(account);
        if (current == null
                || !current.isStorageAccessible()
                || !containerId.equals(current.id)
                || !endpoint.equals(current.agramPushEndpoint)
                || !AgramContainerManager.PUSH_AGRAM.equals(current.pushMode)
                || !UserConfig.getInstance(account).isSessionGenerationCurrent(sessionGeneration)
                || !UserConfig.getInstance(account).isClientActivated()) {
            return;
        }
        ApplicationLoader.postInitApplication();
        ConnectionsManager.onInternalPushReceived(account);
        ConnectionsManager.getInstance(account).resumeNetworkMaybe();
    }

    private void stopSubscriptionLocked(int account) {
        Subscription subscription = subscriptions.get(account);
        if (subscription != null) {
            subscription.stop();
            subscriptions.remove(account);
        }
    }

    private int subscriptionCount() {
        synchronized (sync) {
            return subscriptions.size();
        }
    }

    private boolean hasActiveAgramPushAccounts() {
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            AgramContainerManager.ContainerRecord record = AgramContainerManager.getInstance().getContainer(account);
            if (record != null
                    && record.isStorageAccessible()
                    && UserConfig.getInstance(account).isClientActivated()
                    && AgramContainerManager.PUSH_AGRAM.equals(record.pushMode)) {
                return true;
            }
        }
        return false;
    }

    private void requestServiceStart() {
        Context context = ApplicationLoader.applicationContext;
        try {
            ContextCompat.startForegroundService(context, new Intent(context, AgramPushService.class));
        } catch (Throwable error) {
            FileLog.e("Unable to start Agram Push service", error);
        }
    }

    private void requestServiceStop() {
        Context context = ApplicationLoader.applicationContext;
        try {
            context.stopService(new Intent(context, AgramPushService.class));
        } catch (Throwable error) {
            FileLog.e("Unable to stop Agram Push service", error);
        }
    }

    private static String pushBaseUrl() {
        String configured = BuildConfig.AGRAM_PUSH_BASE_URL == null
                ? "" : BuildConfig.AGRAM_PUSH_BASE_URL.trim();
        while (configured.endsWith("/")) {
            configured = configured.substring(0, configured.length() - 1);
        }
        try {
            URL url = new URL(configured);
            if (!"https".equals(url.getProtocol()) || TextUtils.isEmpty(url.getHost())
                    || url.getUserInfo() != null || url.getQuery() != null || url.getRef() != null) {
                throw new IllegalArgumentException("Invalid HTTPS push relay configuration");
            }
        } catch (java.net.MalformedURLException error) {
            throw new IllegalArgumentException("Invalid HTTPS push relay configuration", error);
        }
        return configured;
    }

    /** Relay host only; never expose the capability URL/topic in UI or logs. */
    public String relayHost() {
        try {
            return new URL(pushBaseUrl()).getHost();
        } catch (Exception error) {
            return "не настроен";
        }
    }

    private static boolean isEmbeddedEndpoint(String endpoint, String baseUrl) {
        return !TextUtils.isEmpty(endpoint) && endpoint.startsWith(baseUrl + "/" + TOPIC_PREFIX);
    }

    private final class Subscription implements Runnable {
        private final int account;
        private final String containerId;
        private final String endpoint;
        private final String topic;
        private final long sessionGeneration;
        private final AgramPushState.Binding statusBinding;
        private long statusRevision;
        private volatile boolean stopped;
        private volatile HttpsURLConnection connection;
        private Thread thread;
        private String lastMessageId;

        Subscription(int account, String containerId, String endpoint) {
            this.account = account;
            this.containerId = containerId;
            this.endpoint = endpoint;
            this.topic = endpoint.substring(endpoint.lastIndexOf('/') + 1);
            sessionGeneration = UserConfig.getInstance(account).getSessionGeneration();
            statusBinding = AgramPushState.bind(account, containerId, endpoint);
        }

        boolean matches(String expectedContainerId, String expectedEndpoint) {
            return !stopped && containerId.equals(expectedContainerId) && endpoint.equals(expectedEndpoint)
                    && UserConfig.getInstance(account).isSessionGenerationCurrent(sessionGeneration);
        }

        void start() {
            statusRevision = AgramPushState.beginStream(statusBinding);
            thread = new Thread(this, "AgramPush-" + account);
            thread.setDaemon(true);
            thread.start();
        }

        void stop() {
            stopped = true;
            AgramPushState.stopStream(statusBinding, statusRevision);
            HttpsURLConnection activeConnection = connection;
            if (activeConnection != null) {
                try {
                    activeConnection.disconnect();
                } catch (RuntimeException error) {
                    FileLog.e("Agram Push socket disconnect failed");
                }
            }
            Thread activeThread = thread;
            if (activeThread != null) {
                try {
                    activeThread.interrupt();
                } catch (RuntimeException error) {
                    FileLog.e("Agram Push worker interrupt failed");
                }
            }
        }

        @Override
        public void run() {
            long reconnectDelay = INITIAL_RECONNECT_DELAY_MS;
            while (!stopped && isCurrentBinding()) {
                try {
                    AgramPushState.stream(statusBinding, statusRevision, "connecting", "");
                    readStream();
                    reconnectDelay = INITIAL_RECONNECT_DELAY_MS;
                } catch (Throwable error) {
                    if (!stopped) {
                        // Exception messages may include the endpoint capability or proxy credentials.
                        FileLog.e("Agram Push connection failed: " + error.getClass().getSimpleName());
                        AgramPushState.stream(statusBinding, statusRevision, "reconnecting",
                                safeConnectionError(error));
                    }
                } finally {
                    HttpsURLConnection activeConnection = connection;
                    connection = null;
                    if (activeConnection != null) {
                        activeConnection.disconnect();
                    }
                }
                if (!stopped) {
                    try {
                        Thread.sleep(reconnectDelay + secureRandom.nextInt(1000));
                    } catch (InterruptedException ignore) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    reconnectDelay = Math.min(MAX_RECONNECT_DELAY_MS, reconnectDelay * 2L);
                }
            }
        }

        private void readStream() throws Exception {
            AgramContainerManager.ContainerRecord record = AgramContainerManager.getInstance().getContainer(account);
            if (record == null || !isCurrentBinding()) {
                return;
            }
            String cursor = TextUtils.isEmpty(lastMessageId) ? "10m" : lastMessageId;
            URL streamUrl = new URL(endpoint + "/json?since=" + cursor);
            RoutedConnection routed = openConnection(streamUrl, record);
            URLConnection raw = routed.connection;
            if (!(raw instanceof HttpsURLConnection)) {
                throw new IOException("Agram Push requires HTTPS");
            }
            HttpsURLConnection https = (HttpsURLConnection) raw;
            connection = https;
            https.setConnectTimeout(20_000);
            // A half-open socket must not keep an account falsely 'connected' forever.
            https.setReadTimeout(90_000);
            https.setInstanceFollowRedirects(false);
            https.setUseCaches(false);
            https.setRequestProperty("Accept", "application/x-ndjson");
            https.setRequestProperty("User-Agent", "AgramPush/" + BuildVars.BUILD_VERSION_STRING);
            int status;
            if (routed.credentials == null) {
                status = https.getResponseCode();
            } else {
                // SOCKS authentication is exposed through a process-wide API.
                // Serialize only the handshake, bind credentials to this exact
                // thread/proxy, and clear the thread-local scope immediately.
                synchronized (PROXY_AUTH_LOCK) {
                    ScopedProxyAuthenticator.begin(
                            routed.proxyHost, routed.proxyPort, routed.credentials);
                    try {
                        status = https.getResponseCode();
                    } finally {
                        ScopedProxyAuthenticator.end();
                    }
                }
            }
            if (status < 200 || status >= 300) {
                throw new IOException("Push server returned HTTP " + status);
            }
            if (stopped || !isCurrentBinding()) return;
            AgramPushState.stream(statusBinding, statusRevision, "connected", "");
            AndroidUtilities.runOnUIThread(() -> {
                if (!stopped && isCurrentBinding()) {
                    registerEndpointWithTelegram(account, containerId, endpoint, sessionGeneration);
                }
            });
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    https.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while (!stopped && (line = readBoundedLine(reader)) != null) {
                    handleLine(line);
                }
                if (!stopped) throw new IOException("Push stream closed");
            }
        }

        // The relay is external input. Never allocate an unbounded line on a subscription thread.
        private String readBoundedLine(BufferedReader reader) throws IOException {
            StringBuilder value = new StringBuilder(256);
            for (int next; (next = reader.read()) != -1;) {
                if (stopped) return null;
                if (next == '\n') return value.toString();
                if (value.length() >= 64 * 1024) throw new IOException("Push event exceeds size limit");
                if (next != '\r') value.append((char) next);
            }
            return value.length() == 0 ? null : value.toString();
        }

        private RoutedConnection openConnection(URL url, AgramContainerManager.ContainerRecord record) throws IOException {
            if (AgramContainerManager.NETWORK_DIRECT.equals(record.proxyMode)) {
                return new RoutedConnection(url.openConnection(), null, 0, null);
            }
            if (AgramContainerManager.NETWORK_PROXY.equals(record.proxyMode)
                    && TextUtils.isEmpty(record.proxySecret)
                    && !TextUtils.isEmpty(record.proxyAddress)
                    && record.proxyPort > 0) {
                Proxy proxy = new Proxy(Proxy.Type.SOCKS,
                        InetSocketAddress.createUnresolved(record.proxyAddress, record.proxyPort));
                PasswordAuthentication credentials = TextUtils.isEmpty(record.proxyUsername)
                        && TextUtils.isEmpty(record.proxyPassword) ? null
                        : new PasswordAuthentication(record.proxyUsername, record.proxyPassword.toCharArray());
                return new RoutedConnection(url.openConnection(proxy), record.proxyAddress,
                        record.proxyPort, credentials);
            }
            throw new IOException("Selected proxy cannot carry the HTTPS push stream without bypassing container routing");
        }

        private void handleLine(String line) {
            if (TextUtils.isEmpty(line)) {
                return;
            }
            try {
                JSONObject event = new JSONObject(line);
                if (!"message".equals(event.optString("event"))
                        || !topic.equals(event.optString("topic"))) {
                    return;
                }
                String messageId = event.optString("id");
                if (!TextUtils.isEmpty(messageId) && messageId.equals(lastMessageId)) {
                    return;
                }
                lastMessageId = messageId;
                AndroidUtilities.runOnUIThread(() -> {
                    if (!stopped && isCurrentBinding()) {
                        onMessage(account, containerId, endpoint, sessionGeneration);
                    }
                });
            } catch (Throwable error) {
                FileLog.e("Unable to parse Agram Push event: " + error.getClass().getSimpleName());
            }
        }

        private boolean isCurrentBinding() {
            AgramContainerManager.ContainerRecord current = AgramContainerManager.getInstance().getContainer(account);
            return current != null
                    && !stopped
                    && current.isStorageAccessible()
                    && containerId.equals(current.id)
                    && endpoint.equals(current.agramPushEndpoint)
                    && AgramContainerManager.PUSH_AGRAM.equals(current.pushMode)
                    && UserConfig.getInstance(account).isSessionGenerationCurrent(sessionGeneration)
                    && UserConfig.getInstance(account).isClientActivated();
        }
    }

    private static String safeConnectionError(Throwable error) {
        if (error instanceof java.net.SocketTimeoutException) return "Истекло время ожидания Push";
        if (error instanceof java.net.UnknownHostException) return "Не удалось найти сервер Push";
        if (error instanceof javax.net.ssl.SSLException) return "Ошибка защищённого соединения Push";
        return "Канал Push недоступен. Проверьте сеть и совместимость прокси с HTTPS.";
    }

    private static final class RoutedConnection {
        final URLConnection connection;
        final String proxyHost;
        final int proxyPort;
        final PasswordAuthentication credentials;

        RoutedConnection(URLConnection connection, String proxyHost, int proxyPort,
                         PasswordAuthentication credentials) {
            this.connection = connection;
            this.proxyHost = proxyHost;
            this.proxyPort = proxyPort;
            this.credentials = credentials;
        }
    }

    private static final class ScopedProxyAuthenticator extends Authenticator {
        private static final ScopedProxyAuthenticator INSTANCE = new ScopedProxyAuthenticator();
        private static final ThreadLocal<ScopedCredentials> ACTIVE = new ThreadLocal<>();

        static void begin(String proxyHost, int proxyPort, PasswordAuthentication credentials) {
            ACTIVE.set(new ScopedCredentials(proxyHost, proxyPort, credentials));
            Authenticator.setDefault(INSTANCE);
        }

        static void end() {
            ACTIVE.remove();
        }

        @Override
        protected PasswordAuthentication getPasswordAuthentication() {
            ScopedCredentials active = ACTIVE.get();
            if (active == null) {
                return null;
            }
            boolean socksRequest = "SOCKS5".equalsIgnoreCase(getRequestingProtocol());
            if ((getRequestorType() != RequestorType.PROXY && !socksRequest)
                    || getRequestingPort() != active.proxyPort
                    || !active.proxyHost.equalsIgnoreCase(getRequestingHost())) {
                return null;
            }
            return active.credentials;
        }
    }

    private static final class ScopedCredentials {
        private final String proxyHost;
        private final int proxyPort;
        private final PasswordAuthentication credentials;

        ScopedCredentials(String proxyHost, int proxyPort, PasswordAuthentication credentials) {
            this.proxyHost = proxyHost;
            this.proxyPort = proxyPort;
            this.credentials = credentials;
        }
    }
}
