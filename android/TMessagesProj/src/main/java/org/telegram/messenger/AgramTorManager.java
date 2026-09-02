/*
 * This file is part of Agram and is licensed under GNU GPL v2 or later.
 */
package org.telegram.messenger;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONObject;
import org.torproject.jni.TorService;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import IPtProxy.Controller;
import IPtProxy.IPtProxy;
import IPtProxy.OnTransportEvents;

/**
 * Owns the Tor daemon embedded in the Agram process.
 *
 * One daemon is intentionally shared to avoid running dozens of native Tor
 * instances. Container traffic is separated with IsolateSOCKSAuth and a
 * different encrypted SOCKS credential for every container.
 */
public final class AgramTorManager {
    public static final String STATE_STOPPED = "stopped";
    public static final String STATE_STARTING = "starting";
    public static final String STATE_READY = "ready";
    public static final String STATE_ERROR = "error";

    private static final String SETTINGS = "agram_tor_settings";
    private static final String BRIDGE_SCOPE = "agram_tor_bridges_v1";
    private static final String BRIDGE_DATA = "bridge_data";
    private static final long BOOTSTRAP_TIMEOUT_MS = 120_000L;
    private static final long BOOTSTRAP_POLL_MS = 1_500L;
    private static final Pattern BOOTSTRAP_PROGRESS_PATTERN =
            Pattern.compile("(?:^|\\s)PROGRESS=([0-9]{1,3})(?:\\s|$)");
    private static final Pattern BOOTSTRAP_SUMMARY_PATTERN =
            Pattern.compile("(?:^|\\s)SUMMARY=\"([^\"]*)\"");

    public interface Listener {
        void onTorStateChanged(String state);
    }

    public static final class BridgeConfig {
        public final boolean enabled;
        public final String lines;

        private BridgeConfig(boolean enabled, String lines) {
            this.enabled = enabled;
            this.lines = lines == null ? "" : lines;
        }
    }

    private static final AgramTorManager INSTANCE = new AgramTorManager();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final ArrayList<String> activeTransports = new ArrayList<>();
    private Context applicationContext;
    private TorService torService;
    private Controller transportController;
    private boolean receiverRegistered;
    private boolean binding;
    private boolean bound;
    private boolean startScheduled;
    private boolean bootstrapPollScheduled;
    private int startGeneration;
    private volatile String state = STATE_STOPPED;
    private volatile String lastError = "";
    private volatile String bootstrapStatus = "";
    private volatile String bootstrapSummary = "";
    private volatile int bootstrapProgress;
    private volatile long bootstrapStartedAt;
    private volatile int socksPort;

    public static AgramTorManager getInstance() {
        return INSTANCE;
    }

    private AgramTorManager() {
    }

    public String getState() {
        return state;
    }

    public String getLastError() {
        return lastError;
    }

    public int getSocksPort() {
        return STATE_READY.equals(state) ? socksPort : 0;
    }

    public String getBootstrapStatus() {
        // UI must never touch Tor's control socket. The value is refreshed by
        // a background poller while the daemon is bootstrapping.
        return bootstrapStatus;
    }

    public String getBootstrapSummary() {
        return bootstrapSummary;
    }

    public int getBootstrapProgress() {
        return bootstrapProgress;
    }

    public String getDiagnosticSummary() {
        BridgeConfig bridges = getBridgeConfig();
        return "Agram Tor diagnostics"
                + "\nstate: " + state
                + "\nprogress: " + bootstrapProgress + "%"
                + "\nsummary: " + (TextUtils.isEmpty(bootstrapSummary) ? "-" : bootstrapSummary)
                + "\nbootstrap: " + (TextUtils.isEmpty(bootstrapStatus) ? "-" : bootstrapStatus)
                + "\nerror: " + (TextUtils.isEmpty(lastError) ? "-" : lastError)
                + "\nsocks_port: " + socksPort
                + "\nbridges_enabled: " + bridges.enabled
                + "\ntor_version: " + TorService.VERSION_NAME;
    }

