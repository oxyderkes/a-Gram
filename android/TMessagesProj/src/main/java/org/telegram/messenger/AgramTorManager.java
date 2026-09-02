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
import java.util.List;
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
    private static final long BRIDGE_ATTEMPT_TIMEOUT_MS = 55_000L;
    private static final long BRIDGE_STALL_TIMEOUT_MS = 30_000L;
    private static final long SNOWFLAKE_ATTEMPT_TIMEOUT_MS = 95_000L;
    private static final long SNOWFLAKE_STALL_TIMEOUT_MS = 55_000L;
    private static final long MIN_STALL_CHECK_MS = 25_000L;
    private static final long ROTATION_RESTART_DELAY_MS = 1_200L;
    private static final long IGNORE_OLD_SERVICE_BROADCAST_MS = 5_000L;
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
        public final String mode;
        public final boolean snowflakeFallback;
        private final boolean readable;
        private final String readError;

        private BridgeConfig(AgramTorBridgePool.Config config) {
            this.enabled = config.enabled;
            this.lines = config.lines;
            this.mode = config.mode;
            this.snowflakeFallback = config.snowflakeFallback;
            this.readable = config.readable;
            this.readError = config.readError;
        }
    }

    private static final AgramTorManager INSTANCE = new AgramTorManager();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final ArrayList<String> activeTransports = new ArrayList<>();
    private final Object bridgeStoreLock = new Object();
    private Context applicationContext;
    private TorService torService;
    private Controller transportController;
    private ServiceConnection activeServiceConnection;
    private AgramTorBridgePool.Config activeBridgeConfig;
    private List<AgramTorBridgePool.Candidate> bridgePlan = java.util.Collections.emptyList();
    private AgramTorBridgePool.Candidate activeBridgeCandidate;
    private boolean receiverRegistered;
    private boolean binding;
    private boolean bound;
    private boolean startScheduled;
    private boolean bootstrapPollScheduled;
    private boolean candidateOutcomeRecorded;
    private int startGeneration;
    private int bridgeAttemptIndex = -1;
    private volatile int bridgeRotationCount;
    private int attemptBestProgress;
    private long attemptLastProgressAt;
    private long ignoreServiceBroadcastsUntil;
    private volatile String state = STATE_STOPPED;
    private volatile String lastError = "";
    private volatile String bootstrapStatus = "";
    private volatile String bootstrapSummary = "";
    private volatile int bootstrapProgress;
    private volatile long bootstrapStartedAt;
    private volatile int socksPort;
    private volatile String diagnosticBridgeMode = AgramTorBridgePool.MODE_OFF;
    private volatile String diagnosticBridgeTransport = "-";
    private volatile int diagnosticBridgeAttempt;
    private volatile int diagnosticBridgePoolSize;

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
                + "\nbridge_config: " + (bridges.readable ? "ok" : "unreadable")
                + "\nbridge_mode: " + diagnosticBridgeMode
                + "\nbridge_pool_size: " + diagnosticBridgePoolSize
                + "\nbridge_attempt: " + (diagnosticBridgeAttempt == 0 ? "-"
                : diagnosticBridgeAttempt + "/" + diagnosticBridgePoolSize)
                + "\nbridge_transport: " + diagnosticBridgeTransport
                + "\nbridge_rotations: " + bridgeRotationCount
                + "\nsnowflake_fallback: " + bridges.snowflakeFallback
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
        bootstrapStartedAt = 0;
        bootstrapPollScheduled = false;
        startScheduled = true;
        bridgePlan = java.util.Collections.emptyList();
        activeBridgeConfig = null;
        activeBridgeCandidate = null;
        bridgeAttemptIndex = -1;
        bridgeRotationCount = 0;
        candidateOutcomeRecorded = false;
        diagnosticBridgeMode = AgramTorBridgePool.MODE_OFF;
        diagnosticBridgeTransport = "-";
        diagnosticBridgeAttempt = 0;
        diagnosticBridgePoolSize = 0;
        final int generation = ++startGeneration;
        updateState(STATE_STARTING, 0);
        Utilities.globalQueue.postRunnable(() -> prepareAndStart(generation));
    }

    /** Restarts the one shared daemon; all Tor containers pause fail-closed. */
    public synchronized void restart() {
        disconnect();
        lastError = "";
        bootstrapStatus = "";
        bootstrapSummary = "";
        bootstrapProgress = 0;
        updateState(STATE_STOPPED, 0);
        AndroidUtilities.runOnUIThread(this::ensureStarted, ROTATION_RESTART_DELAY_MS);
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
        return new BridgeConfig(readBridgeState());
    }

    public void saveBridgeConfig(boolean enabled, String bridgeLines) {
        try {
            String normalized = normalizeBridgeLines(bridgeLines);
            synchronized (bridgeStoreLock) {
                AgramTorBridgePool.Config previous = readBridgeStateLocked();
                AgramTorBridgePool.Config updated = AgramTorBridgePool.updated(previous, enabled, normalized);
                writeBridgeStateLocked(updated);
            }
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Throwable error) {
            throw new IllegalStateException("Не удалось сохранить мосты", error);
        }
    }

    public static String normalizeBridgeLines(String value) {
        return AgramTorBridgePool.normalize(value);
    }

    private AgramTorBridgePool.Config readBridgeState() {
        synchronized (bridgeStoreLock) {
            return readBridgeStateLocked();
        }
    }

    private AgramTorBridgePool.Config readBridgeStateLocked() {
        try {
            SharedPreferences preferences = ApplicationLoader.applicationContext
                    .getSharedPreferences(SETTINGS, Context.MODE_PRIVATE);
            String encoded = preferences.getString(BRIDGE_DATA, "");
            if (TextUtils.isEmpty(encoded)) {
                return AgramTorBridgePool.Config.disabled();
            }
            byte[] encrypted = Base64.decode(encoded, Base64.NO_WRAP);
            byte[] clear = AgramSecureStore.decrypt(BRIDGE_SCOPE, encrypted,
                    AgramSecureStore.aad(BRIDGE_SCOPE, "bridges"));
            return AgramTorBridgePool.parse(
                    new JSONObject(new String(clear, StandardCharsets.UTF_8)));
        } catch (Throwable error) {
            FileLog.e("Unable to read encrypted Tor bridge configuration: " + safeError(error));
            // An existing but unreadable bridge configuration must never be
            // interpreted as "bridges disabled", which would silently open a
            // direct route.
            return AgramTorBridgePool.Config.unreadable(error);
        }
    }

    private void writeBridgeStateLocked(AgramTorBridgePool.Config config) throws Exception {
        byte[] encrypted = AgramSecureStore.encrypt(BRIDGE_SCOPE,
                AgramTorBridgePool.toJson(config).toString().getBytes(StandardCharsets.UTF_8),
                AgramSecureStore.aad(BRIDGE_SCOPE, "bridges"));
        boolean committed = ApplicationLoader.applicationContext
                .getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
                .edit().putString(BRIDGE_DATA, Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit();
        if (!committed) {
            throw new IllegalStateException("Encrypted bridge configuration was not committed");
        }
    }

    private void prepareAndStart(int generation) {
        AgramTorBridgePool.Config config = readBridgeState();
        List<AgramTorBridgePool.Candidate> plan = AgramTorBridgePool.buildPlan(
                config, System.currentTimeMillis());
        AgramTorBridgePool.Candidate candidate = plan.isEmpty() ? null : plan.get(0);
        synchronized (this) {
            if (generation != startGeneration || !startScheduled) {
                return;
            }
            activeBridgeConfig = config;
            bridgePlan = plan;
            activeBridgeCandidate = candidate;
            bridgeAttemptIndex = candidate == null ? -1 : 0;
            diagnosticBridgeMode = config.readable
                    ? (config.enabled ? AgramTorBridgePool.MODE_AUTO : "direct") : "unreadable";
            diagnosticBridgePoolSize = plan.size();
            diagnosticBridgeAttempt = candidate == null ? 0 : 1;
            diagnosticBridgeTransport = candidate == null ? "-" : candidate.transport;
        }
        if (!config.readable) {
            failStart(generation, "Зашифрованные настройки мостов недоступны; прямое подключение запрещено");
            return;
        }
        if (config.enabled && candidate == null) {
            failStart(generation, "Автоматический режим мостов не содержит безопасных маршрутов");
            return;
        }
        startConfiguredAttempt(generation);
    }

    private void startConfiguredAttempt(int generation) {
        AgramTorBridgePool.Config config;
        AgramTorBridgePool.Candidate candidate;
        synchronized (this) {
            if (generation != startGeneration || !startScheduled
                    || !STATE_STARTING.equals(state)) {
                return;
            }
            config = activeBridgeConfig;
            candidate = activeBridgeCandidate;
            long now = SystemClock.elapsedRealtime();
            bootstrapStartedAt = now;
            attemptLastProgressAt = now;
            attemptBestProgress = 0;
            bootstrapProgress = 0;
            candidateOutcomeRecorded = false;
        }
        try {
            configureTor(config, candidate, generation);
            AndroidUtilities.runOnUIThread(() -> bindTorService(generation));
        } catch (Throwable error) {
            String message = "Настройка " + (candidate == null ? "Tor" : candidate.transport)
                    + ": " + safeError(error);
            FileLog.e("Unable to configure embedded Tor attempt: " + message);
            onAttemptFailure(generation, message);
        }
    }

    private synchronized void bindTorService(int generation) {
        if (generation != startGeneration || !STATE_STARTING.equals(state)
                || applicationContext == null || binding || bound) {
            return;
        }
        // A delayed UI callback from a retired bridge attempt must not clear
        // the scheduling flag of the replacement attempt.
        startScheduled = false;
        try {
            registerReceiver();
            binding = true;
            Intent intent = new Intent(applicationContext, TorService.class);
            intent.setAction(TorService.ACTION_START);
            ServiceConnection connection = createServiceConnection(generation);
            activeServiceConnection = connection;
            if (!applicationContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                binding = false;
                activeServiceConnection = null;
                onAttemptFailure(generation, "Не удалось привязать встроенный TorService");
            } else {
                scheduleBootstrapPoll(generation, BOOTSTRAP_POLL_MS);
            }
        } catch (Throwable error) {
            binding = false;
            activeServiceConnection = null;
            FileLog.e("Unable to bind embedded Tor: " + safeError(error));
            onAttemptFailure(generation, "Запуск TorService: " + safeError(error));
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

    private void configureTor(AgramTorBridgePool.Config config,
            AgramTorBridgePool.Candidate candidate, int generation) throws Exception {
        if (config == null || !config.readable) {
            throw new IllegalStateException("Encrypted bridge configuration is unavailable");
        }
        if (config.enabled && candidate == null) {
            throw new IllegalStateException("Bridge mode has no candidate");
        }
        removeStaleControlSocket();
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
        if (config.enabled) {
            String transport = candidate.transport;
            if (!"vanilla".equals(transport)) {
                ensureTransportController();
            }
            if ("obfs4".equals(transport)) {
                startTransport(IPtProxy.Obfs4, "obfs4", torrc, generation);
            } else if ("webtunnel".equals(transport)) {
                startTransport(IPtProxy.Webtunnel, "webtunnel", torrc, generation);
            } else if ("snowflake".equals(transport)) {
                configureSnowflake(candidate);
                startTransport(IPtProxy.Snowflake, "snowflake", torrc, generation);
            } else if (!"vanilla".equals(transport)) {
                throw new IllegalStateException("Unsupported bridge transport");
            }
            // This branch is deliberately keyed by enabled, not by a non-empty
            // line list. A bridge attempt can therefore never degrade into a
            // direct Tor connection if candidate setup fails.
            torrc.append("UseBridges 1\n");
            for (String line : candidate.bridgeLines) {
                torrc.append("Bridge ").append(line).append('\n');
            }
        }
        try (FileOutputStream output = new FileOutputStream(TorService.getTorrc(applicationContext), false)) {
            output.write(torrc.toString().getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
    }

    private void configureSnowflake(AgramTorBridgePool.Candidate candidate) {
        String brokerUrl = AgramTorBridgePool.candidateArgument(candidate, "url");
        String frontDomains = AgramTorBridgePool.candidateArgument(candidate, "fronts", "front");
        String iceServers = AgramTorBridgePool.candidateArgument(candidate, "ice");
        transportController.setSnowflakeBrokerUrl(TextUtils.isEmpty(brokerUrl)
                ? AgramTorBridgePool.SNOWFLAKE_BROKER_URL : brokerUrl);
        transportController.setSnowflakeFrontDomains(TextUtils.isEmpty(frontDomains)
                ? AgramTorBridgePool.SNOWFLAKE_FRONT_DOMAINS : frontDomains);
        transportController.setSnowflakeIceServers(TextUtils.isEmpty(iceServers)
                ? AgramTorBridgePool.SNOWFLAKE_ICE_SERVERS : iceServers);
        transportController.setSnowflakeMaxPeers(1L);
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
                        FileLog.e("Agram Tor transport error: " + transport + ": " + safeError(error));
                        onTransportConnectionIssue(transport, error);
                    }

                    @Override
                    public void stopped(String transport, Exception error) {
                        if (error != null) {
                            // For obfs4/webtunnel IPtProxy reports Stopped for one
                            // failed SOCKS connection, not for the shared listener.
                            // Tor must remain alive so it can try the next bridge.
                            FileLog.e("Agram Tor transport connection failed: " + transport
                                    + ": " + safeError(error));
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
            if (activeBridgeCandidate != null
                    && !activeBridgeCandidate.transport.equals(transport)) {
                return;
            }
            lastError = AgramTorBridgePool.sanitizeDiagnostic(transport + ": " + safeError(error));
            notifyListeners();
            scheduleBootstrapPoll(startGeneration, 0);
        }
    }

    private void startTransport(String transport, String torName, StringBuilder torrc,
            int generation) throws Exception {
        Controller controller = transportController;
        try {
            controller.start(transport, "");
        } catch (Exception error) {
            // A native transport may have allocated resources before reporting
            // startup failure. stop() is idempotent for an absent transport.
            try {
                controller.stop(transport);
            } catch (Throwable ignore) {
            }
            throw error;
        }
        // Track immediately after a successful start so validation failures
        // below are still torn down before rotating to the next candidate.
        boolean current;
        synchronized (this) {
            current = generation == startGeneration && STATE_STARTING.equals(state);
            if (current) {
                activeTransports.add(transport);
            }
        }
        if (!current) {
            try {
                controller.stop(transport);
            } catch (Throwable ignore) {
            }
            throw new IllegalStateException("Tor attempt was retired during transport startup");
        }
        long port = controller.port(transport);
        String endpoint = controller.localAddress(transport);
        if (port <= 0 || port > 65535) {
            throw new IllegalStateException("Transport " + torName + " did not allocate a port");
        }
        // IPtProxy localAddress() already returns host and port. Appending port again
        // produces an invalid endpoint such as 127.0.0.1:12345:12345.
        if (TextUtils.isEmpty(endpoint)) {
            endpoint = "127.0.0.1:" + port;
        }
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
                if (generation != startGeneration) {
                    return;
                }
                // A queued poll from a retired route must not clear the
                // scheduling flag that belongs to its replacement.
                bootstrapPollScheduled = false;
            }
            pollBootstrap(generation);
        }, delay);
    }

    private void pollBootstrap(int generation) {
        TorService service;
        long startedAt;
        long lastProgressAt;
        AgramTorBridgePool.Candidate candidate;
        synchronized (this) {
            if (generation != startGeneration
                    || (!STATE_STARTING.equals(state) && !STATE_READY.equals(state))) {
                return;
            }
            service = torService;
            startedAt = bootstrapStartedAt;
            lastProgressAt = attemptLastProgressAt;
            candidate = activeBridgeCandidate;
        }

        if (!bound || service == null) {
            long elapsed = SystemClock.elapsedRealtime() - startedAt;
            long timeout = candidate == null ? BOOTSTRAP_TIMEOUT_MS
                    : ("snowflake".equals(candidate.transport)
                    ? SNOWFLAKE_ATTEMPT_TIMEOUT_MS : BRIDGE_ATTEMPT_TIMEOUT_MS);
            if (startedAt > 0 && elapsed >= timeout) {
                onAttemptFailure(generation, "TorService не запустился для маршрута "
                        + (candidate == null ? "direct" : candidate.transport));
            } else {
                scheduleBootstrapPoll(generation, BOOTSTRAP_POLL_MS);
            }
            return;
        }

        String phase = "";
        int port = 0;
        try {
            String value = service.getInfo("status/bootstrap-phase");
            phase = value == null ? "" : value;
            port = service.getSocksPort();
        } catch (Throwable error) {
            FileLog.e("Unable to query embedded Tor bootstrap: " + safeError(error));
        }
        updateBootstrap(generation, phase);

        synchronized (this) {
            if (generation != startGeneration || service != torService) {
                return;
            }
        }
        // STATUS_ON means that TorService is running, not that Tor has built a
        // usable circuit. Only the control-port bootstrap phase may mark the
        // route ready; otherwise a bridge timeout can be shown as a false 100%.
        if (port > 0 && port <= 65535 && bootstrapProgress >= 100) {
            AgramTorBridgePool.Candidate successfulCandidate = null;
            synchronized (this) {
                if (generation != startGeneration || service != torService) {
                    return;
                }
                if (activeBridgeCandidate != null && !candidateOutcomeRecorded) {
                    candidateOutcomeRecorded = true;
                    successfulCandidate = activeBridgeCandidate;
                }
            }
            if (successfulCandidate != null) {
                recordBridgeOutcome(successfulCandidate, true);
            }
            lastError = "";
            bootstrapProgress = 100;
            bootstrapSummary = "Tor подключён";
            bootstrapStatus = "progress=100 summary=Tor подключён";
            updateState(STATE_READY, port);
            return;
        }
        long now = SystemClock.elapsedRealtime();
        long elapsed = now - startedAt;
        if (candidate != null) {
            boolean snowflake = "snowflake".equals(candidate.transport);
            long hardTimeout = snowflake ? SNOWFLAKE_ATTEMPT_TIMEOUT_MS : BRIDGE_ATTEMPT_TIMEOUT_MS;
            long stallTimeout = snowflake ? SNOWFLAKE_STALL_TIMEOUT_MS : BRIDGE_STALL_TIMEOUT_MS;
            synchronized (this) {
                lastProgressAt = attemptLastProgressAt;
            }
            if (elapsed >= hardTimeout
                    || (elapsed >= MIN_STALL_CHECK_MS && now - lastProgressAt >= stallTimeout)) {
                String reason = "Маршрут " + candidate.transport + " не завершил bootstrap"
                        + (TextUtils.isEmpty(lastError) ? "" : ": " + lastError);
                onAttemptFailure(generation, reason);
                return;
            }
        } else if (elapsed >= BOOTSTRAP_TIMEOUT_MS) {
            String transportError = lastError;
            failStart(generation,
                    "Прямой Tor не подключился за 120 секунд."
                            + (TextUtils.isEmpty(transportError) ? ""
                            : " Последняя ошибка: " + transportError));
            return;
        }
        scheduleBootstrapPoll(generation, BOOTSTRAP_POLL_MS);
    }

    private void updateBootstrap(int generation, String phase) {
        String raw = phase == null ? "" : phase.trim();
        int progress = parseBootstrapProgress(raw, bootstrapProgress);
        String summary = AgramTorBridgePool.sanitizeDiagnostic(parseBootstrapSummary(raw));
        if (TextUtils.isEmpty(summary)) {
            summary = bootstrapSummary;
        }
        String compactStatus = TextUtils.isEmpty(raw) ? ""
                : "progress=" + progress + (TextUtils.isEmpty(summary) ? "" : " summary=" + summary);
        boolean changed = !compactStatus.equals(bootstrapStatus)
                || progress != bootstrapProgress
                || !summary.equals(bootstrapSummary);
        synchronized (this) {
            if (generation != startGeneration) {
                return;
            }
            if (progress > attemptBestProgress) {
                attemptBestProgress = progress;
                attemptLastProgressAt = SystemClock.elapsedRealtime();
            }
        }
        bootstrapStatus = compactStatus;
        bootstrapProgress = progress;
        bootstrapSummary = summary;
        if (changed) {
            notifyListeners();
        }
    }

    private void onAttemptFailure(int generation, String error) {
        String safeMessage = AgramTorBridgePool.sanitizeDiagnostic(error);
        AgramTorBridgePool.Candidate failedCandidate = null;
        boolean rotate = false;
        int nextGeneration = 0;
        synchronized (this) {
            if (generation != startGeneration || STATE_STOPPED.equals(state)
                    || STATE_ERROR.equals(state) || STATE_READY.equals(state)) {
                return;
            }
            if (activeBridgeCandidate != null && !candidateOutcomeRecorded) {
                candidateOutcomeRecorded = true;
                failedCandidate = activeBridgeCandidate;
            }
            if (activeBridgeConfig != null && activeBridgeConfig.enabled
                    && bridgeAttemptIndex + 1 < bridgePlan.size()) {
                nextGeneration = ++startGeneration;
                teardownAttemptLocked();
                bridgeAttemptIndex++;
                activeBridgeCandidate = bridgePlan.get(bridgeAttemptIndex);
                bridgeRotationCount++;
                candidateOutcomeRecorded = false;
                startScheduled = true;
                ignoreServiceBroadcastsUntil = SystemClock.elapsedRealtime()
                        + IGNORE_OLD_SERVICE_BROADCAST_MS;
                bootstrapStatus = "";
                bootstrapSummary = "Смена маршрута Tor";
                bootstrapProgress = 0;
                bootstrapStartedAt = 0;
                attemptBestProgress = 0;
                attemptLastProgressAt = 0;
                lastError = safeMessage;
                diagnosticBridgeAttempt = bridgeAttemptIndex + 1;
                diagnosticBridgeTransport = activeBridgeCandidate.transport;
                updateState(STATE_STARTING, 0);
                rotate = true;
            }
        }
        if (failedCandidate != null) {
            recordBridgeOutcome(failedCandidate, false);
        }
        if (rotate) {
            notifyListeners();
            final int scheduledGeneration = nextGeneration;
            Utilities.globalQueue.postRunnable(
                    () -> startConfiguredAttempt(scheduledGeneration), ROTATION_RESTART_DELAY_MS);
        } else {
            failStart(generation, safeMessage + (failedCandidate == null ? ""
                    : ". Безопасные маршруты исчерпаны; прямого fallback нет"));
        }
    }

    private void recordBridgeOutcome(AgramTorBridgePool.Candidate candidate, boolean success) {
        try {
            synchronized (bridgeStoreLock) {
                AgramTorBridgePool.Config current = readBridgeStateLocked();
                if (!current.readable) {
                    return;
                }
                AgramTorBridgePool.recordOutcome(current, candidate, success, System.currentTimeMillis());
                writeBridgeStateLocked(current);
            }
        } catch (Throwable error) {
            // Persistence only affects future ordering. Never interrupt a
            // working route, and never log the candidate line itself.
            FileLog.e("Unable to persist Tor bridge score: " + safeError(error));
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
        lastError = TextUtils.isEmpty(error) ? "Tor остановился без описания ошибки"
                : AgramTorBridgePool.sanitizeDiagnostic(error);
        updateState(STATE_ERROR, 0);
    }

    private synchronized void disconnect() {
        startGeneration++;
        startScheduled = false;
        teardownAttemptLocked();
        bridgePlan = java.util.Collections.emptyList();
        activeBridgeConfig = null;
        activeBridgeCandidate = null;
        bridgeAttemptIndex = -1;
        candidateOutcomeRecorded = false;
        ignoreServiceBroadcastsUntil = 0;
        bootstrapStatus = "";
        bootstrapSummary = "";
        bootstrapProgress = 0;
        bootstrapStartedAt = 0;
        attemptBestProgress = 0;
        attemptLastProgressAt = 0;
        socksPort = 0;
    }

    private void teardownAttemptLocked() {
        unregisterReceiver();
        ServiceConnection connection = activeServiceConnection;
        boolean wasAttached = bound || binding;
        activeServiceConnection = null;
        torService = null;
        binding = false;
        bound = false;
        bootstrapPollScheduled = false;
        socksPort = 0;
        if (applicationContext != null && connection != null && wasAttached) {
            try {
                applicationContext.unbindService(connection);
            } catch (Throwable ignore) {
            }
        }
        stopTransports();
    }

    private synchronized void stopTransports() {
        if (transportController != null) {
            for (String transport : new ArrayList<>(activeTransports)) {
                try {
                    transportController.stop(transport);
                } catch (Throwable error) {
                    FileLog.e("Unable to stop Tor transport " + transport + ": " + safeError(error));
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

    private ServiceConnection createServiceConnection(final int generation) {
        return new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                TorService connectedService;
                try {
                    connectedService = ((TorService.LocalBinder) service).getService();
                } catch (Throwable error) {
                    onAttemptFailure(generation, "Некорректный TorService: " + safeError(error));
                    return;
                }
                boolean stale;
                synchronized (AgramTorManager.this) {
                    stale = generation != startGeneration || activeServiceConnection != this;
                    if (!stale) {
                        binding = false;
                        bound = true;
                        torService = connectedService;
                        scheduleBootstrapPoll(generation, 0);
                    }
                }
                if (stale && applicationContext != null) {
                    try {
                        applicationContext.unbindService(this);
                    } catch (Throwable ignore) {
                    }
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                boolean current;
                synchronized (AgramTorManager.this) {
                    current = generation == startGeneration && activeServiceConnection == this;
                    if (current) {
                        torService = null;
                        binding = false;
                        bound = false;
                        activeServiceConnection = null;
                    }
                }
                if (current) {
                    onAttemptFailure(generation, "Встроенный TorService неожиданно отключился");
                }
            }
        };
    }

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String servicePackage = intent.getStringExtra(TorService.EXTRA_SERVICE_PACKAGE_NAME);
            if (servicePackage != null && !context.getPackageName().equals(servicePackage)) {
                return;
            }
            int generation;
            boolean ignoreOldService;
            synchronized (AgramTorManager.this) {
                generation = startGeneration;
                ignoreOldService = STATE_STARTING.equals(state)
                        && SystemClock.elapsedRealtime() < ignoreServiceBroadcastsUntil;
            }
            if (TorService.ACTION_ERROR.equals(intent.getAction())) {
                String message = intent.getStringExtra(Intent.EXTRA_TEXT);
                String error = AgramTorBridgePool.sanitizeDiagnostic(TextUtils.isEmpty(message)
                        ? "Tor завершился с неизвестной ошибкой" : message);
                FileLog.e("Embedded Tor error: " + error);
                if (ignoreOldService) {
                    lastError = error;
                    notifyListeners();
                } else {
                    onAttemptFailure(generation, error);
                }
                return;
            }
            if (!TorService.ACTION_STATUS.equals(intent.getAction())) {
                return;
            }
            String status = intent.getStringExtra(TorService.EXTRA_STATUS);
            if (TorService.STATUS_ON.equals(status)) {
                scheduleBootstrapPoll(generation, 0);
            } else if (TorService.STATUS_STARTING.equals(status)) {
                if (!STATE_READY.equals(state)) {
                    updateState(STATE_STARTING, 0);
                }
                scheduleBootstrapPoll(generation, 0);
            } else if (TorService.STATUS_OFF.equals(status) || TorService.STATUS_STOPPING.equals(status)) {
                if (!ignoreOldService) {
                    onAttemptFailure(generation, "TorService остановился до завершения подключения");
                }
            }
        }
    };

    private static String safeError(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        String message = error.getMessage();
        return AgramTorBridgePool.sanitizeDiagnostic(
                TextUtils.isEmpty(message) ? error.getClass().getSimpleName() : message);
    }
}
