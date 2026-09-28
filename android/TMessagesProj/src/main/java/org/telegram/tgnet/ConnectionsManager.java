package org.telegram.tgnet;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageInfo;
import android.os.AsyncTask;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Base64;
import android.widget.Toast;

import androidx.annotation.Keep;
import androidx.annotation.IntDef;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;
import com.google.android.gms.tasks.Task;
import com.google.android.play.core.integrity.IntegrityManager;
import com.google.android.play.core.integrity.IntegrityManagerFactory;
import com.google.android.play.core.integrity.IntegrityTokenRequest;
import com.google.android.play.core.integrity.IntegrityTokenResponse;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AgramContainerManager;
import org.telegram.messenger.AgramNetworkController;
import org.telegram.messenger.AgramNativeOwnerState;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BaseController;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.CaptchaController;
import org.telegram.messenger.EmuDetector;
import org.telegram.messenger.FileLoadOperation;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.FileUploadOperation;
import org.telegram.messenger.KeepAliveJob;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.PushListenerController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.StatsController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.utils.proxy.ProxySettings;
import org.telegram.ui.Components.VideoPlayer;
import org.telegram.ui.LoginActivity;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLConnection;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TimeZone;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLException;
public class ConnectionsManager extends BaseController {

    public final static int ConnectionTypeGeneric = 1;
    public final static int ConnectionTypeDownload = 2;
    public final static int ConnectionTypeUpload = 4;
    public final static int ConnectionTypePush = 8;
    public final static int ConnectionTypeDownload2 = ConnectionTypeDownload | (1 << 16);

    public final static int FileTypePhoto = 0x01000000;
    public final static int FileTypeVideo = 0x02000000;
    public final static int FileTypeAudio = 0x03000000;
    public final static int FileTypeFile = 0x04000000;

    public final static int RequestFlagEnableUnauthorized = 1;
    public final static int RequestFlagFailOnServerErrors = 2;
    public final static int RequestFlagCanCompress = 4;
    public final static int RequestFlagWithoutLogin = 8;
    public final static int RequestFlagTryDifferentDc = 16;
    public final static int RequestFlagForceDownload = 32;
    public final static int RequestFlagInvokeAfter = 64;
    public final static int RequestFlagNeedQuickAck = 128;
    public final static int RequestFlagDoNotWaitFloodWait = 1024;
    public final static int RequestFlagListenAfterCancel = 2048;
    public final static int RequestFlagFailOnServerErrorsExceptFloodWait = 65536;

    public final static int ConnectionStateConnecting = 1;
    public final static int ConnectionStateWaitingForNetwork = 2;
    public final static int ConnectionStateConnected = 3;
    public final static int ConnectionStateConnectingToProxy = 4;
    public final static int ConnectionStateUpdating = 5;
    /** Local MTProto auth/config files are unavailable; this is not a network outage. */
    public final static int ConnectionStateLocalAuthUnavailable = 6;

    public static final String LOCAL_AUTH_STATUS_READY = "ready";
    public static final String LOCAL_AUTH_STATUS_UNAVAILABLE = "local_auth_unavailable";

    public final static int LogoutReasonLocalConfigMismatch = 0;
    public final static int LogoutReasonServerAuthRejected = 1;

    @IntDef({LogoutReasonLocalConfigMismatch, LogoutReasonServerAuthRejected})
    @Retention(RetentionPolicy.SOURCE)
    public @interface LogoutReason {
    }

    public final static byte USE_IPV4_ONLY = 0;
    public final static byte USE_IPV6_ONLY = 1;
    public final static byte USE_IPV4_IPV6_RANDOM = 2;

    private static final long[] lastDnsRequestTimes = new long[UserConfig.MAX_ACCOUNT_COUNT];
    private static final String[] dnsConfigOwners = new String[UserConfig.MAX_ACCOUNT_COUNT];

    public final static int DEFAULT_DATACENTER_ID = Integer.MAX_VALUE;

    private long lastPauseTime = System.currentTimeMillis();
    private boolean appPaused = true;
    private boolean isUpdating;
    private int connectionState;
    private volatile boolean localAuthConfigQuarantined;
    private volatile String localAuthConfigStatus = LOCAL_AUTH_STATUS_READY;
    private final AgramNativeOwnerState nativeOwner = new AgramNativeOwnerState();
    private OwnerTransition ownerTransition;
    private String durablyRetiredContainerId;
    private AtomicInteger lastRequestToken = new AtomicInteger(1);
    private int appResumeCount;

    private String compatibleDeviceModel;
    private String compatibleSystemVersion;
    private String compatibleAppVersion;
    private String compatibleLangCode;
    private String compatibleSystemLangCode;
    private int compatibleTimezoneOffset;

    private static final AsyncTask[] currentDnsTasks = new AsyncTask[UserConfig.MAX_ACCOUNT_COUNT];

    private static HashMap<String, ResolveHostByNameTask> resolvingHostnameTasks = new HashMap<>();

    public static final Executor DNS_THREAD_POOL_EXECUTOR;
    public static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();
    private static final int CORE_POOL_SIZE = Math.max(2, Math.min(CPU_COUNT - 1, 4));
    private static final int MAXIMUM_POOL_SIZE = CPU_COUNT * 2 + 1;
    private static final int KEEP_ALIVE_SECONDS = 30;
    private static final BlockingQueue<Runnable> sPoolWorkQueue = new LinkedBlockingQueue<>(128);
    private static final ThreadFactory sThreadFactory = new ThreadFactory() {
        private final AtomicInteger mCount = new AtomicInteger(1);

        public Thread newThread(Runnable r) {
            return new Thread(r, "DnsAsyncTask #" + mCount.getAndIncrement());
        }
    };

    private boolean forceTryIpV6;

    static {
        ThreadPoolExecutor threadPoolExecutor = new ThreadPoolExecutor(CORE_POOL_SIZE, MAXIMUM_POOL_SIZE, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS, sPoolWorkQueue, sThreadFactory);
        threadPoolExecutor.allowCoreThreadTimeOut(true);
        DNS_THREAD_POOL_EXECUTOR = threadPoolExecutor;
    }

    public void setForceTryIpV6(boolean forceTryIpV6) {
        if (this.forceTryIpV6 != forceTryIpV6) {
            this.forceTryIpV6 = forceTryIpV6;
            checkConnection();
        }
    }

    public void discardConnection(int dcId, int connectionType) {
        Utilities.stageQueue.postRunnable(() -> {
            native_discardConnection(currentAccount, dcId, connectionType);
        });
    }

    public void failNotRunningRequest(int requestToken) {
        Utilities.stageQueue.postRunnable(() -> {
            native_failNotRunningRequest(currentAccount, requestToken);
        });
    }

    private static class ResolvedDomain {

        public ArrayList<String> addresses;
        long ttl;

        public ResolvedDomain(ArrayList<String> a, long t) {
            addresses = a;
            ttl = t;
        }

        public String getAddress() {
            return addresses.get(Utilities.random.nextInt(addresses.size()));
        }
    }

    private static HashMap<String, ResolvedDomain> dnsCache = new HashMap<>();

    /** Immutable owner of DNS work; capturing it never creates a native transport. */
    private static final class DnsOwner {
        final int account;
        final String containerId;
        final long generation;
        final String key;
        final String phone;

        private DnsOwner(int account, String containerId, long generation, String phone) {
            this.account = account;
            this.containerId = containerId;
            this.generation = generation;
            this.phone = phone;
            key = account + ":" + containerId + ":" + generation;
        }

        static DnsOwner capture(int account) {
            if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) return null;
            try {
                UserConfig config = UserConfig.getInstance(account);
                long generation = config.getSessionGeneration();
                AgramContainerManager.ContainerRecord record = AgramContainerManager.getInstance().getContainer(account);
                if (record == null || !record.isStorageAccessible() || !config.isSessionGenerationCurrent(generation)) return null;
                return new DnsOwner(account, record.id, generation, config.getClientPhone());
            } catch (RuntimeException e) {
                FileLog.e("Unable to capture DNS owner; local storage retained", e);
                return null;
            }
        }

        boolean permitsDirectHttp() {
            return AgramNetworkController.isDirectHttpOwnerCurrent(account, containerId, generation);
        }