    public void addListener(Listener listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /** Starts Tor and configured pluggable transports off the UI thread. */
    public synchronized void ensureStarted() {
        if ((STATE_ERROR.equals(state) || STATE_STOPPED.equals(state))
                && (binding || bound || startScheduled)) {
            disconnect();
        }
        if (STATE_READY.equals(state) || binding || bound || startScheduled) {
            return;
        }
        applicationContext = ApplicationLoader.applicationContext.getApplicationContext();
        registerReceiver();
        lastError = "";
        bootstrapStatus = "";
        bootstrapSummary = "Подготовка Tor";
        bootstrapProgress = 0;
        bootstrapStartedAt = SystemClock.elapsedRealtime();
        bootstrapPollScheduled = false;
        startScheduled = true;
        final int generation = ++startGeneration;
        updateState(STATE_STARTING, 0);
        Utilities.globalQueue.postRunnable(() -> {
            try {
                configureTor();
                AndroidUtilities.runOnUIThread(() -> bindTorService(generation));
            } catch (Throwable error) {
                FileLog.e("Unable to configure embedded Tor", error);
                failStart(generation, "Настройка Tor: " + safeError(error));
            }
        });
    }

    /** Restarts the one shared daemon; all Tor containers pause fail-closed. */
    public synchronized void restart() {
        disconnect();
        lastError = "";
        bootstrapStatus = "";
        bootstrapSummary = "";
        bootstrapProgress = 0;
        updateState(STATE_STOPPED, 0);
        AndroidUtilities.runOnUIThread(this::ensureStarted, 700);
    }

    public synchronized void stopIfUnused() {
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            AgramContainerManager.ContainerRecord record = AgramContainerManager.getInstance().getContainer(account);
            if (record != null && AgramContainerManager.NETWORK_TOR.equals(record.proxyMode)) {
                return;
            }
        }
        disconnect();
        updateState(STATE_STOPPED, 0);
    }

    public BridgeConfig getBridgeConfig() {
        try {
            SharedPreferences preferences = ApplicationLoader.applicationContext
                    .getSharedPreferences(SETTINGS, Context.MODE_PRIVATE);
            String encoded = preferences.getString(BRIDGE_DATA, "");
            if (TextUtils.isEmpty(encoded)) {
                return new BridgeConfig(false, "");
            }
            byte[] encrypted = Base64.decode(encoded, Base64.NO_WRAP);
            byte[] clear = AgramSecureStore.decrypt(BRIDGE_SCOPE, encrypted,
                    AgramSecureStore.aad(BRIDGE_SCOPE, "bridges"));
            JSONObject json = new JSONObject(new String(clear, StandardCharsets.UTF_8));
            return new BridgeConfig(json.optBoolean("enabled", false), json.optString("lines", ""));
        } catch (Throwable error) {
            FileLog.e("Unable to read encrypted Tor bridges", error);
            return new BridgeConfig(false, "");
        }
    }

    public void saveBridgeConfig(boolean enabled, String bridgeLines) {
        try {
            String normalized = normalizeBridgeLines(bridgeLines);
            if (enabled && TextUtils.isEmpty(normalized)) {
                throw new IllegalArgumentException("Добавьте хотя бы одну строку Bridge");
            }
            JSONObject json = new JSONObject();
            json.put("enabled", enabled);
            json.put("lines", normalized);
            byte[] encrypted = AgramSecureStore.encrypt(BRIDGE_SCOPE,
                    json.toString().getBytes(StandardCharsets.UTF_8),
                    AgramSecureStore.aad(BRIDGE_SCOPE, "bridges"));
            ApplicationLoader.applicationContext.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
                    .edit().putString(BRIDGE_DATA, Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit();
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Throwable error) {
            throw new IllegalStateException("Не удалось сохранить мосты", error);
        }
    }

    public static String normalizeBridgeLines(String value) {
        if (TextUtils.isEmpty(value)) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        for (String raw : value.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.regionMatches(true, 0, "Bridge ", 0, 7)) {
                line = line.substring(7).trim();
            }
            if (line.indexOf('\u0000') >= 0 || line.length() > 1024) {
                throw new IllegalArgumentException("Некорректная строка моста");
            }
            String lower = line.toLowerCase(Locale.US);
            boolean pluggable = lower.startsWith("obfs4 ") || lower.startsWith("webtunnel ");
            boolean vanilla = line.matches("(\\[[0-9a-fA-F:]+]|[^\\s:]+):[0-9]{1,5}(\\s+[A-Fa-f0-9]{40})?.*");
            if (!pluggable && !vanilla) {
                throw new IllegalArgumentException("Поддерживаются обычные, obfs4 и webtunnel мосты");
            }
            if (result.length() > 0) {
                result.append('\n');
            }
            result.append(line);
        }
        return result.toString();
    }

