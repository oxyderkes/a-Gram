/*
 * This file is part of Agram and is licensed under GNU GPL v2 or later.
 */
package org.telegram.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AgramContainerManager;
import org.telegram.messenger.AgramBridgeRequestClient;
import org.telegram.messenger.AgramNetworkController;
import org.telegram.messenger.AgramRdsysBridgeClient;
import org.telegram.messenger.AgramSessionRouteController;
import org.telegram.messenger.AgramTorManager;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

/** Network control center for the selected Agram container. */
public class AgramTorSettingsActivity extends BaseFragment
        implements AgramTorManager.Listener, AgramSessionRouteController.Listener {
    private final int account;
    private final AgramBridgeRequestClient bridgeRequestClient;
    private AgramContainerManager.ContainerRecord record;
    private TextView stateView;
    private TextView routeView;
    private TextView primaryAction;
    private TextView circuitAction;
    private ProgressBar bootstrapProgress;
    private Switch bridgesEnabled;
    private EditText bridgeLines;
    private TextView bridgeAssistStatus;

    private final Runnable statusTicker = new Runnable() {
        @Override
        public void run() {
            if (fragmentView == null) {
                return;
            }
            updateState();
            AndroidUtilities.runOnUIThread(this, 2_000);
        }
    };

    public AgramTorSettingsActivity() {
        this(UserConfig.selectedAccount, new AgramRdsysBridgeClient());
    }

    public AgramTorSettingsActivity(int account) {
        this(account, new AgramRdsysBridgeClient());
    }

    /** Allows the bridge request backend to be supplied without coupling it to Tor lifecycle code. */
    public AgramTorSettingsActivity(int account, AgramBridgeRequestClient bridgeRequestClient) {
        this.account = account;
        this.bridgeRequestClient = bridgeRequestClient;
        currentAccount = account;
    }

    @Override
    public boolean onFragmentCreate() {
        record = AgramContainerManager.getInstance().ensureContainer(account);
        AgramTorManager.getInstance().addListener(this);
        AgramSessionRouteController.getInstance().addListener(this);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        AndroidUtilities.cancelRunOnUIThread(statusTicker);
        AgramTorManager.getInstance().removeListener(this);
        AgramSessionRouteController.getInstance().removeListener(this);
        super.onFragmentDestroy();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Tor и маршрут");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = root;
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        root.addView(scroll, LayoutHelper.createFrame(-1, -1));
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(AndroidUtilities.dp(18), AndroidUtilities.dp(18),
                AndroidUtilities.dp(18), AndroidUtilities.dp(32));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        content.addView(title(context, "Маршрут аккаунта " + (account + 1)));
        content.addView(body(context,
                "Tor встроен в Agram. Пока он не готов, MTProto и push этого контейнера приостановлены; локальные чаты остаются доступны."),
                LayoutHelper.createLinear(-1, -2, 0, 6, 0, 14));

        LinearLayout statusCard = card(context);
        content.addView(statusCard, LayoutHelper.createLinear(-1, -2, 0, 0, 0, 12));
        statusCard.addView(label(context, "СОСТОЯНИЕ"));
        stateView = mono(context);
        statusCard.addView(stateView, LayoutHelper.createLinear(-1, -2, 0, 6, 0, 8));
        bootstrapProgress = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        bootstrapProgress.setMax(100);
        bootstrapProgress.setIndeterminate(false);
        statusCard.addView(bootstrapProgress, LayoutHelper.createLinear(-1, 6, 0, 0, 0, 8));
        routeView = mono(context);
        statusCard.addView(routeView, LayoutHelper.createLinear(-1, -2, 0, 0, 0, 8));

        primaryAction = action(context, "ЗАПУСТИТЬ TOR");
        primaryAction.setOnClickListener(v -> enableOrStartTor());
        statusCard.addView(primaryAction, LayoutHelper.createLinear(-1, 46, 0, 4, 0, 0));
        TextView refreshIp = action(context, "ОБНОВИТЬ IP И ГЕО TELEGRAM");
        refreshIp.setOnClickListener(v -> AgramSessionRouteController.getInstance().refresh(account, true));
        statusCard.addView(refreshIp, LayoutHelper.createLinear(-1, 44, 0, 8, 0, 0));
        TextView copyDiagnostics = action(context, "КОПИРОВАТЬ ДИАГНОСТИКУ TOR");
        copyDiagnostics.setOnClickListener(v -> {
            AndroidUtilities.addToClipboard(AgramTorManager.getInstance().getDiagnosticSummary());
            Toast.makeText(getParentActivity(), "Диагностика Tor скопирована", Toast.LENGTH_SHORT).show();
        });
        statusCard.addView(copyDiagnostics, LayoutHelper.createLinear(-1, 44, 0, 8, 0, 0));

        LinearLayout identityCard = card(context);
        content.addView(identityCard, LayoutHelper.createLinear(-1, -2, 0, 0, 0, 12));
        identityCard.addView(label(context, "ЦЕПОЧКА ЭТОГО КОНТЕЙНЕРА"));
        identityCard.addView(body(context,
                "Новая цепочка меняет SOCKS isolation ID только текущего аккаунта. Остальные контейнеры продолжают работать через свои circuit-группы."));
        circuitAction = action(context, "НОВАЯ ЦЕПОЧКА / ЛИЧНОСТЬ");
        circuitAction.setOnClickListener(v -> {
            AgramNetworkController.getInstance().rotateTorCircuit(account);
            Toast.makeText(getParentActivity(), "Для этого контейнера назначена новая Tor-цепочка", Toast.LENGTH_SHORT).show();
            updateState();
        });
        identityCard.addView(circuitAction, LayoutHelper.createLinear(-1, 46, 0, 10, 0, 0));
        TextView restart = action(context, "ПЕРЕЗАПУСТИТЬ ОБЩИЙ TOR");
        restart.setOnClickListener(v -> confirmGlobalRestart());
        identityCard.addView(restart, LayoutHelper.createLinear(-1, 46, 0, 8, 0, 0));

        LinearLayout bridgeCard = card(context);
        content.addView(bridgeCard, LayoutHelper.createLinear(-1, -2));
        bridgeCard.addView(label(context, "МОСТЫ TOR"));
        AgramTorManager.BridgeConfig config = AgramTorManager.getInstance().getBridgeConfig();
        bridgesEnabled = new Switch(context);
        bridgesEnabled.setText("Использовать мосты");
        bridgesEnabled.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        bridgesEnabled.setTextSize(15);
        bridgesEnabled.setChecked(config.enabled);
        bridgeCard.addView(bridgesEnabled, LayoutHelper.createLinear(-1, 48));
        bridgeLines = new EditText(context);
        bridgeLines.setHint("obfs4 …\nили webtunnel …\nили IP:port fingerprint");
        bridgeLines.setText(config.lines);
        bridgeLines.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        bridgeLines.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        bridgeLines.setTextSize(13);
        bridgeLines.setGravity(Gravity.TOP | Gravity.LEFT);
        bridgeLines.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        bridgeLines.setMinHeight(AndroidUtilities.dp(132));
        bridgeLines.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(10),
                AndroidUtilities.dp(12), AndroidUtilities.dp(10));
        bridgeLines.setBackground(rounded(Theme.getColor(Theme.key_windowBackgroundGray), 10));
        bridgeCard.addView(bridgeLines, LayoutHelper.createLinear(-1, -2, 0, 6, 0, 0));
        bridgeCard.addView(body(context,
                "Строки мостов зашифрованы Android Keystore. Сам Tor общий для экономии памяти, поэтому изменение мостов и его перезапуск временно приостановят все Tor-контейнеры."),
                LayoutHelper.createLinear(-1, -2, 0, 8, 0, 0));
        bridgeAssistStatus = body(context, "");
        bridgeAssistStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        bridgeCard.addView(bridgeAssistStatus,
                LayoutHelper.createLinear(-1, -2, 0, 10, 0, 0));
        TextView requestBridges = action(context, "CONNECTION ASSIST · ЗАПРОСИТЬ МОСТЫ");
        requestBridges.setContentDescription("Connection Assist, запросить мосты Tor");
        requestBridges.setOnClickListener(v -> presentFragment(new AgramBridgeRequestActivity(
                bridgeRequestClient, this::applyRequestedBridges)));
        bridgeCard.addView(requestBridges, LayoutHelper.createLinear(-1, 46, 0, 8, 0, 0));
        TextView saveBridges = action(context, "СОХРАНИТЬ МОСТЫ И ПЕРЕЗАПУСТИТЬ");
        saveBridges.setOnClickListener(v -> saveBridges());
        bridgeCard.addView(saveBridges, LayoutHelper.createLinear(-1, 46, 0, 10, 0, 0));

        updateState();
        AgramSessionRouteController.getInstance().refresh(account, false);
        AndroidUtilities.runOnUIThread(statusTicker, 2_000);
        return fragmentView;
    }

    private void enableOrStartTor() {
        record = AgramContainerManager.getInstance().ensureContainer(account);
        if (!AgramContainerManager.NETWORK_TOR.equals(record.proxyMode)) {
            AgramContainerManager.getInstance().saveNetworkSettings(account,
                    AgramContainerManager.NETWORK_TOR, true, "", 0, "", "", "");
        }
        AgramNetworkController.getInstance().apply(account);
        AgramTorManager.getInstance().ensureStarted();
        updateState();
    }

    private void confirmGlobalRestart() {
        new AlertDialog.Builder(getParentActivity())
                .setTitle("Перезапустить общий Tor?")
                .setMessage("Все аккаунты, использующие встроенный Tor, временно останутся без сети. Прямого fallback не будет.")
                .setPositiveButton("Перезапустить", (dialog, which) -> AgramTorManager.getInstance().restart())
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void saveBridges() {
        try {
            AgramTorManager.getInstance().saveBridgeConfig(
                    bridgesEnabled.isChecked(), bridgeLines.getText().toString());
            AgramTorManager.getInstance().restart();
            Toast.makeText(getParentActivity(), "Настройки мостов сохранены", Toast.LENGTH_SHORT).show();
        } catch (RuntimeException error) {
            Toast.makeText(getParentActivity(), error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void applyRequestedBridges(boolean enabled, String lines) {
        AgramTorManager.getInstance().saveBridgeConfig(enabled, lines);
        bridgesEnabled.setChecked(enabled);
        bridgeLines.setText(lines);
        AgramTorManager.getInstance().restart();
        updateState();
    }

    private void updateState() {
        if (stateView == null) {
            return;
        }
        record = AgramContainerManager.getInstance().ensureContainer(account);
        AgramTorManager tor = AgramTorManager.getInstance();
        String torState = tor.getState();
        String bootstrap = tor.getBootstrapSummary();
        int progress = tor.getBootstrapProgress();
        String network = AgramNetworkController.getInstance().getState(account);
        stateView.setText("режим: " + record.proxyMode
                + "\nTor: " + torState
                + (tor.getSocksPort() > 0 ? " · SOCKS 127.0.0.1:" + tor.getSocksPort() : "")
                + ((AgramTorManager.STATE_STARTING.equals(torState)
                || AgramTorManager.STATE_READY.equals(torState))
                ? "\nbootstrap: " + progress + "%" : "")
                + (!TextUtils.isEmpty(bootstrap) ? " · " + bootstrap : "")
                + "\nконтейнер: " + network + " · fail-closed"
                + (!TextUtils.isEmpty(tor.getLastError())
                ? "\nошибка: " + tor.getLastError() : ""));
        bootstrapProgress.setProgress(progress, true);
        bootstrapProgress.setVisibility(AgramTorManager.STATE_STARTING.equals(torState)
                || AgramTorManager.STATE_READY.equals(torState) ? View.VISIBLE : View.GONE);

        AgramSessionRouteController.RouteInfo route = AgramSessionRouteController.getInstance().get(account);
        String location = route.approximateLocation();
        routeView.setText("Telegram видит:\nIP: "
                + (TextUtils.isEmpty(route.ip) ? (route.loading ? "проверяем…" : "нет данных") : route.ip)
                + "\nгео: " + (TextUtils.isEmpty(location) ? "нет данных" : location));

        boolean usesTor = AgramContainerManager.NETWORK_TOR.equals(record.proxyMode);
        primaryAction.setText(usesTor
                ? (AgramTorManager.STATE_READY.equals(torState) ? "TOR ПОДКЛЮЧЁН"
                : (AgramTorManager.STATE_STARTING.equals(torState)
                ? "TOR ЗАПУСКАЕТСЯ · " + progress + "%" : "ПОВТОРИТЬ ЗАПУСК TOR"))
                : "ИСПОЛЬЗОВАТЬ TOR ДЛЯ ЭТОГО АККАУНТА");
        boolean circuitReady = usesTor && AgramTorManager.STATE_READY.equals(torState);
        circuitAction.setEnabled(circuitReady);
        circuitAction.setAlpha(circuitReady ? 1f : .45f);

        AgramTorManager.BridgeConfig bridgeConfig = tor.getBridgeConfig();
        int bridgeCount = countBridgeLines(bridgeConfig.lines);
        String configured = bridgeConfig.enabled
                ? "Мосты включены" + (bridgeCount > 0 ? " · строк: " + bridgeCount : "")
                : "Мосты выключены";
        bridgeAssistStatus.setText("Connection Assist: "
                + (bridgeRequestClient == null ? "сервис не подключён" : "готов к запросу")
                + "\n" + configured);
    }

    private static int countBridgeLines(String lines) {
        if (TextUtils.isEmpty(lines)) {
            return 0;
        }
        int count = 0;
        for (String line : lines.split("\\r?\\n")) {
            if (!TextUtils.isEmpty(line.trim())) {
                count++;
            }
        }
        return count;
    }

    @Override
    public void onTorStateChanged(String state) {
        updateState();
    }

    @Override
    public void onSessionRouteChanged(int changedAccount, AgramSessionRouteController.RouteInfo info) {
        if (changedAccount == account) {
            updateState();
        }
    }

    private static LinearLayout card(Context context) {
        LinearLayout view = new LinearLayout(context);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(12),
                AndroidUtilities.dp(14), AndroidUtilities.dp(14));
        view.setBackground(rounded(Theme.getColor(Theme.key_windowBackgroundWhite), 14));
        return view;
    }

    private static TextView title(Context context, String value) {
        TextView view = text(context, value, 24, Theme.key_windowBackgroundWhiteBlackText, true);
        return view;
    }

    private static TextView label(Context context, String value) {
        return text(context, value, 12, Theme.key_windowBackgroundWhiteBlueHeader, true);
    }

    private static TextView body(Context context, String value) {
        TextView view = text(context, value, 13, Theme.key_windowBackgroundWhiteGrayText, false);
        view.setLineSpacing(AndroidUtilities.dp(2), 1f);
        return view;
    }

    private static TextView mono(Context context) {
        TextView view = body(context, "");
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextIsSelectable(true);
        view.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(10),
                AndroidUtilities.dp(12), AndroidUtilities.dp(10));
        view.setBackground(rounded(Theme.getColor(Theme.key_windowBackgroundGray), 10));
        return view;
    }

    private static TextView action(Context context, String value) {
        TextView view = text(context, value, 13, Theme.key_featuredStickers_buttonText, true);
        view.setGravity(Gravity.CENTER);
        view.setBackground(rounded(Theme.getColor(Theme.key_featuredStickers_addButton), 10));
        return view;
    }

    private static TextView text(Context context, String value, int size, int colorKey, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(Theme.getColor(colorKey));
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    private static GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(AndroidUtilities.dp(radius));
        return drawable;
    }
}