        boolean permitsProxyBootstrap(String host) {
            try {
                AgramContainerManager.ContainerRecord current = AgramContainerManager.getInstance().getContainer(account);
                return current != null && current.isStorageAccessible() && containerId.equals(current.id)
                        && UserConfig.getInstance(account).isSessionGenerationCurrent(generation)
                        && isAgramAuthTransportReady(account)
                        && AgramContainerManager.getInstance().isCurrentContainer(account, containerId)
                        && AgramContainerManager.NETWORK_PROXY.equals(current.proxyMode)
                        && current.proxyAddress != null && current.proxyAddress.trim().equalsIgnoreCase(host);
            } catch (RuntimeException e) {
                FileLog.e("DNS bootstrap paused while local storage is unavailable", e);
                return false;
            }
        }
    }

    private static int lastClassGuid = 1;
    
    private static final ConnectionsManager[] Instance = new ConnectionsManager[UserConfig.MAX_ACCOUNT_COUNT];
    public static ConnectionsManager getInstance(int num) {
        ConnectionsManager localInstance = Instance[num];
        if (localInstance == null) {
            synchronized (ConnectionsManager.class) {
                localInstance = Instance[num];
                if (localInstance == null) {
                    Instance[num] = localInstance = new ConnectionsManager(num);
                    localInstance.schedulePendingNativeRetirement();
                }
            }
        }
        return localInstance;
    }

    public ConnectionsManager(int instance) {
        super(instance);
        connectionState = native_getConnectionState(currentAccount);
        String deviceModel;
        String systemLangCode;
        String langCode;
        String appVersion;
        String systemVersion;
        File config = ApplicationLoader.getFilesDirFixed();
        if (instance != 0) {
            config = new File(config, "account" + instance);
            config.mkdirs();
        }
        String configPath = config.toString();
        boolean enablePushConnection = isPushConnectionEnabled();
        try {
            systemLangCode = LocaleController.getSystemLocaleStringIso639().toLowerCase();
            langCode = LocaleController.getLocaleStringIso639().toLowerCase();
            String manufacturer = TextUtils.isEmpty(Build.MANUFACTURER) ? "Android" : Build.MANUFACTURER.trim();
            String model = TextUtils.isEmpty(Build.MODEL) ? "device" : Build.MODEL.trim();
            deviceModel = model.toLowerCase(Locale.US).startsWith(manufacturer.toLowerCase(Locale.US))
                    ? model
                    : manufacturer + " " + model;
            PackageInfo pInfo = ApplicationLoader.applicationContext.getPackageManager().getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0);
            appVersion = pInfo.versionName + " (" + pInfo.versionCode + ")";
            if (BuildVars.DEBUG_PRIVATE_VERSION) {
                appVersion += " pbeta";
            } else if (BuildVars.DEBUG_VERSION) {
                appVersion += " beta";
            }
            systemVersion = "Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")";
        } catch (Exception e) {
            systemLangCode = "en";
            langCode = "";
            deviceModel = "Android unknown";
            appVersion = "App version unknown";
            systemVersion = "SDK " + Build.VERSION.SDK_INT;
        }
        if (systemLangCode.trim().length() == 0) {
            systemLangCode = "en";
        }
        if (deviceModel.trim().length() == 0) {
            deviceModel = "Android unknown";
        }
        if (appVersion.trim().length() == 0) {
            appVersion = "App version unknown";
        }
        if (systemVersion.trim().length() == 0) {
            systemVersion = "SDK Unknown";
        }
        getUserConfig().loadConfig();
        String pushString = getRegId();
        String fingerprint = AndroidUtilities.getCertificateSHA256Fingerprint();

        int timezoneOffset = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000;
        compatibleDeviceModel = deviceModel;
        compatibleSystemVersion = systemVersion;
        compatibleAppVersion = appVersion;
        compatibleLangCode = langCode;
        compatibleSystemLangCode = systemLangCode;
        compatibleTimezoneOffset = timezoneOffset;
        AgramContainerManager.SessionProfile sessionProfile = AgramContainerManager.getInstance().resolveSessionProfile(
                currentAccount,
                deviceModel,
                systemVersion,
                appVersion,
                langCode,
                systemLangCode,
                timezoneOffset
        );
        deviceModel = sessionProfile.deviceModel;
        systemVersion = sessionProfile.systemVersion;
        appVersion = sessionProfile.appVersion;
        langCode = sessionProfile.languageCode;
        systemLangCode = sessionProfile.systemLanguageCode;
        timezoneOffset = sessionProfile.timezoneOffset;
        SharedPreferences mainPreferences;
        if (currentAccount == 0) {
            mainPreferences = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE);
        } else {
            mainPreferences = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig" + currentAccount, Activity.MODE_PRIVATE);
        }
        forceTryIpV6 = mainPreferences.getBoolean("forceTryIpV6", false);
        boolean userPremium = false;
        if (getUserConfig().getCurrentUser() != null) {
            userPremium = getUserConfig().getCurrentUser().premium;
        }
        init(SharedConfig.buildVersion(), TLRPC.LAYER, BuildVars.APP_ID, deviceModel, systemVersion, appVersion, langCode, systemLangCode, configPath, FileLog.getNetworkLogPath(), pushString, fingerprint, timezoneOffset, getUserConfig().getClientUserId(), userPremium, enablePushConnection);
    }

    /**
     * Re-applies a profile saved by the offline container screen. The network
     * singleton may already exist because Intro requests remote configuration;
     * resetting the native init version guarantees that the authorization
     * request is wrapped in a fresh initConnection with the selected labels.
     */
    public void applyAgramSessionProfile() {
        if (!ensureNativeContainerOwner()) return;
        AgramContainerManager.SessionProfile profile = AgramContainerManager.getInstance().resolveSessionProfile(
                currentAccount,
                compatibleDeviceModel,
                compatibleSystemVersion,
                compatibleAppVersion,
                compatibleLangCode,
                compatibleSystemLangCode,
                compatibleTimezoneOffset
        );
        final RequestOwner owner = captureRequestOwner(false);
        synchronized (nativeOwner) {
        if (owner == null || !owner.isCurrent()) return;
        native_setSessionProfile(
                currentAccount,
                profile.deviceModel,
                profile.systemVersion,
                profile.appVersion,
                profile.languageCode,
                profile.systemLanguageCode,
                profile.timezoneOffset
        );
        }
    }

    private final class RequestOwner {
        final String containerId;
        final long generation;
        final boolean logoutRequest;
        RequestOwner(String containerId, long generation, boolean logoutRequest) {
            this.containerId = containerId;
            this.generation = generation;
            this.logoutRequest = logoutRequest;
        }
        boolean isCurrent() {
            return isCurrent(true);
        }
        boolean isCurrent(boolean checkNative) {
            try {
                AgramContainerManager manager = AgramContainerManager.getInstance();
                AgramContainerManager.ContainerRecord record = manager.getContainer(currentAccount);
                return !localAuthConfigQuarantined && nativeOwner.isReadyFor(containerId)
                        && getUserConfig().isSessionGenerationCurrent(generation)
                        && record != null && record.isStorageAccessible() && containerId.equals(record.id)
                        && manager.isCurrentContainer(currentAccount, containerId)
                        && (logoutRequest || !manager.hasPendingNativeRetirement(currentAccount, containerId)
                            || getUserConfig().hasPersistedSession())
                        && (!checkNative || native_isContainerOwnerReady(currentAccount, containerId));
            } catch (RuntimeException unavailable) {
                return false;
            }
        }
    }

    private RequestOwner captureRequestOwner(boolean logoutRequest) {
        try {
            RequestOwner owner = new RequestOwner(nativeOwner.owner(),
                    getUserConfig().getSessionGeneration(), logoutRequest);
            return owner.isCurrent() ? owner : null;
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private static final class OwnerTransition {
        final long id, generation;
        final String previous, target;
        final Runnable completion;
        OwnerTransition(long id, long generation, String previous, String target, Runnable completion) {
            this.id = id;
            this.generation = generation;
            this.previous = previous;
            this.target = target;
            this.completion = completion;
        }
    }

    /** A saved profile may activate only an empty, durably retired native slot. */
    public boolean ensureNativeContainerOwner() {
        try {
            AgramContainerManager manager = AgramContainerManager.getInstance();
            AgramContainerManager.ContainerRecord record = manager.getContainer(currentAccount);
            if (record == null || !record.isStorageAccessible()) return false;
            if (manager.hasPendingNativeRetirement(currentAccount, record.id)
                    && !getUserConfig().hasPersistedSession()) {
                schedulePendingNativeRetirement();
                return false;
            }
            synchronized (nativeOwner) {
                if (captureRequestOwner(false) != null) return true;
                if (nativeOwner.isPending() || !TextUtils.isEmpty(nativeOwner.owner())) return false;
                beginOwnerTransition("", record.id, getUserConfig().getSessionGeneration(),
                        () -> {
                            applyAgramSessionProfile();
                            AgramNetworkController.getInstance().prepare(currentAccount);
                            checkConnection();
                            native_resumeNetwork(currentAccount, false);
                        });
            }
        } catch (RuntimeException unavailable) {
            localAuthConfigStatus = "native_owner_unavailable";
        }
        return false;
    }

    private void beginOwnerTransition(String previous, String target, long generation, Runnable completion) {
        long id = nativeOwner.begin(target, generation);
        ownerTransition = new OwnerTransition(id, generation, previous, target, completion);
        localAuthConfigStatus = "native_owner_pending";
        requestCallbacks.clear();
        native_transitionContainerOwner(currentAccount, previous, target, id);
    }

    /** Explicit logout only: container deletion follows the native durable retirement ack. */
    public void retireAgramContainer(String expectedId, long generation, Runnable completion) {
        boolean alreadyRetired = false;
        try {
            AgramContainerManager manager = AgramContainerManager.getInstance();
            if (!getUserConfig().isSessionGenerationCurrent(generation)
                    || getUserConfig().hasPersistedSession() || getUserConfig().isClientActivated()
                    || !manager.hasPendingNativeRetirement(currentAccount, expectedId)
                    || !manager.isContainerAccessible(currentAccount)) return;
            synchronized (nativeOwner) {
                if (nativeOwner.isPending()) return;
                if (TextUtils.isEmpty(nativeOwner.owner()) && TextUtils.equals(expectedId, durablyRetiredContainerId)) {
                    alreadyRetired = true;
                } else if (TextUtils.equals(nativeOwner.owner(), expectedId)) {
                    beginOwnerTransition(expectedId, "", generation, () -> finishNativeRetirement(expectedId, completion));
                }
            }
        } catch (RuntimeException unavailable) {
            localAuthConfigStatus = "native_owner_unavailable";
        }
        if (alreadyRetired) finishNativeRetirement(expectedId, completion);
    }

    private void finishNativeRetirement(String previous, Runnable completion) {
        AgramContainerManager manager = AgramContainerManager.getInstance();
        if (manager.acknowledgeNativeRetirement(currentAccount, previous)
                && manager.deleteContainer(currentAccount, previous)) {
            if (completion != null) completion.run();
        } else {
            localAuthConfigStatus = "native_owner_unavailable";
            getNotificationCenter().postNotificationName(NotificationCenter.agramContainerPersistenceFailed,
                    currentAccount, "native_retirement");
        }
    }

    private void schedulePendingNativeRetirement() {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                AgramContainerManager manager = AgramContainerManager.getInstance();
                AgramContainerManager.ContainerRecord record = manager.getContainer(currentAccount);
                if (record != null && manager.hasPendingNativeRetirement(currentAccount, record.id)
                        && !getUserConfig().hasPersistedSession()) {
                    retireAgramContainer(record.id, getUserConfig().getSessionGeneration(), () ->
                            getNotificationCenter().postNotificationName(NotificationCenter.appDidLogout));
                }
            } catch (RuntimeException unavailable) {
                localAuthConfigStatus = "native_owner_unavailable";
            }
        }, 1);
    }

    public static void onNativeOwnerTransition(int account, long transitionId, boolean success) {
        // Startup does not use callbacks: never recursively construct a singleton here.
        ConnectionsManager existing = account >= 0 && account < Instance.length ? Instance[account] : null;
        if (existing == null) return;
        AndroidUtilities.runOnUIThread(() -> existing.completeOwnerTransition(transitionId, success));
    }

    private void completeOwnerTransition(long transitionId, boolean success) {
        Runnable completion = null;
        synchronized (nativeOwner) {
            OwnerTransition transition = ownerTransition;
            if (transition == null || transition.id != transitionId) return;
            try {
                AgramContainerManager manager = AgramContainerManager.getInstance();
                String expectedId = TextUtils.isEmpty(transition.target) ? transition.previous : transition.target;
                boolean current = manager.isCurrentContainer(currentAccount, expectedId)
                        && manager.isContainerAccessible(currentAccount)
                        && (TextUtils.isEmpty(transition.target) ? !getUserConfig().hasPersistedSession()
                            : !manager.hasPendingNativeRetirement(currentAccount, expectedId));
                boolean nativeReady = !TextUtils.isEmpty(transition.target)
                        && native_isContainerOwnerReady(currentAccount, transition.target);
                if (!nativeOwner.complete(transition.id, current ? transition.target : null,
                        getUserConfig().getSessionGeneration(), success, nativeReady)) {
                    localAuthConfigStatus = "native_owner_unavailable";
                    return;
                }
                ownerTransition = null;
                if (TextUtils.isEmpty(transition.target)) durablyRetiredContainerId = transition.previous;
                localAuthConfigQuarantined = false;
                localAuthConfigStatus = TextUtils.isEmpty(transition.target) ? "native_owner_retired" : LOCAL_AUTH_STATUS_READY;
                completion = transition.completion;
            } catch (RuntimeException unavailable) {
                localAuthConfigStatus = "native_owner_unavailable";
            }
        }
        // Storage/archive retirement can wait for workers that probe ownership.
        // Never retain the owner monitor across that barrier or UI callbacks.
        if (completion != null) completion.run();
    }

    private String getRegId() {
        // No shared device token or status marker in initConnection.params.
        // Per-container Simple Push is registered separately via token_type 4.
        return "";
    }

    public boolean isPushConnectionEnabled() {
        SharedPreferences preferences = MessagesController.getGlobalNotificationsSettings();
        if (preferences.contains("pushConnection")) {
            return preferences.getBoolean("pushConnection", true);
        } else {
            return MessagesController.getMainSettings(UserConfig.selectedAccount).getBoolean("backgroundConnection", false);
        }
    }

    public long getCurrentTimeMillis() {
        return native_getCurrentTimeMillis(currentAccount);
    }

    public int getCurrentTime() {
        return native_getCurrentTime(currentAccount);
    }

    public int getCurrentDatacenterId() {
        return native_getCurrentDatacenterId(currentAccount);
    }

    public long getCurrentAuthKeyId() {
        return native_getCurrentAuthKeyId(currentAccount);
    }

    public int getTimeDifference() {
        return native_getTimeDifference(currentAccount);
    }

    public <T extends TLObject> int sendRequestTyped(TLMethod<T> method, Utilities.Callback2<T, TLRPC.TL_error> completionBlock) {
        return sendRequestTyped(method, null, completionBlock);
    }
    public <T extends TLObject> int sendRequestTyped(TLMethod<T> method, Executor executor, Utilities.Callback2<T, TLRPC.TL_error> completionBlock) {
        return sendRequestTyped(method, executor, completionBlock, DEFAULT_DATACENTER_ID, 0);
    }
    public <T extends TLObject> int sendRequestTyped(TLMethod<T> method, Executor executor, Utilities.Callback2<T, TLRPC.TL_error> completionBlock, int requestFlags) {
        return sendRequestTyped(method, executor, completionBlock, DEFAULT_DATACENTER_ID, requestFlags);
    }
    public <T extends TLObject> int sendRequestTyped(TLMethod<T> method, Executor executor, Utilities.Callback2<T, TLRPC.TL_error> completionBlock, int dcId, int requestFlags) {
        final RequestOwner owner = captureRequestOwner(false);
        return sendRequest(method, (res, err) -> {
            //noinspection unchecked
            T result = (T) res;
            if (executor != null) {
                executor.execute(() -> { if (owner != null && owner.isCurrent()) completionBlock.run(result, err); });
            } else {
                completionBlock.run(result, err);
            }
        }, null, null, null, requestFlags, dcId, ConnectionTypeGeneric, true);
    }



    public int sendRequestTypedAndProcessUpdates(TLMethod<TLRPC.Updates> method, Executor executor, Utilities.Callback2<TLRPC.Updates, TLRPC.TL_error> completionBlock) {
        return sendRequestTypedAndProcessUpdates(method, executor, completionBlock, DEFAULT_DATACENTER_ID, 0);
    }

    public int sendRequestTypedAndProcessUpdates(TLMethod<TLRPC.Updates> method, Executor executor, Utilities.Callback2<TLRPC.Updates, TLRPC.TL_error> completionBlock, int dcId, int requestFlags) {
        final RequestOwner owner = captureRequestOwner(false);
        return sendRequestTyped(method, null, (result, err) -> {
            if (result != null) {
                getMessagesController().processUpdates(result, false);
            }
            if (executor != null) {
                executor.execute(() -> { if (owner != null && owner.isCurrent()) completionBlock.run(result, err); });
            } else {
                completionBlock.run(result, err);
            }
        }, dcId, requestFlags);
    }


    public int sendRequest(TLObject object, RequestDelegate completionBlock) {
        return sendRequest(object, completionBlock, null, 0);
    }

    public int sendRequest(TLObject object, RequestDelegate completionBlock, int flags) {
        return sendRequest(object, completionBlock, null, null, null, flags, DEFAULT_DATACENTER_ID, ConnectionTypeGeneric, true);
    }

    public int sendRequest(TLObject object, RequestDelegate completionBlock, int flags, int connectionType) {
        return sendRequest(object, completionBlock, null, null, null, flags, DEFAULT_DATACENTER_ID, connectionType, true);
    }

    public int sendRequest(TLObject object, RequestDelegateTimestamp completionBlock, int flags, int connectionType, int datacenterId) {
        return sendRequest(object, null, completionBlock, null, null, flags, datacenterId, connectionType, true);
    }

    public int sendRequest(TLObject object, RequestDelegate completionBlock, QuickAckDelegate quickAckBlock, int flags) {
        return sendRequest(object, completionBlock, null, quickAckBlock, null, flags, DEFAULT_DATACENTER_ID, ConnectionTypeGeneric, true);
    }

    public int sendRequest(final TLObject object, final RequestDelegate onComplete, final QuickAckDelegate onQuickAck, final WriteToSocketDelegate onWriteToSocket, final int flags, final int datacenterId, final int connectionType, final boolean immediate) {
        return sendRequest(object, onComplete, null, onQuickAck, onWriteToSocket, flags, datacenterId, connectionType, immediate);
    }

    public int sendRequestSync(final TLObject object, final RequestDelegate onComplete, final QuickAckDelegate onQuickAck, final WriteToSocketDelegate onWriteToSocket, final int flags, final int datacenterId, final int connectionType, final boolean immediate) {
        final int requestToken = lastRequestToken.getAndIncrement();
        final RequestOwner owner = captureRequestOwner(object instanceof TLRPC.TL_auth_logOut);
        if (owner == null) {
            rejectUnavailableRequest(object, onComplete, null);
            return requestToken;
        }
        sendRequestInternal(owner, object, onComplete, null, onQuickAck, onWriteToSocket, flags, datacenterId, connectionType, immediate, requestToken);
        return requestToken;
    }

    public int sendRequest(final TLObject object, final RequestDelegate onComplete, final RequestDelegateTimestamp onCompleteTimestamp, final QuickAckDelegate onQuickAck, final WriteToSocketDelegate onWriteToSocket, final int flags, final int datacenterId, final int connectionType, final boolean immediate) {
        final int requestToken = lastRequestToken.getAndIncrement();
        final RequestOwner owner = captureRequestOwner(object instanceof TLRPC.TL_auth_logOut);
        if (owner == null) {
            rejectUnavailableRequest(object, onComplete, onCompleteTimestamp);
            return requestToken;
        }
        Utilities.stageQueue.postRunnable(() -> {
            sendRequestInternal(owner, object, onComplete, onCompleteTimestamp, onQuickAck, onWriteToSocket, flags, datacenterId, connectionType, immediate, requestToken);
        });
        return requestToken;
    }

    private void rejectUnavailableRequest(TLObject object, RequestDelegate onComplete, RequestDelegateTimestamp timestampCallback) {
        object.freeResources();
        try {
            final long generation = getUserConfig().getSessionGeneration();
            final AgramContainerManager manager = AgramContainerManager.getInstance();
            final AgramContainerManager.ContainerRecord record = manager.getContainer(currentAccount);
            if (record == null) return;
            final String containerId = record.id;
            Utilities.stageQueue.postRunnable(() -> {
                try {
                    if (!getUserConfig().isSessionGenerationCurrent(generation)
                            || !manager.isCurrentContainer(currentAccount, containerId)) return;
                    TLRPC.TL_error error = new TLRPC.TL_error();
                    error.code = -2000;
                    error.text = "AGRAM_NATIVE_OWNER_UNAVAILABLE";
                    if (onComplete != null) onComplete.run(null, error);
                    else if (timestampCallback != null) timestampCallback.run(null, error, 0);
                } catch (RuntimeException unavailable) {
                    // A local storage fault cannot turn an ownerless callback into a new session's work.
                }
            });
        } catch (RuntimeException unavailable) {
            // No immutable owner snapshot is available; do not deliver a callback to an arbitrary slot.
        }
    }

    private void sendRequestInternal(RequestOwner owner, TLObject object, RequestDelegate onComplete, RequestDelegateTimestamp onCompleteTimestamp, QuickAckDelegate onQuickAck, WriteToSocketDelegate onWriteToSocket, int flags, int datacenterId, int connectionType, boolean immediate, int requestToken) {
        if (!owner.isCurrent()) {
            object.freeResources();
            return;
        }
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("send request " + object + " with token = " + requestToken);
        }
        try {
            NativeByteBuffer buffer = new NativeByteBuffer(object.getObjectSize());
            object.serializeToStream(buffer);
            object.freeResources();

            long startRequestTime = 0;
            if (BuildVars.DEBUG_PRIVATE_VERSION && BuildVars.LOGS_ENABLED || (connectionType & ConnectionTypeDownload) != 0) {
                startRequestTime = System.currentTimeMillis();
            }
            long finalStartRequestTime = startRequestTime;
            listen(requestToken, (response, errorCode, errorText, networkType, timestamp, requestMsgId, dcId) -> {
                if (!owner.isCurrent()) return;
                try {
                    TLObject resp = null;
                    TLRPC.TL_error error = null;
                    int responseSize = 0;
                    if (response != 0) {
                        NativeByteBuffer buff = NativeByteBuffer.wrap(response);
                        buff.setDataSourceType(TLDataSourceType.NETWORK);
                        buff.reused = true;
                        responseSize = buff.limit();
                        int magic = buff.readInt32(true);
                        try {
                            resp = object.deserializeResponse(buff, magic, true);
                        } catch (Exception e2) {
                            if (BuildVars.DEBUG_PRIVATE_VERSION) {
                                throw e2;
                            }
                            FileLog.fatal(e2);
                            return;
                        }
                    } else if (errorText != null) {
                        error = new TLRPC.TL_error();
                        error.code = errorCode;
                        error.text = errorText;
                        if (BuildVars.LOGS_ENABLED && error.code != -2000) {
                            FileLog.e(object + " got error " + error.code + " " + error.text);
                        }
                    }
                    if ((connectionType & ConnectionTypeDownload) != 0 && VideoPlayer.activePlayers.isEmpty()) {
                        long ping_time = native_getCurrentPingTime(currentAccount);
                        final long size = responseSize;
                        final long delta = Math.max(0, (System.currentTimeMillis() - finalStartRequestTime) - ping_time);
                        DefaultBandwidthMeter.getSingletonInstance(ApplicationLoader.applicationContext).onTransfer(size, delta);
                    }
                    if (BuildVars.DEBUG_PRIVATE_VERSION && !getUserConfig().isClientActivated() && error != null && error.code == 400 && Objects.equals(error.text, "CONNECTION_NOT_INITED")) {
                        if (BuildVars.LOGS_ENABLED) {
                            FileLog.d("Cleanup keys for " + currentAccount + " because of CONNECTION_NOT_INITED");
                        }
                        cleanup(true);
                        sendRequest(object, onComplete, onCompleteTimestamp, onQuickAck, onWriteToSocket, flags, datacenterId, connectionType, immediate);
                        return;
                    }
                    if (resp != null) {
                        resp.networkType = networkType;
                    }
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("java received " + resp + (error != null ? " error = " + error : "") + " messageId = 0x" + Long.toHexString(requestMsgId));
                        FileLog.dumpResponseAndRequest(currentAccount, object, resp, error, requestMsgId, finalStartRequestTime, requestToken);
                    }
                    final TLObject finalResponse = resp;
                    final TLRPC.TL_error finalError = error;
                    Utilities.stageQueue.postRunnable(() -> {
                        if (!owner.isCurrent()) {
                            if (finalResponse != null) finalResponse.freeResources();
                            return;
                        }
                        if (onComplete != null) {
                            onComplete.run(finalResponse, finalError);
                        } else if (onCompleteTimestamp != null) {
                            onCompleteTimestamp.run(finalResponse, finalError, timestamp);
                        } else if (finalResponse instanceof TLRPC.Updates) {
                            KeepAliveJob.finishJob();
                            AccountInstance.getInstance(currentAccount).getMessagesController().processUpdates((TLRPC.Updates) finalResponse, false);
                        }
                        if (finalResponse != null) {
                            finalResponse.freeResources();
                        }
                    });
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }, onQuickAck == null ? null : () -> { if (owner.isCurrent()) onQuickAck.run(); },
                    onWriteToSocket == null ? null : () -> { if (owner.isCurrent()) onWriteToSocket.run(); });
            synchronized (nativeOwner) {
                if (!owner.isCurrent()) {
                    requestCallbacks.remove(requestToken);
                    buffer.reuse();
                    return;
                }
                native_sendRequest(currentAccount, buffer.address, flags, datacenterId, connectionType, immediate, requestToken);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private final ConcurrentHashMap<Integer, RequestCallbacks> requestCallbacks = new ConcurrentHashMap<>();
    private static class RequestCallbacks {
        public RequestDelegateInternal onComplete;
        public QuickAckDelegate onQuickAck;
        public WriteToSocketDelegate onWriteToSocket;
        public Runnable onCancelled;
        public RequestCallbacks(RequestDelegateInternal onComplete, QuickAckDelegate onQuickAck, WriteToSocketDelegate onWriteToSocket) {
            this.onComplete = onComplete;
            this.onQuickAck = onQuickAck;
            this.onWriteToSocket = onWriteToSocket;
        }
    }

    private void listen(int requestToken, RequestDelegateInternal onComplete, QuickAckDelegate onQuickAck, WriteToSocketDelegate onWriteToSocket) {
        requestCallbacks.put(requestToken, new RequestCallbacks(onComplete, onQuickAck, onWriteToSocket));
//        FileLog.d("{rc} listen(" + currentAccount + ", " + requestToken + "): " + requestCallbacks.size() + " requests' callbacks");
    }

    private void listenCancel(int requestToken, Runnable onCancelled) {
        RequestCallbacks callbacks = requestCallbacks.get(requestToken);
        if (callbacks != null) {
            callbacks.onCancelled = onCancelled;
//            FileLog.d("{rc} listenCancel(" + currentAccount + ", " + requestToken + "): " + requestCallbacks.size() + " requests' callbacks");
        } else {
//            FileLog.d("{rc} listenCancel(" + currentAccount + ", " + requestToken + "): callback not found, " + requestCallbacks.size() + " requests' callbacks");
        }
    }

    public static void onRequestClear(int currentAccount, int requestToken, boolean cancelled) {
        ConnectionsManager connectionsManager = currentAccount >= 0 && currentAccount < Instance.length ? Instance[currentAccount] : null;
        if (connectionsManager == null) return;
        RequestCallbacks callbacks = connectionsManager.requestCallbacks.get(requestToken);
        if (cancelled) {
            if (callbacks != null) {
                if (callbacks.onCancelled != null) {
                    callbacks.onCancelled.run();
                }
                connectionsManager.requestCallbacks.remove(requestToken);
//                FileLog.d("{rc} onRequestClear(" + currentAccount + ", " + requestToken + ", " + cancelled + "): request to cancel is found " + connectionsManager.requestCallbacks.size() + " requests' callbacks");
            } else {
//                FileLog.d("{rc} onRequestClear(" + currentAccount + ", " + requestToken + ", " + cancelled + "): request to cancel is not found " + connectionsManager.requestCallbacks.size() + " requests' callbacks");
            }
        } else if (callbacks != null) {
            connectionsManager.requestCallbacks.remove(requestToken);
//            FileLog.d("{rc} onRequestClear(" + currentAccount + ", " + requestToken + ", " + cancelled + "): " + connectionsManager.requestCallbacks.size() + " requests' callbacks");
        }
    }

    public static void onRequestComplete(int currentAccount, int requestToken, long response, int errorCode, String errorText, int networkType, long timestamp, long requestMsgId, int dcId) {
        ConnectionsManager connectionsManager = currentAccount >= 0 && currentAccount < Instance.length ? Instance[currentAccount] : null;
        if (connectionsManager == null) return;
        RequestCallbacks callbacks = connectionsManager.requestCallbacks.get(requestToken);
        connectionsManager.requestCallbacks.remove(requestToken);
        if (callbacks != null) {
            if (callbacks.onComplete != null) {
                callbacks.onComplete.run(response, errorCode, errorText, networkType, timestamp, requestMsgId, dcId);
            }
//            FileLog.d("{rc} onRequestComplete(" + currentAccount + ", " + requestToken + "): found request " + requestToken + ", " + connectionsManager.requestCallbacks.size() + " requests' callbacks");
        } else {
//            FileLog.d("{rc} onRequestComplete(" + currentAccount + ", " + requestToken + "): not found request " + requestToken + "! " + connectionsManager.requestCallbacks.size() + " requests' callbacks");
        }
    }

    public static void onRequestQuickAck(int currentAccount, int requestToken) {
        ConnectionsManager connectionsManager = currentAccount >= 0 && currentAccount < Instance.length ? Instance[currentAccount] : null;
        if (connectionsManager == null) return;
        RequestCallbacks callbacks = connectionsManager.requestCallbacks.get(requestToken);
        if (callbacks != null) {
            if (callbacks.onQuickAck != null) {
                callbacks.onQuickAck.run();
            }
//            FileLog.d("{rc} onRequestQuickAck(" + currentAccount + ", " + requestToken + "): found request " + requestToken + ", " + connectionsManager.requestCallbacks.size() + " requests' callbacks");
        } else {
//            FileLog.d("{rc} onRequestQuickAck(" + currentAccount + ", " + requestToken + "): not found request " + requestToken + "! " + connectionsManager.requestCallbacks.size() + " requests' callbacks");
        }
    }

    public static void onRequestWriteToSocket(int currentAccount, int requestToken) {
        ConnectionsManager connectionsManager = currentAccount >= 0 && currentAccount < Instance.length ? Instance[currentAccount] : null;
        if (connectionsManager == null) return;
        RequestCallbacks callbacks = connectionsManager.requestCallbacks.get(requestToken);
        if (callbacks != null) {
            if (callbacks.onWriteToSocket != null) {
                callbacks.onWriteToSocket.run();
            }
//            FileLog.d("{rc} onRequestWriteToSocket(" + currentAccount + ", " + requestToken + "): found request " + requestToken + ", " + connectionsManager.requestCallbacks.size() + " requests' callbacks");
        } else {
//            FileLog.d("{rc} onRequestWriteToSocket(" + currentAccount + ", " + requestToken + "): not found request " + requestToken + "! " + connectionsManager.requestCallbacks.size() + " requests' callbacks");
        }
    }

    public void cancelRequest(int token, boolean notifyServer) {
        cancelRequest(token, notifyServer, null);
    }

    public void cancelRequest(int token, boolean notifyServer, Runnable onCancelled) {
        final RequestOwner owner = captureRequestOwner(false);
        Utilities.stageQueue.postRunnable(() -> {
            if (owner == null || !owner.isCurrent()) return;
            if (onCancelled != null) {
                listenCancel(token, () -> {
                    Utilities.stageQueue.postRunnable(() -> { if (owner.isCurrent()) onCancelled.run(); });
                });
            }
            native_cancelRequest(currentAccount, token, notifyServer);
        });
    }

    public void cleanup(boolean resetKeys) {
        final RequestOwner owner = captureRequestOwner(false);
        synchronized (nativeOwner) {
            if (owner != null && owner.isCurrent()) native_cleanUp(currentAccount, resetKeys);
        }
    }

    public void cancelRequestsForGuid(int guid) {
        final RequestOwner owner = captureRequestOwner(false);
        Utilities.stageQueue.postRunnable(() -> {
            synchronized (nativeOwner) {
                if (owner != null && owner.isCurrent()) native_cancelRequestsForGuid(currentAccount, guid);
            }
        });
    }

    public void bindRequestToGuid(int requestToken, int guid) {
        if (guid == 0) {
            return;
        }
        native_bindRequestToGuid(currentAccount, requestToken, guid);
    }

    public void applyDatacenterAddress(int datacenterId, String ipAddress, int port) {
        native_applyDatacenterAddress(currentAccount, datacenterId, ipAddress, port);
    }

    public int getConnectionState() {
        if (connectionState == ConnectionStateConnected && isUpdating) {
            return ConnectionStateUpdating;
        }
        return connectionState;
    }

    public boolean isLocalAuthConfigQuarantined() {
        return localAuthConfigQuarantined;
    }

    /** Read-only policy probe: never construct a transport from a routing callback. */
    public static boolean isAgramAuthTransportReady(int account) {
        if (account < 0 || account >= Instance.length) return false;
        ConnectionsManager existing = Instance[account];
        return existing != null && existing.captureRequestOwner(false) != null;
    }

    /** Stable, non-sensitive diagnostic code suitable for recovery UI. */
    public String getLocalAuthConfigStatus() {
        return localAuthConfigStatus;
    }

    public void setUserId(long id) {
        RequestOwner owner = captureRequestOwner(false);
        synchronized (nativeOwner) {
            if (owner != null && owner.isCurrent()) native_setUserId(currentAccount, id);
        }
    }

    public void checkConnection() {
        byte selectedStrategy = getIpStrategy();
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("selected ip strategy " + selectedStrategy);
        }
        final RequestOwner owner = captureRequestOwner(false);
        synchronized (nativeOwner) {
            if (owner == null || !owner.isCurrent()) return;
            native_setIpStrategy(currentAccount, selectedStrategy);
            native_setNetworkAvailable(currentAccount, isAgramRouteConfigured() && ApplicationLoader.isNetworkOnline(), ApplicationLoader.getCurrentNetworkType(), ApplicationLoader.isConnectionSlow());
        }
    }

    private boolean isAgramRouteConfigured() {
        try {
            AgramContainerManager.ContainerRecord record = AgramContainerManager.getInstance().getContainer(currentAccount);
            return record != null && record.isStorageAccessible()
                    && (AgramContainerManager.NETWORK_DIRECT.equals(record.proxyMode)
                        || AgramContainerManager.NETWORK_PROXY.equals(record.proxyMode)
                            && !TextUtils.isEmpty(record.proxyAddress));
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    public void setPushConnectionEnabled(boolean value) {
        native_setPushConnectionEnabled(currentAccount, value);
    }

    public void init(int version, int layer, int apiId, String deviceModel, String systemVersion, String appVersion, String langCode, String systemLangCode, String configPath, String logPath, String regId, String cFingerprint, int timezoneOffset, long userId, boolean userPremium, boolean enablePushConnection) {
        AgramNetworkController.getInstance().prepare(currentAccount);
        String installer = "";
        try {
            Context context = ApplicationLoader.applicationContext;
            if (Build.VERSION.SDK_INT >= 30) {
                InstallSourceInfo installSourceInfo = context.getPackageManager().getInstallSourceInfo(context.getPackageName());
                if (installSourceInfo != null) {
                    installer = installSourceInfo.getInitiatingPackageName();
                    if (installer == null) {
                        installer = installSourceInfo.getInstallingPackageName();
                    }
                }
            } else {
                installer = context.getPackageManager().getInstallerPackageName(context.getPackageName());
            }
        } catch (Throwable ignore) {

        }
        if (installer == null) {
            installer = "";
        }
        String packageId = "";
        try {
            packageId = ApplicationLoader.applicationContext.getPackageName();
        } catch (Throwable ignore) {

        }
        if (packageId == null) {
            packageId = "";
        }

        String containerOwnerId = "";
        boolean containerRetirementPending = false;
        try {
            AgramContainerManager manager = AgramContainerManager.getInstance();
            AgramContainerManager.ContainerRecord record = manager.getContainer(currentAccount);
            // A persisted but unreadable UserConfig is not an inactive slot.
            // Never authorize native key retirement/adoption from a zero runtime id alone.
            if (record != null && record.isStorageAccessible()
                    && (userId != 0 || !getUserConfig().hasPersistedSession())) {
                containerOwnerId = record.id;
                containerRetirementPending = manager.hasPendingNativeRetirement(currentAccount, record.id)
                        && !getUserConfig().hasPersistedSession();
            }
        } catch (RuntimeException unavailable) {
            // Unknown encrypted metadata is not an empty/reusable native owner.
        }
        native_init(currentAccount, version, layer, apiId, deviceModel, systemVersion, appVersion, langCode, systemLangCode, configPath, logPath, regId, cFingerprint, installer, packageId, timezoneOffset, userId, userPremium, enablePushConnection, ApplicationLoader.isNetworkOnline(), ApplicationLoader.getCurrentNetworkType(), SharedConfig.measureDevicePerformanceClass(), containerOwnerId, containerRetirementPending);
        boolean ownerReady = !TextUtils.isEmpty(containerOwnerId)
                && native_isContainerOwnerReady(currentAccount, containerOwnerId);
        nativeOwner.initialize(containerOwnerId, ownerReady);
        if (!ownerReady) {
            localAuthConfigQuarantined = true;
            localAuthConfigStatus = "native_owner_unavailable";
            connectionState = ConnectionStateLocalAuthUnavailable;
        }
        if (containerRetirementPending) localAuthConfigStatus = "native_owner_pending";
        if (ownerReady) AgramNetworkController.getInstance().prepare(currentAccount);
        checkConnection();
    }

    public static void setLangCode(String langCode) {
        langCode = langCode.replace('_', '-').toLowerCase();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            native_setLangCode(a, langCode);
        }
    }

    public static void setRegId(String regId, @PushListenerController.PushType int type, String status) {
        // Legacy callbacks cannot broadcast a token to all account instances.
    }

    public static void setSystemLangCode(String langCode) {
        langCode = langCode.replace('_', '-').toLowerCase();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            native_setSystemLangCode(a, langCode);
        }
    }

    public void switchBackend(boolean restart) {
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        preferences.edit().remove("language_showed2").commit();
        native_switchBackend(currentAccount, restart);
    }

    public boolean isTestBackend() {
        return native_isTestBackend(currentAccount) != 0;
    }

    public void resumeNetworkMaybe() {
        if (localAuthConfigQuarantined) {
            native_pauseNetwork(currentAccount);
            return;
        }
        native_resumeNetwork(currentAccount, true);
    }

    public void updateDcSettings() {
        native_updateDcSettings(currentAccount);
    }

    public void setDefaultDatacenterId(int dcId) {
        native_moveDatacenter(currentAccount, dcId);
    }

    public long getPauseTime() {
        return lastPauseTime;
    }

    public long checkProxy(ProxySettings settings, RequestTimeDelegate requestTimeDelegate) {
        if (settings == null || !settings.isValid()) {
            return 0;
        }
        if (settings.getType() == ProxySettings.Type.WEB) {
            // Telegram 12.10.5 implements WEB proxies through one process-global
            // localhost transport. Agram routes every account independently, so
            // starting that transport here would silently mix container routes.
            if (requestTimeDelegate != null) {
                requestTimeDelegate.run(-1);
            }
            return 0;
        }

        return native_checkProxy(currentAccount, settings.getAddress(), settings.getPort(), settings.getUser(), settings.getPassword(), settings.getSecret(), requestTimeDelegate);
    }

    public static boolean isProxyTypeSupported(ProxySettings settings) {
        return settings == null || settings.getType() != ProxySettings.Type.WEB;
    }

    public static void notifyUnsupportedWebProxy() {
        AndroidUtilities.runOnUIThread(() -> Toast.makeText(
                ApplicationLoader.applicationContext,
                LocaleController.getString(R.string.AGramWebProxyUnsupported),
                Toast.LENGTH_LONG
        ).show());
    }

    public void setAppPaused(final boolean value, final boolean byScreenState) {
        if (!byScreenState) {
            appPaused = value;
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("app paused = " + value);
            }
            if (value) {
                appResumeCount--;
            } else {
                appResumeCount++;
            }
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("app resume count " + appResumeCount);
            }
            if (appResumeCount < 0) {
                appResumeCount = 0;
            }
        }
        if (appResumeCount == 0) {
            if (lastPauseTime == 0) {
                lastPauseTime = System.currentTimeMillis();
            }
            native_pauseNetwork(currentAccount);
        } else {
            if (appPaused) {
                return;
            }
            if (localAuthConfigQuarantined) {
                native_pauseNetwork(currentAccount);
                return;
            }
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("reset app pause time");
            }
            if (lastPauseTime != 0 && System.currentTimeMillis() - lastPauseTime > 5000) {
                getContactsController().checkContacts();
            }
            lastPauseTime = 0;
            native_resumeNetwork(currentAccount, false);
        }
    }

    public static void onUnparsedMessageReceived(long address, final int currentAccount, long messageId) {
        final RequestOwner owner = captureCallbackOwner(currentAccount);
        if (owner == null) return;
        try {
            NativeByteBuffer buff = NativeByteBuffer.wrap(address);
            buff.setDataSourceType(TLDataSourceType.NETWORK);
            buff.reused = true;
            int constructor = buff.readInt32(true);
            final TLObject message = TLClassStore.Instance().TLdeserialize(buff, constructor, true);
            FileLog.dumpUnparsedMessage(message, messageId, currentAccount);
            if (message instanceof TLRPC.Updates) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("java received " + message);
                }
                KeepAliveJob.finishJob();
                Utilities.stageQueue.postRunnable(() -> {
                    if (owner.isCurrent()) AccountInstance.getInstance(currentAccount).getMessagesController().processUpdates((TLRPC.Updates) message, false);
                });
            } else {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d(String.format("java received unknown constructor 0x%x", constructor));
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static void onUpdate(final int currentAccount) {
        final RequestOwner owner = captureCallbackOwner(currentAccount);
        if (owner == null) return;
        Utilities.stageQueue.postRunnable(() -> {
            if (owner.isCurrent()) AccountInstance.getInstance(currentAccount).getMessagesController().updateTimerProc();
        });
    }

    public static void onSessionCreated(final int currentAccount) {
        final RequestOwner owner = captureCallbackOwner(currentAccount);
        if (owner == null) return;
        Utilities.stageQueue.postRunnable(() -> {
            if (owner.isCurrent()) AccountInstance.getInstance(currentAccount).getMessagesController().getDifference();
        });
    }

    private static RequestOwner captureCallbackOwner(int account) {
        ConnectionsManager existing = account >= 0 && account < Instance.length ? Instance[account] : null;
        return existing == null ? null : existing.captureRequestOwner(false);
    }

    public static void onConnectionStateChanged(final int state, final int currentAccount) {
        final RequestOwner owner = captureCallbackOwner(currentAccount);
        if (owner == null) return;
        AndroidUtilities.runOnUIThread(() -> {
            if (!owner.isCurrent()) return;
            ConnectionsManager manager = Instance[currentAccount];
            // A queued native "connected" callback must not undo a local-auth
            // quarantine. Recovery is intentionally process-bound: after the
            // local files become available, a clean re-init starts unquarantined.
            manager.connectionState = manager.localAuthConfigQuarantined
                    ? ConnectionStateLocalAuthUnavailable : state;
            AccountInstance.getInstance(currentAccount).getNotificationCenter().postNotificationName(NotificationCenter.didUpdateConnectionState);
        });
    }

    public static void onLogout(final int currentAccount, @LogoutReason final int reason) {
        ConnectionsManager existing = currentAccount >= 0 && currentAccount < Instance.length ? Instance[currentAccount] : null;
        if (existing == null) return; // Synchronous init is surfaced by its readiness query.
        final RequestOwner owner = existing.new RequestOwner(existing.nativeOwner.owner(),
                UserConfig.getInstance(currentAccount).getSessionGeneration(), false);
        AndroidUtilities.runOnUIThread(() -> {
            if (!owner.isCurrent(reason != LogoutReasonLocalConfigMismatch)) return;
            if (reason == LogoutReasonLocalConfigMismatch) {
                ConnectionsManager manager = existing;
                manager.localAuthConfigQuarantined = true;
                manager.localAuthConfigStatus = LOCAL_AUTH_STATUS_UNAVAILABLE;
                manager.connectionState = ConnectionStateLocalAuthUnavailable;
                native_pauseNetwork(currentAccount);
                FileLog.e("Agram quarantined account " + currentAccount
                        + ": Telegram user data exists but the local MTProto config/auth key is unavailable; preserving session data");
                NotificationCenter.getInstance(currentAccount).postNotificationName(
                        NotificationCenter.sessionAuthConfigMismatch,
                        reason);
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.didUpdateConnectionState);
                return;
            }
            AccountInstance accountInstance = AccountInstance.getInstance(currentAccount);
            if (accountInstance.getUserConfig().getClientUserId() != 0) {
                if (reason == LogoutReasonServerAuthRejected) {
                    // A definitive server-side revocation follows the same teardown
                    // path as a manual logout. Agram must not retain an unusable
                    // account card or reuse its container identity.
                    accountInstance.getMessagesController().performLogout(0, true);
                } else {
                    FileLog.e("Ignoring unknown native logout reason " + reason + " for account " + currentAccount);
                }
            }
        });
    }

    public static int getInitFlags() {
        int flags = 0;
        EmuDetector detector = EmuDetector.with(ApplicationLoader.applicationContext);
        if (detector.detect()) {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("detected emu");
            }
            flags |= 1024;
        }
        return flags;
    }

    public static void onBytesSent(int amount, int networkType, final int currentAccount) {
        try {
            AccountInstance.getInstance(currentAccount).getStatsController().incrementSentBytesCount(networkType, StatsController.TYPE_TOTAL, amount);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static void onRequestNewServerIpAndPort(final int second, final int currentAccount) {
        // DNS-over-HTTPS discovery has no proxy support. Never use it as a
        // direct-network fallback for an account with a selected proxy.
        final DnsOwner owner = DnsOwner.capture(currentAccount);
        if (owner == null || !owner.permitsDirectHttp()) return;
        Utilities.globalQueue.postRunnable(() -> {
            boolean networkOnline = ApplicationLoader.isNetworkOnline();
            Utilities.stageQueue.postRunnable(() -> {
                if (!owner.permitsDirectHttp()) return;
                if (!owner.key.equals(dnsConfigOwners[currentAccount])) {
                    dnsConfigOwners[currentAccount] = owner.key;
                    currentDnsTasks[currentAccount] = null;
                    lastDnsRequestTimes[currentAccount] = 0;
                }
                if (currentDnsTasks[currentAccount] != null
                        || second == 0 && Math.abs(lastDnsRequestTimes[currentAccount] - System.currentTimeMillis()) < 10000
                        || !networkOnline) return;
                lastDnsRequestTimes[currentAccount] = System.currentTimeMillis();
                if (second == 2) {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("start mozilla txt task");
                    }
                    MozillaDnsLoadTask task = new MozillaDnsLoadTask(owner);
                    startDnsConfigTask(owner, task);
                } else {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("start google txt task");
                    }
                    GoogleDnsLoadTask task = new GoogleDnsLoadTask(owner);
                    startDnsConfigTask(owner, task);
                }
            });
        });
    }

    public static void onProxyError(final int account) {
        final RequestOwner owner = captureCallbackOwner(account);
        if (owner == null) return;
        AndroidUtilities.runOnUIThread(() -> {
            if (!owner.isCurrent()) return;
            AgramNetworkController.getInstance().onProxyError(account);
            if (account == UserConfig.selectedAccount) {
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.needShowAlert, 3);
            }
        });
    }

    /** Called only on stageQueue; rejected jobs must not monopolize their account slot. */
    private static void startDnsConfigTask(DnsOwner owner, AsyncTask task) {
        if (!owner.permitsDirectHttp()) return;
        currentDnsTasks[owner.account] = task;
        try {
            task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, null, null, null);
        } catch (Throwable e) {
            if (currentDnsTasks[owner.account] == task) currentDnsTasks[owner.account] = null;
            FileLog.e(e, false);
        }
    }

    public static void getHostByName(int currentAccount, String hostName, long address, long token) {
        final DnsOwner owner = DnsOwner.capture(currentAccount);
        AndroidUtilities.runOnUIThread(() -> {
            if (owner == null || !owner.permitsProxyBootstrap(hostName)) {
                native_onHostNameResolved(currentAccount, hostName, address, token, "");
                return;
            }
            String key = owner.key + ":" + hostName.toLowerCase(Locale.US);
            ResolvedDomain resolvedDomain = dnsCache.get(key);
            if (resolvedDomain != null && SystemClock.elapsedRealtime() - resolvedDomain.ttl < 5 * 60 * 1000) {
                native_onHostNameResolved(currentAccount, hostName, address, token, resolvedDomain.getAddress());
            } else {
                ResolveHostByNameTask task = resolvingHostnameTasks.get(key);
                if (task == null) {
                    task = new ResolveHostByNameTask(owner, hostName, key);
                    try {
                        task.executeOnExecutor(DNS_THREAD_POOL_EXECUTOR, null, null, null);
                    } catch (Throwable e) {
                        FileLog.e(e);
                        native_onHostNameResolved(currentAccount, hostName, address, token, "");
                        return;
                    }
                    resolvingHostnameTasks.put(key, task);
                }
                task.addAddress(address, token);
            }
        });
    }

    public static void onBytesReceived(int amount, int networkType, final int currentAccount) {
        try {
            StatsController.getInstance(currentAccount).incrementReceivedBytesCount(networkType, StatsController.TYPE_TOTAL, amount);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static void onUpdateConfig(long address, final int currentAccount) {
        final RequestOwner owner = captureCallbackOwner(currentAccount);
        if (owner == null) return;
        try {
            NativeByteBuffer buff = NativeByteBuffer.wrap(address);
            buff.reused = true;
            final TLRPC.TL_config message = TLRPC.TL_config.TLdeserialize(buff, buff.readInt32(true), true);
            if (message != null) {
                Utilities.stageQueue.postRunnable(() -> {
                    if (owner.isCurrent()) AccountInstance.getInstance(currentAccount).getMessagesController().updateConfig(message);
                });
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static void onInternalPushReceived(final int currentAccount) {
        if (captureCallbackOwner(currentAccount) != null) KeepAliveJob.startJob();
    }

    public static void setProxySettings(boolean enabled, ProxySettings settings) {
        if (!isProxyTypeSupported(settings)) {
            // Fail closed without touching the active container route or its
            // durable profile. In particular, never persist an ephemeral
            // 127.0.0.1 port produced by Telegram's global WEB transport.
            MessagesController.getGlobalMainSettings().edit()
                    .putBoolean("proxy_enabled", false)
                    .commit();
            notifyUnsupportedWebProxy();
            return;
        }

        String address = "";
        int port = 0;
        String username = "";
        String password = "";
        String secret = "";

        if (enabled && settings != null && settings.isValid()) {
            address = settings.getAddress();
            port = settings.getPort();
            username = settings.getUser();
            password = settings.getPassword();
            secret = settings.getSecret();
        }

        int account = UserConfig.selectedAccount;
        AgramContainerManager.getInstance().saveProxySettings(
                account, enabled, address, port, username, password, secret);
        AgramNetworkController.getInstance().apply(account);
        AccountInstance accountInstance = AccountInstance.getInstance(account);
        if (accountInstance.getUserConfig().isClientActivated()) {
            accountInstance.getMessagesController().checkPromoInfo(true);
        }
    }

    public static native void native_switchBackend(int currentAccount, boolean restart);
    public static native int native_isTestBackend(int currentAccount);
    public static native void native_pauseNetwork(int currentAccount);
    public static native void native_setIpStrategy(int currentAccount, byte value);
    public static native void native_updateDcSettings(int currentAccount);
    public static native void native_moveDatacenter(int currentAccount, int datacenterId);
    public static native void native_setNetworkAvailable(int currentAccount, boolean value, int networkType, boolean slow);
    public static void native_resumeNetwork(int currentAccount, boolean partial) {
        ConnectionsManager existing = currentAccount >= 0 && currentAccount < Instance.length ? Instance[currentAccount] : null;
        if (existing == null) return;
        RequestOwner owner = existing.captureRequestOwner(false);
        synchronized (existing.nativeOwner) {
            if (owner != null && owner.isCurrent() && existing.isAgramRouteConfigured()) {
                native_resumeNetworkOwned(currentAccount, partial);
            }
        }
    }
    private static native void native_resumeNetworkOwned(int currentAccount, boolean partial);
    public static native long native_getCurrentTimeMillis(int currentAccount);
    public static native int native_getCurrentTime(int currentAccount);
    public static native int native_getCurrentPingTime(int currentAccount);
    public static native int native_getCurrentDatacenterId(int currentAccount);
    public static native long native_getCurrentAuthKeyId(int currentAccount);
    public static native int native_getTimeDifference(int currentAccount);
    private static native void native_sendRequest(int currentAccount, long object, int flags, int datacenterId, int connectionType, boolean immediate, int requestToken);
    public static native void native_cancelRequest(int currentAccount, int token, boolean notifyServer);
    public static native void native_cleanUp(int currentAccount, boolean resetKeys);
    public static native void native_cancelRequestsForGuid(int currentAccount, int guid);
    public static native void native_bindRequestToGuid(int currentAccount, int requestToken, int guid);
    public static native void native_applyDatacenterAddress(int currentAccount, int datacenterId, String ipAddress, int port);
    public static native int native_getConnectionState(int currentAccount);
    public static native void native_setUserId(int currentAccount, long id);
    public static native void native_init(int currentAccount, int version, int layer, int apiId, String deviceModel, String systemVersion, String appVersion, String langCode, String systemLangCode, String configPath, String logPath, String regId, String cFingerprint, String installer, String packageId, int timezoneOffset, long userId, boolean userPremium, boolean enablePushConnection, boolean hasNetwork, int networkType, int performanceClass, String containerOwnerId, boolean containerRetirementPending);
    public static native boolean native_isContainerOwnerReady(int currentAccount, String expectedOwnerId);
    public static native String native_getContainerOwner(int currentAccount);
    public static native void native_transitionContainerOwner(int currentAccount, String expectedOwnerId, String newOwnerId, long transitionId);
    private static native void native_setSessionProfile(int currentAccount, String deviceModel, String systemVersion, String appVersion, String langCode, String systemLangCode, int timezoneOffset);
    public static native void native_setProxySettings(int currentAccount, String address, int port, String username, String password, String secret);
    public static native void native_setLangCode(int currentAccount, String langCode);
    public static native void native_setRegId(int currentAccount, String regId);
    public static native void native_setSystemLangCode(int currentAccount, String langCode);
    public static native void native_setJava(boolean useJavaByteBuffers);
    public static native void native_setPushConnectionEnabled(int currentAccount, boolean value);
    public static native void native_applyDnsConfig(int currentAccount, long address, String phone, int date);
    public static native long native_checkProxy(int currentAccount, String address, int port, String username, String password, String secret, RequestTimeDelegate requestTimeDelegate);
    public static native void native_onHostNameResolved(int currentAccount, String host, long address, long token, String ip);
    public static native void native_discardConnection(int currentAccount, int datacenterId, int connectionType);
    public static native void native_failNotRunningRequest(int currentAccount, int token);
    public static native void native_receivedIntegrityCheckClassic(int currentAccount, int requestToken, String nonce, String token);
    public static native void native_receivedCaptchaResult(int currentAccount, int[] requestTokens, String token);
    public static native boolean native_isGoodPrime(byte[] prime, int g);


    public static boolean testNativeTlScheme(NativeByteBuffer buffer, INativeTlTest test) {
        return test.test(buffer.address);
    }

    public static native boolean native_test_AuthAuthorization(long object);
    public interface INativeTlTest {
        boolean test(long address);
    }


    public static int generateClassGuid() {
        return lastClassGuid++;
    }

    public void setIsUpdating(final boolean value) {
        AndroidUtilities.runOnUIThread(() -> {
            if (isUpdating == value) {
                return;
            }
            isUpdating = value;
            if (connectionState == ConnectionStateConnected) {
                AccountInstance.getInstance(currentAccount).getNotificationCenter().postNotificationName(NotificationCenter.didUpdateConnectionState);
            }
        });
    }

    @SuppressLint("NewApi")
    protected byte getIpStrategy() {
        if (Build.VERSION.SDK_INT < 19) {
            return USE_IPV4_ONLY;
        }
        if (BuildVars.LOGS_ENABLED) {
            try {
                NetworkInterface networkInterface;
                Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
                while (networkInterfaces.hasMoreElements()) {
                    networkInterface = networkInterfaces.nextElement();
                    if (!networkInterface.isUp() || networkInterface.isLoopback() || networkInterface.getInterfaceAddresses().isEmpty()) {
                        continue;
                    }
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("valid interface: " + networkInterface);
                    }
                    List<InterfaceAddress> interfaceAddresses = networkInterface.getInterfaceAddresses();
                    for (int a = 0; a < interfaceAddresses.size(); a++) {
                        InterfaceAddress address = interfaceAddresses.get(a);
                        InetAddress inetAddress = address.getAddress();
                        if (BuildVars.LOGS_ENABLED) {
                            FileLog.d("address: " + inetAddress.getHostAddress());
                        }
                        if (inetAddress.isLinkLocalAddress() || inetAddress.isLoopbackAddress() || inetAddress.isMulticastAddress()) {
                            continue;
                        }
                        if (BuildVars.LOGS_ENABLED) {
                            FileLog.d("address is good");
                        }
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        try {
            NetworkInterface networkInterface;
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            boolean hasIpv4 = false;
            boolean hasIpv6 = false;
            boolean hasStrangeIpv4 = false;
            while (networkInterfaces.hasMoreElements()) {
                networkInterface = networkInterfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }
                List<InterfaceAddress> interfaceAddresses = networkInterface.getInterfaceAddresses();
                for (int a = 0; a < interfaceAddresses.size(); a++) {
                    InterfaceAddress address = interfaceAddresses.get(a);
                    InetAddress inetAddress = address.getAddress();
                    if (inetAddress.isLinkLocalAddress() || inetAddress.isLoopbackAddress() || inetAddress.isMulticastAddress()) {
                        continue;
                    }
                    if (inetAddress instanceof Inet6Address) {
                        hasIpv6 = true;
                    } else if (inetAddress instanceof Inet4Address) {
                        String addrr = inetAddress.getHostAddress();
                        if (!addrr.startsWith("192.0.0.")) {
                            hasIpv4 = true;
                        } else {
                            hasStrangeIpv4 = true;
                        }
                    }
                }
            }
            if (hasIpv6) {
                if (forceTryIpV6) {
                    return USE_IPV6_ONLY;
                }
                if (hasStrangeIpv4) {
                    return USE_IPV4_IPV6_RANDOM;
                }
                if (!hasIpv4) {
                    return USE_IPV6_ONLY;
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }

        return USE_IPV4_ONLY;
    }

    private static class ResolveHostByNameTask extends AsyncTask<Void, Void, ResolvedDomain> {
        private final ArrayList<long[]> callbacks = new ArrayList<>();
        private final DnsOwner owner;
        private final String currentHostName;
        private final String cacheKey;

        ResolveHostByNameTask(DnsOwner owner, String hostName, String cacheKey) {
            this.owner = owner;
            currentHostName = hostName;
            this.cacheKey = cacheKey;
        }

        void addAddress(long address, long token) {
            for (long[] callback : callbacks) {
                if (callback[0] == address && callback[1] == token) return;
            }
            callbacks.add(new long[]{address, token});
        }

        protected ResolvedDomain doInBackground(Void... voids) {
            if (!owner.permitsProxyBootstrap(currentHostName) || isCancelled()) return null;
            try {
                // Bootstrap boundary: the selected proxy hostname is resolved by Android's
                // system resolver BEFORE that proxy connection exists. Supplying a numeric
                // proxy address avoids this DNS request. Never send it to a third-party DoH
                // service or use this path to resolve unrelated/accountless destinations.
                InetAddress[] resolved = InetAddress.getAllByName(currentHostName);
                if (!owner.permitsProxyBootstrap(currentHostName) || isCancelled()) return null;
                ArrayList<String> addresses = new ArrayList<>();
                for (InetAddress address : resolved) {
                    // The native proxy bootstrap callback currently accepts IPv4 only.
                    if (address instanceof Inet4Address) addresses.add(address.getHostAddress());
                }
                return addresses.isEmpty() ? null : new ResolvedDomain(addresses, SystemClock.elapsedRealtime());
            } catch (Exception e) {
                FileLog.e(e, false);
                return null;
            }
        }

        @Override
        protected void onPostExecute(ResolvedDomain result) {
            boolean current = owner.permitsProxyBootstrap(currentHostName);
            if (current && result != null) dnsCache.put(cacheKey, result);
            for (long[] callback : callbacks) {
                native_onHostNameResolved(owner.account, currentHostName, callback[0], callback[1],
                        current && result != null ? result.getAddress() : "");
            }
            if (resolvingHostnameTasks.get(cacheKey) == this) resolvingHostnameTasks.remove(cacheKey);
        }
    }

    private static class GoogleDnsLoadTask extends AsyncTask<Void, Void, NativeByteBuffer> {

        private final DnsOwner owner;
        private final int currentAccount;
        private final String domain;
        private int responseDate;

        public GoogleDnsLoadTask(DnsOwner owner) {
            super();
            this.owner = owner;
            currentAccount = owner.account;
            domain = native_isTestBackend(currentAccount) != 0 ? "tapv3.stel.com"
                    : AccountInstance.getInstance(currentAccount).getMessagesController().dcDomainName;
        }

        protected NativeByteBuffer doInBackground(Void... voids) {
            ByteArrayOutputStream outbuf = null;
            InputStream httpConnectionStream = null;
            try {
                if (!owner.permitsDirectHttp() || isCancelled()) return null;
                int len = Utilities.random.nextInt(116) + 13;
                final String characters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

                StringBuilder padding = new StringBuilder(len);
                for (int a = 0; a < len; a++) {
                    padding.append(characters.charAt(Utilities.random.nextInt(characters.length())));
                }
                URL downloadUrl = new URL("https://dns.google.com/resolve?name=" + domain + "&type=ANY&random_padding=" + padding);
                URLConnection httpConnection = AgramNetworkController.openDirectHttpConnection(
                        currentAccount, downloadUrl, owner.containerId, owner.generation);
                httpConnection.addRequestProperty("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 10_0 like Mac OS X) AppleWebKit/602.1.38 (KHTML, like Gecko) Version/10.0 Mobile/14A5297c Safari/602.1");
                httpConnection.setConnectTimeout(5000);
                httpConnection.setReadTimeout(5000);
                httpConnection.connect();
                httpConnectionStream = httpConnection.getInputStream();
                responseDate = (int) (httpConnection.getDate() / 1000);

                outbuf = new ByteArrayOutputStream();

                byte[] data = new byte[1024 * 32];
                while (true) {
                    if (isCancelled()) {
                        break;
                    }
                    int read = httpConnectionStream.read(data);
                    if (read > 0) {
                        outbuf.write(data, 0, read);
                    } else if (read == -1) {
                        break;
                    } else {
                        break;
                    }
                }

                JSONObject jsonObject = new JSONObject(new String(outbuf.toByteArray()));
                JSONArray array = jsonObject.getJSONArray("Answer");
                len = array.length();
                ArrayList<String> arrayList = new ArrayList<>(len);
                for (int a = 0; a < len; a++) {
                    JSONObject object = array.getJSONObject(a);
                    int type = object.getInt("type");
                    if (type != 16) {
                        continue;
                    }
                    arrayList.add(object.getString("data"));
                }
                Collections.sort(arrayList, (o1, o2) -> {
                    int l1 = o1.length();
                    int l2 = o2.length();
                    if (l1 > l2) {
                        return -1;
                    } else if (l1 < l2) {
                        return 1;
                    }
                    return 0;
                });
                StringBuilder builder = new StringBuilder();
                for (int a = 0; a < arrayList.size(); a++) {
                    builder.append(arrayList.get(a).replace("\"", ""));
                }
                byte[] bytes = Base64.decode(builder.toString(), Base64.DEFAULT);
                NativeByteBuffer buffer = new NativeByteBuffer(bytes.length);
                buffer.writeBytes(bytes);
                return buffer;
            } catch (Throwable e) {
                FileLog.e(e, !(e instanceof SocketTimeoutException || e instanceof SSLException));
            } finally {
                try {
                    if (httpConnectionStream != null) {
                        httpConnectionStream.close();
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                }
                try {
                    if (outbuf != null) {
                        outbuf.close();
                    }
                } catch (Exception ignore) {

                }
            }
            return null;
        }

        @Override
        protected void onPostExecute(final NativeByteBuffer result) {
            Utilities.stageQueue.postRunnable(() -> {
                if (currentDnsTasks[currentAccount] != this || !owner.permitsDirectHttp()) {
                    if (currentDnsTasks[currentAccount] == this) currentDnsTasks[currentAccount] = null;
                    if (result != null) result.reuse();
                    return;
                }
                currentDnsTasks[currentAccount] = null;
                if (result != null) {
                    native_applyDnsConfig(currentAccount, result.address, owner.phone, responseDate);
                } else {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("failed to get google result");
                        FileLog.d("start mozilla task");
                    }
                    if (!owner.permitsDirectHttp()) return;
                    MozillaDnsLoadTask task = new MozillaDnsLoadTask(owner);
                    startDnsConfigTask(owner, task);
                }
            });
        }
    }

    private static class MozillaDnsLoadTask extends AsyncTask<Void, Void, NativeByteBuffer> {

        private final DnsOwner owner;
        private final int currentAccount;
        private final String domain;
        private int responseDate;

        public MozillaDnsLoadTask(DnsOwner owner) {
            super();
            this.owner = owner;
            currentAccount = owner.account;
            domain = native_isTestBackend(currentAccount) != 0 ? "tapv3.stel.com"
                    : AccountInstance.getInstance(currentAccount).getMessagesController().dcDomainName;
        }

        protected NativeByteBuffer doInBackground(Void... voids) {
            ByteArrayOutputStream outbuf = null;
            InputStream httpConnectionStream = null;
            try {
                if (!owner.permitsDirectHttp() || isCancelled()) return null;
                int len = Utilities.random.nextInt(116) + 13;
                final String characters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

                StringBuilder padding = new StringBuilder(len);
                for (int a = 0; a < len; a++) {
                    padding.append(characters.charAt(Utilities.random.nextInt(characters.length())));
                }
                URL downloadUrl = new URL("https://mozilla.cloudflare-dns.com/dns-query?name=" + domain + "&type=TXT&random_padding=" + padding);
                URLConnection httpConnection = AgramNetworkController.openDirectHttpConnection(
                        currentAccount, downloadUrl, owner.containerId, owner.generation);
                httpConnection.addRequestProperty("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 10_0 like Mac OS X) AppleWebKit/602.1.38 (KHTML, like Gecko) Version/10.0 Mobile/14A5297c Safari/602.1");
                httpConnection.addRequestProperty("accept", "application/dns-json");
                httpConnection.setConnectTimeout(5000);
                httpConnection.setReadTimeout(5000);
                httpConnection.connect();
                httpConnectionStream = httpConnection.getInputStream();
                responseDate = (int) (httpConnection.getDate() / 1000);

                outbuf = new ByteArrayOutputStream();

                byte[] data = new byte[1024 * 32];
                while (true) {
                    if (isCancelled()) {
                        break;
                    }
                    int read = httpConnectionStream.read(data);
                    if (read > 0) {
                        outbuf.write(data, 0, read);
                    } else if (read == -1) {
                        break;
                    } else {
                        break;
                    }
                }

                JSONObject jsonObject = new JSONObject(new String(outbuf.toByteArray()));
                JSONArray array = jsonObject.getJSONArray("Answer");
                len = array.length();
                ArrayList<String> arrayList = new ArrayList<>(len);
                for (int a = 0; a < len; a++) {
                    JSONObject object = array.getJSONObject(a);
                    int type = object.getInt("type");
                    if (type != 16) {
                        continue;
                    }
                    arrayList.add(object.getString("data"));
                }
                Collections.sort(arrayList, (o1, o2) -> {
                    int l1 = o1.length();
                    int l2 = o2.length();
                    if (l1 > l2) {
                        return -1;
                    } else if (l1 < l2) {
                        return 1;
                    }
                    return 0;
                });
                StringBuilder builder = new StringBuilder();
                for (int a = 0; a < arrayList.size(); a++) {
                    builder.append(arrayList.get(a).replace("\"", ""));
                }
                byte[] bytes = Base64.decode(builder.toString(), Base64.DEFAULT);
                NativeByteBuffer buffer = new NativeByteBuffer(bytes.length);
                buffer.writeBytes(bytes);
                return buffer;
            } catch (Throwable e) {
                FileLog.e(e, false);
            } finally {
                try {
                    if (httpConnectionStream != null) {
                        httpConnectionStream.close();
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                }
                try {
                    if (outbuf != null) {
                        outbuf.close();
                    }
                } catch (Exception ignore) {

                }
            }
            return null;
        }

        @Override
        protected void onPostExecute(final NativeByteBuffer result) {
            Utilities.stageQueue.postRunnable(() -> {
                if (currentDnsTasks[currentAccount] != this || !owner.permitsDirectHttp()) {
                    if (currentDnsTasks[currentAccount] == this) currentDnsTasks[currentAccount] = null;
                    if (result != null) result.reuse();
                    return;
                }
                currentDnsTasks[currentAccount] = null;
                if (result != null) {
                    native_applyDnsConfig(currentAccount, result.address, owner.phone, responseDate);
                } else {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("failed to get mozilla txt result");
                    }
                }
            });
        }
    }

    public static long lastPremiumFloodWaitShown = 0;
    @Keep
    public static void onPremiumFloodWait(final int currentAccount, final int requestToken, boolean isUpload) {
        final RequestOwner owner = captureCallbackOwner(currentAccount);
        if (owner == null) return;
        AndroidUtilities.runOnUIThread(() -> {
            if (!owner.isCurrent() || UserConfig.selectedAccount != currentAccount) {
                return;
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (!owner.isCurrent()) return;
                boolean updated = false;
                if (isUpload) {
                    FileUploadOperation operation = FileLoader.getInstance(currentAccount).findUploadOperationByRequestToken(requestToken);
                    if (operation != null) {
                        updated = !operation.caughtPremiumFloodWait;
                        operation.caughtPremiumFloodWait = true;
                    }
                } else {
                    FileLoadOperation operation = FileLoader.getInstance(currentAccount).findLoadOperationByRequestToken(requestToken);
                    if (operation != null) {
                        updated = !operation.caughtPremiumFloodWait;
                        operation.caughtPremiumFloodWait = true;
                    }
                }
                final boolean finalUpdated = updated;
                if (finalUpdated) {
                    NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.premiumFloodWaitReceived);
                }
            });
        });
    }

    @Keep
    public static void onIntegrityCheckClassic(final int currentAccount, final int requestToken, final String project, final String nonce) {
        final RequestOwner owner = captureCallbackOwner(currentAccount);
        if (owner == null) return;
        AndroidUtilities.runOnUIThread(() -> {
            if (!owner.isCurrent()) return;
            long start = System.currentTimeMillis();
            FileLog.d("account"+currentAccount+": server requests integrity classic check with project = "+project+" nonce = " + nonce);
            IntegrityManager integrityManager = IntegrityManagerFactory.create(ApplicationLoader.applicationContext);
            final long project_id;
            try {
                project_id = Long.parseLong(project);
            } catch (Exception e) {
                FileLog.d("account"+currentAccount+": integrity check failes to parse project id");
                native_receivedIntegrityCheckClassic(currentAccount, requestToken, nonce, "PLAYINTEGRITY_FAILED_EXCEPTION_NOPROJECT");
                return;
            }
            Task<IntegrityTokenResponse> integrityTokenResponse = integrityManager.requestIntegrityToken(IntegrityTokenRequest.builder().setNonce(nonce).setCloudProjectNumber(project_id).build());
            integrityTokenResponse
                .addOnSuccessListener(r -> {
                    if (!owner.isCurrent()) return;
                    final String token = r.token();

                    if (token == null) {
                        FileLog.e("account"+currentAccount+": integrity check gave null token in " + (System.currentTimeMillis() - start) + "ms");
                        native_receivedIntegrityCheckClassic(currentAccount, requestToken, nonce, "PLAYINTEGRITY_FAILED_EXCEPTION_NULL");
                        return;
                    }

                    FileLog.d("account"+currentAccount+": integrity check successfully gave token: " + token + " in " + (System.currentTimeMillis() - start) + "ms");
                    try {
                        native_receivedIntegrityCheckClassic(currentAccount, requestToken, nonce, token);
                    } catch (Exception e) {
                        FileLog.e("receivedIntegrityCheckClassic failed", e);
                    }
                })
                .addOnFailureListener(e -> {
                    if (!owner.isCurrent()) return;
                    FileLog.e("account"+currentAccount+": integrity check failed to give a token in " + (System.currentTimeMillis() - start) + "ms", e);
                    native_receivedIntegrityCheckClassic(currentAccount, requestToken, nonce, "PLAYINTEGRITY_FAILED_EXCEPTION_" + LoginActivity.errorString(e));
                });
        });
    }

    @Keep
    public static void onCaptchaCheck(final int currentAccount, final int requestToken, final String action, final String key_id) {
        if (captureCallbackOwner(currentAccount) != null) CaptchaController.request(currentAccount, requestToken, action, key_id);
    }

    public static native byte[] nativeTestGenerateClientHello(String domain);


}