    private synchronized void bindTorService(int generation) {
        startScheduled = false;
        if (generation != startGeneration || !STATE_STARTING.equals(state)
                || applicationContext == null || binding || bound) {
            return;
        }
        try {
            binding = true;
            Intent intent = new Intent(applicationContext, TorService.class);
            intent.setAction(TorService.ACTION_START);
            if (!applicationContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)) {
                binding = false;
                failStart(generation, "Не удалось привязать встроенный TorService");
            }
        } catch (Throwable error) {
            binding = false;
            FileLog.e("Unable to bind embedded Tor", error);
            failStart(generation, "Запуск TorService: " + safeError(error));
        }
    }

    private void registerReceiver() {
        if (receiverRegistered) {
            return;
        }
        TorService.setBroadcastPackageName(applicationContext.getPackageName());
        IntentFilter filter = new IntentFilter();
        filter.addAction(TorService.ACTION_STATUS);
        filter.addAction(TorService.ACTION_ERROR);
        if (Build.VERSION.SDK_INT >= 33) {
            applicationContext.registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            applicationContext.registerReceiver(statusReceiver, filter);
        }
        receiverRegistered = true;
    }

    private void unregisterReceiver() {
        if (!receiverRegistered || applicationContext == null) {
            return;
        }
        try {
            applicationContext.unregisterReceiver(statusReceiver);
        } catch (Throwable ignore) {
        }
        receiverRegistered = false;
    }

    private void configureTor() throws Exception {
        removeStaleControlSocket();
        BridgeConfig bridges = getBridgeConfig();
        List<String> bridgeLines = bridges.enabled
                ? splitLines(normalizeBridgeLines(bridges.lines)) : Collections.emptyList();
        StringBuilder torrc = new StringBuilder()
                .append("ClientOnly 1\n")
                .append("AvoidDiskWrites 1\n")
                .append("SafeLogging 1\n")
                // TorService already writes a non-zero SocksPort to its
                // defaults torrc. Never combine it with "SocksPort 0": Tor
                // rejects a zero and non-zero SocksPort in one effective
                // configuration before bootstrap begins. Multiple non-zero
                // listeners are valid, and TorService discovers the selected
                // port through GETINFO net/listeners/socks.
                .append("SocksPort auto IsolateSOCKSAuth\n");

        stopTransports();
        if (!bridgeLines.isEmpty()) {
            boolean needsObfs4 = containsTransport(bridgeLines, "obfs4");
            boolean needsWebTunnel = containsTransport(bridgeLines, "webtunnel");
            if (needsObfs4 || needsWebTunnel) {
                ensureTransportController();
            }
            if (needsObfs4) {
                startTransport(IPtProxy.Obfs4, "obfs4", torrc);
            }
            if (needsWebTunnel) {
                startTransport(IPtProxy.Webtunnel, "webtunnel", torrc);
            }
            torrc.append("UseBridges 1\n");
            for (String line : bridgeLines) {
                torrc.append("Bridge ").append(line).append('\n');
            }
        }
        try (FileOutputStream output = new FileOutputStream(TorService.getTorrc(applicationContext), false)) {
            output.write(torrc.toString().getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
    }

    private void removeStaleControlSocket() {
        File serviceDirectory = TorService.getTorrc(applicationContext).getParentFile();
        File controlSocket = new File(new File(serviceDirectory, "data"), "ControlSocket");
        if (controlSocket.exists() && !controlSocket.delete()) {
            throw new IllegalStateException("Не удалось удалить зависший ControlSocket Tor");
        }
    }

    private void ensureTransportController() {
        if (transportController != null) {
            return;
        }
        File directory = new File(applicationContext.getNoBackupFilesDir(), "agram_tor/pt_state");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Unable to create private transport state directory");
        }
        transportController = new Controller(directory.getAbsolutePath(), false, false, "NOTICE",
                new OnTransportEvents() {
                    @Override
                    public void connected(String transport) {
                        FileLog.d("Agram Tor transport connected: " + transport);
                    }

                    @Override
                    public void error(String transport, Exception error) {
                        FileLog.e("Agram Tor transport error: " + transport, error);
                        onTransportConnectionIssue(transport, error);
                    }

                    @Override
                    public void stopped(String transport, Exception error) {
                        if (error != null) {
                            // For obfs4/webtunnel IPtProxy reports Stopped for one
                            // failed SOCKS connection, not for the shared listener.
                            // Tor must remain alive so it can try the next bridge.
                            FileLog.e("Agram Tor transport connection failed: " + transport, error);
                            onTransportConnectionIssue(transport, error);
                        }
                    }
                });
    }

    private void onTransportConnectionIssue(String transport, Throwable error) {
        synchronized (this) {
            if (STATE_STOPPED.equals(state) || STATE_ERROR.equals(state)) {
                return;
            }
            lastError = transport + ": " + safeError(error);
            notifyListeners();
            scheduleBootstrapPoll(startGeneration, 0);
        }
    }

    private void startTransport(String transport, String torName, StringBuilder torrc) throws Exception {
        transportController.start(transport, "");
        long port = transportController.port(transport);
        String endpoint = transportController.localAddress(transport);
        if (port <= 0 || port > 65535) {
            throw new IllegalStateException("Transport " + torName + " did not allocate a port");
        }
        // IPtProxy localAddress() already returns host and port. Appending port again
        // produces an invalid endpoint such as 127.0.0.1:12345:12345.
        if (TextUtils.isEmpty(endpoint)) {
            endpoint = "127.0.0.1:" + port;
        }
        activeTransports.add(transport);
        torrc.append("ClientTransportPlugin ").append(torName)
                .append(" socks5 ").append(endpoint).append('\n');
    }

    private synchronized void scheduleBootstrapPoll(int generation, long delay) {
        if (generation != startGeneration || bootstrapPollScheduled) {
            return;
        }
        bootstrapPollScheduled = true;
        Utilities.globalQueue.postRunnable(() -> {
            synchronized (AgramTorManager.this) {
                bootstrapPollScheduled = false;
                if (generation != startGeneration) {
                    return;
                }
            }
            pollBootstrap(generation);
        }, delay);
    }

    private void pollBootstrap(int generation) {
        TorService service;
        long startedAt;
        synchronized (this) {
            if (generation != startGeneration || !bound || torService == null
                    || (!STATE_STARTING.equals(state) && !STATE_READY.equals(state))) {
                return;
            }
            service = torService;
            startedAt = bootstrapStartedAt;
        }

        String phase = "";
        int port = 0;
        try {
            String value = service.getInfo("status/bootstrap-phase");
            phase = value == null ? "" : value;
            port = service.getSocksPort();
        } catch (Throwable error) {
            FileLog.e("Unable to query embedded Tor bootstrap", error);
        }
        updateBootstrap(phase);

        synchronized (this) {
            if (generation != startGeneration || service != torService) {
                return;
            }
        }
        // STATUS_ON means that TorService is running, not that Tor has built a
        // usable circuit. Only the control-port bootstrap phase may mark the
        // route ready; otherwise a bridge timeout can be shown as a false 100%.
        if (port > 0 && port <= 65535 && bootstrapProgress >= 100) {
            lastError = "";
            bootstrapProgress = 100;
            bootstrapSummary = "Tor подключён";
            updateState(STATE_READY, port);
            return;
        }
        if (SystemClock.elapsedRealtime() - startedAt >= BOOTSTRAP_TIMEOUT_MS) {
            String transportError = lastError;
            failStart(generation,
                    "Tor не подключился за 120 секунд. Проверьте сеть или замените мост."
                            + (TextUtils.isEmpty(transportError) ? ""
                            : " Последняя ошибка транспорта: " + transportError));
            return;
        }
        scheduleBootstrapPoll(generation, BOOTSTRAP_POLL_MS);
    }

    private void updateBootstrap(String phase) {
        String normalized = phase == null ? "" : phase.trim();
        int progress = parseBootstrapProgress(normalized, bootstrapProgress);
        String summary = parseBootstrapSummary(normalized);
        if (TextUtils.isEmpty(summary)) {
            summary = bootstrapSummary;
        }
        boolean changed = !normalized.equals(bootstrapStatus)
                || progress != bootstrapProgress
                || !summary.equals(bootstrapSummary);
        bootstrapStatus = normalized;
        bootstrapProgress = progress;
        bootstrapSummary = summary;
        if (changed) {
            notifyListeners();
        }
    }

    private static int parseBootstrapProgress(String phase, int fallback) {
        Matcher matcher = BOOTSTRAP_PROGRESS_PATTERN.matcher(phase);
        if (!matcher.find()) {
            return fallback;
        }
        try {
            return Math.max(0, Math.min(100, Integer.parseInt(matcher.group(1))));
        } catch (NumberFormatException ignore) {
            return fallback;
        }
    }

    private static String parseBootstrapSummary(String phase) {
        Matcher matcher = BOOTSTRAP_SUMMARY_PATTERN.matcher(phase);
        return matcher.find() ? matcher.group(1).replace("\\\"", "\"") : "";
    }

    private synchronized void failStart(int generation, String error) {
        if (generation != startGeneration) {
            return;
        }
        failStart(error);
    }

    private synchronized void failStart(String error) {
        String phase = bootstrapStatus;
        String summary = bootstrapSummary;
        int progress = bootstrapProgress;
        disconnect();
        bootstrapStatus = phase;
        bootstrapSummary = summary;
        bootstrapProgress = progress;
        lastError = TextUtils.isEmpty(error) ? "Tor остановился без описания ошибки" : error;
        updateState(STATE_ERROR, 0);
    }

    private synchronized void disconnect() {
        startGeneration++;
        startScheduled = false;
        unregisterReceiver();
        if (applicationContext != null && (bound || binding)) {
            try {
                applicationContext.unbindService(serviceConnection);
            } catch (Throwable ignore) {
            }
        }
        torService = null;
        binding = false;
        bound = false;
        bootstrapPollScheduled = false;
        bootstrapStatus = "";
        bootstrapSummary = "";
        bootstrapProgress = 0;
        bootstrapStartedAt = 0;
        socksPort = 0;
        stopTransports();
    }

    private synchronized void stopTransports() {
        if (transportController != null) {
            for (String transport : new ArrayList<>(activeTransports)) {
                try {
                    transportController.stop(transport);
                } catch (Throwable error) {
                    FileLog.e("Unable to stop Tor transport " + transport, error);
                }
            }
        }
        activeTransports.clear();
    }

    private void updateState(String newState, int newPort) {
        if (newState.equals(state) && newPort == socksPort) {
            return;
        }
        state = newState;
        socksPort = newPort;
        // Route changes can load encrypted container metadata. Never perform
        // that work while a Tor callback is holding this manager's monitor.
        Utilities.globalQueue.postRunnable(() ->
                AgramNetworkController.getInstance().onTorStateChanged(newState, newPort));
        notifyListeners();
    }

    private void notifyListeners() {
        AndroidUtilities.runOnUIThread(() -> {
            String currentState = state;
            for (Listener listener : listeners) {
                listener.onTorStateChanged(currentState);
            }
        });
    }

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (AgramTorManager.this) {
                binding = false;
                bound = true;
                torService = ((TorService.LocalBinder) service).getService();
                scheduleBootstrapPoll(startGeneration, 0);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (AgramTorManager.this) {
                torService = null;
                binding = false;
                bound = false;
                failStart("Встроенный TorService неожиданно отключился");
            }
        }
    };

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (TorService.ACTION_ERROR.equals(intent.getAction())) {
                String message = intent.getStringExtra(Intent.EXTRA_TEXT);
                String error = TextUtils.isEmpty(message) ? "Tor завершился с неизвестной ошибкой" : message;
                FileLog.e("Embedded Tor error: " + error);
                failStart(error);
                return;
            }
            if (!TorService.ACTION_STATUS.equals(intent.getAction())) {
                return;
            }
            String servicePackage = intent.getStringExtra(TorService.EXTRA_SERVICE_PACKAGE_NAME);
            if (servicePackage != null && !context.getPackageName().equals(servicePackage)) {
                return;
            }
            String status = intent.getStringExtra(TorService.EXTRA_STATUS);
            if (TorService.STATUS_ON.equals(status)) {
                scheduleBootstrapPoll(startGeneration, 0);
            } else if (TorService.STATUS_STARTING.equals(status)) {
                updateState(STATE_STARTING, 0);
                scheduleBootstrapPoll(startGeneration, 0);
            } else if (TorService.STATUS_OFF.equals(status) || TorService.STATUS_STOPPING.equals(status)) {
                failStart("TorService остановился до завершения подключения");
            }
        }
    };

    private static boolean containsTransport(List<String> lines, String transport) {
        String prefix = transport.toLowerCase(Locale.US) + " ";
        for (String line : lines) {
            if (line.toLowerCase(Locale.US).startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> splitLines(String value) {
        ArrayList<String> result = new ArrayList<>();
        if (!TextUtils.isEmpty(value)) {
            Collections.addAll(result, value.split("\\n"));
        }
        return result;
    }

    private static String safeError(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        String message = error.getMessage();
        return TextUtils.isEmpty(message) ? error.getClass().getSimpleName() : message;
    }
}
