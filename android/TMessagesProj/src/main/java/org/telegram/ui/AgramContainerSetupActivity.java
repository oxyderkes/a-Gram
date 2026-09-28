/*
 * This file is part of Agram and is licensed under GNU GPL v2 or later.
 */
package org.telegram.ui;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AgramContainerManager;
import org.telegram.messenger.AgramNetworkController;
import org.telegram.messenger.AgramPushController;
import org.telegram.messenger.AgramPushState;
import org.telegram.messenger.AgramDeletedMediaStore;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationsController;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.RadioButtonCell;

import java.util.ArrayList;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

/** One account is always created in one automatically assigned container. */
public class AgramContainerSetupActivity extends BaseFragment {

    private final int account;
    private AgramContainerManager.ContainerRecord record;
    private boolean activeContainer;
    private long sessionGeneration;
    private static final DispatchQueue settingsQueue = new DispatchQueue("agramSettings");
    private boolean initializing = true, dirty, saving, destroyed, archiveStatusLoading;
    private TextView continueButton, connectionStatus, pushStatus, archiveStatus;
    private final ArrayList<ThemeDescription> themeDescriptions = new ArrayList<>();
    private final Runnable statusRefresh = new Runnable() {
        @Override public void run() {
            if (destroyed || fragmentView == null) return;
            refreshConnectionStatus();
            AndroidUtilities.runOnUIThread(this, 2_000L);
        }
    };

    private ChoiceCell presetProfile;
    private ChoiceCell customProfile;
    private TextView presetButton;
    private TextView regenerateButton;
    private LinearLayout customProfileFields;
    private EditText deviceModelField;
    private EditText systemVersionField;
    private TextView profileSummary;
    private TextView preview;
    private int pendingPresetIndex;
    private String pendingProfileId;

    private ChoiceCell directNetwork;
    private ChoiceCell proxyNetwork;
    private LinearLayout proxyFields;
    private EditText proxyAddressField;
    private EditText proxyPortField;
    private EditText proxyUsernameField;
    private EditText proxyPasswordField;
    private EditText proxySecretField;
    private ToggleCell killSwitch;

    private ChoiceCell agramPush;
    private ChoiceCell directPush;

    private EditText pinField;
    private ToggleCell biometricSwitch;
    private ToggleCell contactsSyncSwitch;
    private ToggleCell keepDeletedSwitch;
    private ToggleCell ghostReadSwitch;
    private ToggleCell ghostStoriesSwitch;
    private ToggleCell ghostTypingSwitch;
    private ToggleCell ghostOnlineSwitch;
    private ToggleCell ghostReadOnInteractionSwitch;
    private ToggleCell ghostWarnSwitch;
    private ChoiceCell hiddenNotifications;
    private ChoiceCell authorNotifications;
    private ChoiceCell fullNotifications;
    public AgramContainerSetupActivity() {
        this(UserConfig.getLoginTargetAccount());
    }

    public AgramContainerSetupActivity(int account) {
        this.account = account;
        currentAccount = account;
    }

    @Override
    public boolean onFragmentCreate() {
        sessionGeneration = UserConfig.getInstance(account).getSessionGeneration();
        activeContainer = UserConfig.getInstance(account).isClientActivated();
        if (activeContainer && account != UserConfig.selectedAccount) {
            return false;
        }
        try {
            record = AgramContainerManager.getInstance().ensureFreshContainerForSessionState(account);
        } catch (AgramContainerManager.ContainerPersistenceException error) {
            Toast.makeText(org.telegram.messenger.ApplicationLoader.applicationContext,
                    "Не удалось открыть настройки: хранилище недоступно. Данные аккаунта не удалены.", Toast.LENGTH_LONG).show();
            return false;
        }
        if (record == null || !record.isStorageAccessible()) {
            Toast.makeText(org.telegram.messenger.ApplicationLoader.applicationContext,
                    "Разблокируйте устройство и повторите попытку. Хранилище аккаунта недоступно.", Toast.LENGTH_LONG).show();
            return false;
        }
        pendingPresetIndex = record.presetIndex;
        pendingProfileId = TextUtils.isEmpty(record.profileId) ? UUID.randomUUID().toString() : record.profileId;
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        themeDescriptions.clear();
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(activeContainer ? "Контейнер аккаунта" : "Новый аккаунт");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1 && onBackPressed(true)) finishFragment();
            }
        });

        FrameLayout root = new FrameLayout(context);
        fragmentView = root;
        updatePageBackground();
        android.widget.ScrollView scroll = new android.widget.ScrollView(context);
        scroll.setFillViewport(true);
        AndroidUtilities.setScrollViewEdgeEffectColor(scroll, Theme.getColor(Theme.key_actionBarDefault));
        themeDescriptions.add(new ThemeDescription(scroll, ThemeDescription.FLAG_LISTGLOWCOLOR, null, null, null, null, Theme.key_actionBarDefault));
        root.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(20), AndroidUtilities.dp(20), AndroidUtilities.dp(32));
        scroll.addView(content, new android.widget.ScrollView.LayoutParams(-1, -2));

        content.addView(text(context, activeContainer ? "Настройки аккаунта" : "Перед входом", 24,
                Theme.key_windowBackgroundWhiteBlackText, true));
        TextView subtitle = text(context,
                activeContainer
                        ? "Изменения применятся только к этому аккаунту."
                        : "Выберите профиль устройства. Контейнер создаётся автоматически.",
                15, Theme.key_windowBackgroundWhiteGrayText, false);
        subtitle.setLineSpacing(AndroidUtilities.dp(2), 1f);
        content.addView(subtitle, LayoutHelper.createLinear(-1, -2, 0, 8, 0, 18));

        addProfileCard(context, content);
        addNetworkCard(context, content);
        addPushCard(context, content);
        if (activeContainer) addContactsCard(context, content);
        addGhostCard(context, content);
        addSecurityCard(context, content);
        addArchiveCard(context, content);
        continueButton = text(context, activeContainer ? "СОХРАНИТЬ НАСТРОЙКИ" : "ПРОДОЛЖИТЬ К ВХОДУ",
                14, Theme.key_featuredStickers_buttonText, true);
        continueButton.setGravity(Gravity.CENTER);
        themedBackground(continueButton, Theme.key_featuredStickers_addButton, 12);
        continueButton.setOnClickListener(v -> saveAndContinue());
        content.addView(continueButton, LayoutHelper.createLinear(-1, 52, 0, 8, 0, 0));

        applyLockedProfileState();
        selectProfileMode(record.profileMode == AgramContainerManager.PROFILE_CUSTOM
                ? AgramContainerManager.PROFILE_CUSTOM : AgramContainerManager.PROFILE_PRESET);
        selectNetworkMode(record.proxyMode);
        selectPushMode(record.pushMode);
        updatePreview();
        watchInputChanges();
        initializing = false;
        refreshConnectionStatus();
        refreshArchiveStatus();
        return fragmentView;
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (!invoked) return !dirty && !saving;
        if (saving) return false;
        if (!dirty) return true;
        showDialog(new AlertDialog.Builder(getParentActivity())
                .setTitle("Несохранённые изменения")
                .setMessage("Выйти без сохранения настроек?")
                .setPositiveButton("Выйти", (dialog, which) -> { dirty = false; finishFragment(); })
                .setNegativeButton("Остаться", null).create());
        return false;
    }

    @Override
    public void onResume() {
        super.onResume();
        updatePageBackground();
        AndroidUtilities.cancelRunOnUIThread(statusRefresh);
        AndroidUtilities.runOnUIThread(statusRefresh);
    }

    @Override
    public void onPause() {
        AndroidUtilities.cancelRunOnUIThread(statusRefresh);
        super.onPause();
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        AndroidUtilities.cancelRunOnUIThread(statusRefresh);
        super.onFragmentDestroy();
    }

    private void markChanged() { if (!initializing) dirty = true; }

    private void watchInputChanges() {
        TextWatcher watcher = new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { markChanged(); }
            public void afterTextChanged(Editable s) { }
        };
        for (EditText input : new EditText[]{deviceModelField, systemVersionField, proxyAddressField,
                proxyPortField, proxyUsernameField, proxyPasswordField, proxySecretField, pinField}) {
            input.addTextChangedListener(watcher);
            input.setSaveEnabled(false);
        }
    }

    private void refreshConnectionStatus() {
        if (connectionStatus == null || destroyed) return;
        try {
            refreshAccessibleConnectionStatus();
        } catch (RuntimeException unavailableStorage) {
            // A diagnostics timer must not crash the screen or interpret a
            // temporarily unavailable registry as a logged-out account.
            setStatusText(connectionStatus, "Не удалось прочитать состояние локального хранилища. Данные не удалены.");
            if (pushStatus != null) setStatusText(pushStatus, "Состояние Push временно недоступно.");
        }
    }

    private void refreshAccessibleConnectionStatus() {
        AgramContainerManager.ContainerRecord current = AgramContainerManager.getInstance().getContainer(account);
        if (current == null || !record.id.equals(current.id)) {
            setStatusText(connectionStatus, "Владелец этого экрана изменился. Откройте настройки нужного аккаунта заново.");
            if (pushStatus != null) setStatusText(pushStatus, "Состояние прежнего контейнера не отображается.");
            return;
        }
        String connection;
        if (!current.isStorageAccessible()) connection = "Локальное хранилище недоступно — сеть приостановлена";
        else {
            ConnectionsManager transport = ConnectionsManager.getInstance(account);
            String ownerStatus = transport.getLocalAuthConfigStatus();
            if ("native_owner_pending".equals(ownerStatus)) {
                connection = "Проверяется привязка ключей к контейнеру — сеть приостановлена";
            } else if ("native_owner_unavailable".equals(ownerStatus)) {
                connection = "Привязка ключей к контейнеру не подтверждена — сеть закрыта, данные сохранены";
            } else if ("native_owner_retired".equals(ownerStatus)) {
                connection = "Сессия завершена; освобождается контейнер";
            } else if (!activeContainer) {
                connection = "Вход ещё не выполнен";
            } else switch (transport.getConnectionState()) {
                case ConnectionsManager.ConnectionStateConnected: connection = "Подключено"; break;
                case ConnectionsManager.ConnectionStateUpdating: connection = "Синхронизация"; break;
                case ConnectionsManager.ConnectionStateConnectingToProxy: connection = "Подключение к прокси"; break;
                case ConnectionsManager.ConnectionStateWaitingForNetwork: connection = "Нет доступного соединения"; break;
                case ConnectionsManager.ConnectionStateLocalAuthUnavailable: connection = "Локальные ключи недоступны — данные сохранены"; break;
                default: connection = "Подключение";
            }
        }
        setStatusText(connectionStatus, "Telegram: " + connection + "\nМаршрут: "
                + (AgramContainerManager.NETWORK_PROXY.equals(current.proxyMode) ? "прокси" : "прямой")
                + "\nСостояние маршрута: " + routeStateLabel(AgramNetworkController.getInstance().getState(account)));
        if (pushStatus != null) {
            if (AgramContainerManager.PUSH_DIRECT.equals(current.pushMode)) {
                setStatusText(pushStatus, "Доставка через соединение Telegram. Отдельный Push-канал не используется.");
            } else {
                AgramPushState.Snapshot state = AgramPushState.snapshot(account, current.id);
                setStatusText(pushStatus, "Канал Push: " + stateLabel(state.stream)
                        + "\nРегистрация в Telegram: " + stateLabel(state.registration)
                        + (TextUtils.isEmpty(state.streamError) ? "" : "\n" + state.streamError)
                        + (TextUtils.isEmpty(state.registrationError) ? "" : "\n" + state.registrationError)
                        + "\nПоследнее изменение: " + (state.updatedAt == 0 ? "нет данных"
                        : java.text.DateFormat.getTimeInstance().format(new java.util.Date(state.updatedAt))));
            }
        }
    }

    private static void setStatusText(TextView view, String text) {
        // Polling must not relayout unchanged cards while the user scrolls or types.
        if (!TextUtils.equals(view.getText(), text)) view.setText(text);
    }

    private static String routeStateLabel(String state) {
        if ("direct".equals(state)) return "прямое соединение разрешено";
        if ("proxy_active".equals(state)) return "прокси настроен; доступность — в строке Telegram";
        if ("proxy_required".equals(state)) return "нужен адрес прокси, сеть закрыта";
        if ("proxy_error".equals(state)) return "прокси недоступен, kill switch остановил сеть";
        if ("proxy_reconnecting".equals(state)) return "повторное подключение через прокси";
        if ("local_storage_unavailable".equals(state)) return "хранилище недоступно, данные сохранены";
        if ("local_auth_unavailable".equals(state)) return "ключи сессии недоступны, данные сохранены";
        if ("native_owner_pending".equals(state)) return "ожидание подтверждения владельца ключей";
        if ("native_owner_unavailable".equals(state)) return "владелец ключей не подтверждён, сеть закрыта";
        if ("container_unavailable".equals(state)) return "контейнер недоступен, сеть закрыта";
        if ("route_unavailable".equals(state)) return "маршрут не поддерживается, сеть закрыта";
        return "ещё не применён";
    }

    private void addContactsCard(Context context, LinearLayout content) {
        LinearLayout card = card(context);
        content.addView(card, LayoutHelper.createLinear(-1, -2, 0, 0, 0, 12));
        card.addView(sectionLabel(context, "КОНТАКТЫ"));
        contactsSyncSwitch = settingSwitch(context, "Синхронизация контактов",
                "Передавать имена и номера из телефона в Telegram.",
                UserConfig.getInstance(account).isContactSyncAllowed());
        card.addView(contactsSyncSwitch);
        addDetails(context, card, "По умолчанию выключено. Синхронизация требует разрешения Android "
                + "и включается отдельно для этого аккаунта. Выключение не удаляет ранее загруженные контакты.");
    }

    private static String stateLabel(String state) {
        if ("connected".equals(state)) return "подключён";
        if ("registered".equals(state)) return "зарегистрирован";
        if ("registering".equals(state)) return "регистрация";
        if ("connecting".equals(state)) return "подключение";
        if ("reconnecting".equals(state)) return "повторное подключение";
        if ("error".equals(state) || "configuration_error".equals(state)) return "ошибка";
        if ("stopped".equals(state)) return "остановлен";
        return "не проверена";
    }

    private void addArchiveCard(Context context, LinearLayout content) {
        LinearLayout card = card(context);
        content.addView(card, LayoutHelper.createLinear(-1, -2, 0, 0, 0, 12));
        card.addView(sectionLabel(context, "ПОСТОЯННЫЙ АРХИВ"));
        keepDeletedSwitch = settingSwitch(context, "Сохранять удалённые",
                "Сообщения остаются в чате с {DELETED}.",
                MessagesController.getInstance(account).isKeepDeletedMessagesEnabled());
        keepDeletedSwitch.setContentDescription("Сохранять удалённые сообщения");
        card.addView(keepDeletedSwitch);
        addDetails(context, card, "Сохранённые удалённые сообщения остаются в переписке с отметкой {DELETED}. "
                + "Выключение не удаляет уже сохранённые сообщения. "
                + "Секретные чаты, исчезающие сообщения и защищённый контент не архивируются.\n\n"
                + "Очистка кэша освобождает временные файлы и сохраняет архив. Очистка локальной базы, "
                + "явное удаление сообщения или истории могут удалить соответствующий архив без возможности восстановления с сервера. "
                + "Выход из аккаунта, очистка данных Android и удаление приложения удаляют локальные данные. "
                + "Архив находится в приватной папке приложения, но не зашифрован отдельным ключом PIN.");
        archiveStatus = text(context, "Нажмите «Обновить», чтобы проверить хранилище.", 14,
                Theme.key_windowBackgroundWhiteBlackText, false);
        card.addView(archiveStatus, LayoutHelper.createLinear(-1, -2, 0, 16, 0, 12));
        TextView refresh = action(context, "Обновить состояние");
        refresh.setOnClickListener(v -> refreshArchiveStatus());
        card.addView(refresh, LayoutHelper.createLinear(-1, 48));
        TextView retry = action(context, "Повторить неудачные сохранения");
        retry.setOnClickListener(v -> {
            if (!sameContainer()) return;
            AgramDeletedMediaStore.retryPending(account);
            refreshArchiveStatus();
        });
        card.addView(retry, LayoutHelper.createLinear(-1, 48, 0, 8, 0, 0));
    }

    private boolean sameContainer() {
        try {
            AgramContainerManager.ContainerRecord current = AgramContainerManager.getInstance().getContainer(account);
            return current != null && current.isStorageAccessible() && record.id.equals(current.id)
                    && UserConfig.getInstance(account).isSessionGenerationCurrent(sessionGeneration);
        } catch (RuntimeException unavailableStorage) {
            return false;
        }
    }

    private void refreshArchiveStatus() {
        if (archiveStatus == null || archiveStatusLoading || !sameContainer()) return;
        archiveStatusLoading = true;
        archiveStatus.setText("Проверка хранилища…");
        AgramDeletedMediaStore.requestStatus(account, status -> {
            archiveStatusLoading = false;
            if (destroyed || !sameContainer()) return;
            archiveStatus.setText("Сохранено: " + status.files + " файлов · "
                    + AndroidUtilities.formatFileSize(status.bytes)
                    + "\nОжидает: " + status.pending + " · с ошибкой: " + status.failed
                    + "\nСвободно: " + AndroidUtilities.formatFileSize(status.freeBytes)
                    + (TextUtils.isEmpty(status.lastError) ? "" : "\n" + status.lastError));
        });
    }

    private void addProfileCard(Context context, LinearLayout content) {
        LinearLayout card = card(context);
        content.addView(card, LayoutHelper.createLinear(-1, -2, 0, 0, 0, 12));
        card.addView(sectionLabel(context, "ПРОФИЛЬ УСТРОЙСТВА"));
        presetProfile = radio(context, "Один из 10 пресетов",
                "Готовая модель и версия Android.");
        customProfile = radio(context, "Вручную",
                "Своя модель и версия Android.");
        card.addView(presetProfile);
        card.addView(customProfile);
        presetProfile.setOnClickListener(v -> selectProfileMode(AgramContainerManager.PROFILE_PRESET));
        customProfile.setOnClickListener(v -> selectProfileMode(AgramContainerManager.PROFILE_CUSTOM));

        presetButton = action(context, presetLabel());
        presetButton.setOnClickListener(v -> showPresetPicker());
        card.addView(presetButton, LayoutHelper.createLinear(-1, 48, 0, 6, 0, 0));
        regenerateButton = action(context, "СГЕНЕРИРОВАТЬ ДРУГОЙ ПРОФИЛЬ");
        regenerateButton.setOnClickListener(v -> regenerateProfile());
        card.addView(regenerateButton, LayoutHelper.createLinear(-1, 44, 0, 8, 0, 0));

        customProfileFields = new LinearLayout(context);
        customProfileFields.setOrientation(LinearLayout.VERTICAL);
        AgramContainerManager.ProfilePreset preset = AgramContainerManager.getProfilePreset(pendingPresetIndex);
        deviceModelField = profileInput(context, "Модель устройства", valueOr(record.deviceModel, preset.deviceModel));
        systemVersionField = profileInput(context, "Версия Android", valueOr(record.systemVersion, preset.systemVersion));
        customProfileFields.addView(deviceModelField, LayoutHelper.createLinear(-1, 48, 0, 6, 0, 0));
        customProfileFields.addView(systemVersionField, LayoutHelper.createLinear(-1, 48, 0, 6, 0, 0));
        card.addView(customProfileFields, LayoutHelper.createLinear(-1, -2));

        TextWatcher watcher = new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { updatePreview(); }
            public void afterTextChanged(Editable s) { }
        };
        deviceModelField.addTextChangedListener(watcher);
        systemVersionField.addTextChangedListener(watcher);

        profileSummary = text(context, "", 15, Theme.key_windowBackgroundWhiteBlackText, false);
        profileSummary.setVisibility(activeContainer ? View.VISIBLE : View.GONE);
        card.addView(profileSummary, LayoutHelper.createLinear(-1, -2));
        preview = text(context, "", 13, Theme.key_windowBackgroundWhiteGrayText, false);
        preview.setTypeface(android.graphics.Typeface.MONOSPACE);
        preview.setTextIsSelectable(true);
        themedBackground(preview, Theme.key_dialogBackgroundGray, 12);
        preview.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(12), AndroidUtilities.dp(14), AndroidUtilities.dp(12));
        addExpandableDetails(context, card, "Данные для Telegram", preview);
    }

    private void addNetworkCard(Context context, LinearLayout content) {
        LinearLayout card = card(context);
        content.addView(card, LayoutHelper.createLinear(-1, -2, 0, 0, 0, 12));
        card.addView(sectionLabel(context, "СЕТЬ"));
        directNetwork = radio(context, "Прямое", "Без локального прокси.");
        proxyNetwork = radio(context, "Свой прокси", "SOCKS5 или MTProto.");
        card.addView(directNetwork);
        card.addView(proxyNetwork);
        directNetwork.setOnClickListener(v -> selectNetworkMode(AgramContainerManager.NETWORK_DIRECT));
        proxyNetwork.setOnClickListener(v -> selectNetworkMode(AgramContainerManager.NETWORK_PROXY));

        proxyFields = new LinearLayout(context);
        proxyFields.setOrientation(LinearLayout.VERTICAL);
        proxyAddressField = profileInput(context, "Адрес прокси", record.proxyAddress);
        proxyPortField = profileInput(context, "Порт", Integer.toString(record.proxyPort > 0 ? record.proxyPort : 1080));
        proxyPortField.setInputType(InputType.TYPE_CLASS_NUMBER);
        proxyUsernameField = profileInput(context, "Логин (необязательно)", record.proxyUsername);
        proxyPasswordField = profileInput(context, "Пароль (необязательно)", record.proxyPassword);
        proxyPasswordField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        proxySecretField = profileInput(context, "MTProto secret (необязательно)", record.proxySecret);
        proxyFields.addView(proxyAddressField, LayoutHelper.createLinear(-1, 48, 0, 6, 0, 0));
        proxyFields.addView(proxyPortField, LayoutHelper.createLinear(-1, 48, 0, 6, 0, 0));
        proxyFields.addView(proxyUsernameField, LayoutHelper.createLinear(-1, 48, 0, 6, 0, 0));
        proxyFields.addView(proxyPasswordField, LayoutHelper.createLinear(-1, 48, 0, 6, 0, 0));
        proxyFields.addView(proxySecretField, LayoutHelper.createLinear(-1, 48, 0, 6, 0, 0));
        card.addView(proxyFields, LayoutHelper.createLinear(-1, -2));

        killSwitch = settingSwitch(context, "Kill switch: не выходить в сеть без выбранного маршрута", record.killSwitch);
        card.addView(killSwitch);
        connectionStatus = text(context, "", 14, Theme.key_windowBackgroundWhiteBlackText, false);
        card.addView(connectionStatus, LayoutHelper.createLinear(-1, -2, 0, 12, 0, 8));
        TextView retryRoute = action(context, "Повторить подключение");
        retryRoute.setOnClickListener(v -> {
            if (!sameContainer()) return;
            AgramNetworkController.getInstance().apply(account);
            refreshConnectionStatus();
        });
        card.addView(retryRoute, LayoutHelper.createLinear(-1, 48));
        addDetails(context, card, "При ошибке прокси блокировка сети приостанавливает MTProto этого аккаунта. "
                + "Локальная история остаётся доступной.\n\n"
                + "При выбранном прокси звонки, прямые эфиры и внешние загрузки через ImageLoader заблокированы. "
                + "Обычные сообщения и медиа Telegram используют маршрут MTProto. Встроенные веб-страницы, "
                + "мини-приложения, платежи и сторонние сервисы ещё не изолированы этим маршрутом "
                + "и могут подключаться напрямую. Это не системный VPN.");
    }

    private void addPushCard(Context context, LinearLayout content) {
        LinearLayout card = card(context);
        content.addView(card, LayoutHelper.createLinear(-1, -2, 0, 0, 0, 12));
        card.addView(sectionLabel(context, "PUSH"));
        agramPush = radio(context, "Agram Push", "Отдельный канал для этого аккаунта.");
        directPush = radio(context, "Telegram", "Через соединение аккаунта, без Push-сервера.");
        card.addView(agramPush);
        card.addView(directPush);
        pushStatus = text(context, "", 14, Theme.key_windowBackgroundWhiteBlackText, false);
        card.addView(pushStatus, LayoutHelper.createLinear(-1, -2, 0, 12, 0, 8));
        TextView retryPush = action(context, "Переподключить Push");
        retryPush.setOnClickListener(v -> {
            if (!sameContainer()) return;
            AgramPushController.getInstance().onNetworkRouteChanged(account);
            refreshConnectionStatus();
        });
        card.addView(retryPush, LayoutHelper.createLinear(-1, 48));
        agramPush.setOnClickListener(v -> selectPushMode(AgramContainerManager.PUSH_AGRAM));
        directPush.setOnClickListener(v -> selectPushMode(AgramContainerManager.PUSH_DIRECT));
        addDetails(context, card, "Сервер: " + AgramPushController.getInstance().relayHost()
                + ". Сервер видит IP подключения, но не текст сообщений. Внешнее приложение не требуется.\n\n"
                + "Endpoint хранится в зашифрованной записи контейнера и регистрируется в Telegram "
                + "как Simple Push type 4 без объединения other_uids. Через SOCKS5 push следует маршруту контейнера. "
                + "MTProto-прокси не переносит HTTPS push: Agram Push остаётся недоступен, а не подключается напрямую.");
    }

    private void addGhostCard(Context context, LinearLayout content) {
        LinearLayout card = card(context);
        content.addView(card, LayoutHelper.createLinear(-1, -2, 0, 0, 0, 12));
        card.addView(sectionLabel(context, "GHOST MODE"));
        ghostReadSwitch = settingSwitch(context, "Не отправлять отметку о прочтении", record.ghostSuppressReadReceipts);
        ghostStoriesSwitch = settingSwitch(context, "Не показывать просмотр историй", record.ghostSuppressStoryViews);
        ghostTypingSwitch = settingSwitch(context, "Не отправлять typing / recording", record.ghostSuppressTyping);
        ghostOnlineSwitch = settingSwitch(context, "Минимизировать online", record.ghostMinimizeOnline);
        ghostReadOnInteractionSwitch = settingSwitch(context, "Прочитать при взаимодействии", record.ghostReadOnInteraction);
        ghostWarnSwitch = settingSwitch(context, "Предупреждать перед реакцией или ответом", record.ghostWarnBeforeInteraction);
        card.addView(ghostReadSwitch);
        card.addView(ghostStoriesSwitch);
        card.addView(ghostTypingSwitch);
        card.addView(ghostOnlineSwitch);
        card.addView(ghostReadOnInteractionSwitch);
        card.addView(ghostWarnSwitch);
        ghostReadSwitch.setOnCheckedChangeListener((button, checked) -> {
            ghostReadOnInteractionSwitch.setEnabled(checked);
            ghostReadOnInteractionSwitch.setAlpha(checked ? 1f : .5f);
        });
    }

    private void addSecurityCard(Context context, LinearLayout content) {
        LinearLayout card = card(context);
        content.addView(card, LayoutHelper.createLinear(-1, -2, 0, 0, 0, 12));
        card.addView(sectionLabel(context, "ЛОКАЛЬНАЯ ЗАЩИТА"));
        pinField = input(context, activeContainer ? "Новый PIN (необязательно)" : "PIN (необязательно)");
        pinField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        card.addView(pinField, LayoutHelper.createLinear(-1, 52));
        biometricSwitch = settingSwitch(context, "Биометрия после настройки PIN", record.biometricEnabled);
        card.addView(biometricSwitch);
        card.addView(text(context, "Минимум 6 символов. Блокировка при запуске и после минуты в фоне.",
                13, Theme.key_windowBackgroundWhiteGrayText, false));
        card.addView(sectionLabel(context, "УВЕДОМЛЕНИЯ"), LayoutHelper.createLinear(-1, -2, 0, 12, 0, 0));
        hiddenNotifications = radio(context, "Скрытые", "Только Agram и факт нового сообщения.");
        authorNotifications = radio(context, "Только автор", "Без текста и вложений.");
        fullNotifications = radio(context, "Полные", "Стандартный текст и доступные превью.");
        card.addView(hiddenNotifications);
        card.addView(authorNotifications);
        card.addView(fullNotifications);
        hiddenNotifications.setOnClickListener(v -> selectNotificationPrivacy(AgramContainerManager.NOTIFICATION_HIDDEN));
        authorNotifications.setOnClickListener(v -> selectNotificationPrivacy(AgramContainerManager.NOTIFICATION_AUTHOR));
        fullNotifications.setOnClickListener(v -> selectNotificationPrivacy(AgramContainerManager.NOTIFICATION_FULL));
        selectNotificationPrivacy(record.notificationPrivacy);
        addDetails(context, card, "Пустое поле PIN сохраняет текущий код. PIN блокирует интерфейс, "
                + "но не фоновую доставку и не шифрует базу сообщений или архив. "
                + "Локальные данные разделены по аккаунтам; ключи контейнера хранятся в Android Keystore.");
    }

    private void saveAndContinue() {
        if (saving || !sameContainer()) return;
        String pin = pinField.getText().toString();
        if (!TextUtils.isEmpty(pin) && pin.length() < 6) { toast("PIN должен содержать не менее 6 символов"); return; }
        if (biometricSwitch.isChecked() && TextUtils.isEmpty(pin) && !record.hasPin()) { toast("Сначала задайте PIN контейнера"); return; }
        int mode = selectedProfileMode();
        if (!record.profileLocked && mode == AgramContainerManager.PROFILE_CUSTOM
                && (TextUtils.isEmpty(deviceModelField.getText().toString().trim())
                || TextUtils.isEmpty(systemVersionField.getText().toString().trim()))) {
            toast("Заполните модель устройства и версию Android"); return;
        }
        String networkMode = selectedNetworkMode();
        int proxyPort = parsePort(proxyPortField.getText().toString());
        if (AgramContainerManager.NETWORK_PROXY.equals(networkMode)
                && (TextUtils.isEmpty(proxyAddressField.getText().toString().trim()) || proxyPort == 0)) {
            toast("Укажите корректные адрес и порт прокси"); return;
        }
        String pushMode = selectedPushMode();

        final String containerId = record.id;
        final String containerName = record.name;
        final boolean profileLocked = record.profileLocked;
        final int presetIndex = pendingPresetIndex;
        final String profileId = pendingProfileId;
        final String model = deviceModelField.getText().toString();
        final String system = systemVersionField.getText().toString();
        final String sysLanguage = systemLanguage(), appLanguage = clientLanguage();
        final int timezone = timezoneOffset(), privacy = selectedNotificationPrivacy();
        final boolean biometric = biometricSwitch.isChecked(), kill = killSwitch.isChecked();
        final boolean syncContacts = contactsSyncSwitch != null && contactsSyncSwitch.isChecked();
        final boolean keepDeleted = keepDeletedSwitch.isChecked();
        final boolean enableContacts = syncContacts && !UserConfig.getInstance(account).isContactSyncAllowed();
        final String address = proxyAddressField.getText().toString().trim();
        final String username = proxyUsernameField.getText().toString();
        final String password = proxyPasswordField.getText().toString();
        final String secret = proxySecretField.getText().toString().trim();
        final String oldEndpoint = record.agramPushEndpoint;
        final boolean ghost = AgramContainerManager.getInstance().isGhostModeEnabled(account);
        final boolean read = ghostReadSwitch.isChecked(), stories = ghostStoriesSwitch.isChecked();
        final boolean typing = ghostTypingSwitch.isChecked(), online = ghostOnlineSwitch.isChecked();
        final boolean readOnAction = ghostReadOnInteractionSwitch.isChecked(), warn = ghostWarnSwitch.isChecked();
        saving = true;
        continueButton.setEnabled(false);
        continueButton.setText("Сохранение…");
        settingsQueue.postRunnable(() -> {
            try {
                AgramContainerManager manager = AgramContainerManager.getInstance();
                manager.runBoundSettingsUpdate(account, containerId, () -> {
                    if (!profileLocked) {
                        manager.updatePreLoginProfile(account, mode, presetIndex, model, system,
                                sysLanguage, appLanguage, false, timezone, profileId,
                                pin, biometric, privacy);
                    } else {
                        manager.updateContainerSecurity(account, containerName, pin, biometric, privacy);
                    }
                    manager.updateGhostMode(account, ghost, read, stories, typing, online, readOnAction, warn);
                    manager.saveNetworkSettings(account, networkMode, kill, address, proxyPort, username, password, secret);
                    manager.savePushSettings(account, pushMode);
                });
                if (!MessagesController.getInstance(account).setKeepDeletedMessagesEnabled(
                        keepDeleted, containerId, sessionGeneration)) {
                    throw new IllegalStateException("Deleted message preference could not be persisted");
                }
                if (activeContainer && (!sameContainer() || !UserConfig.getInstance(account).setContactSyncEnabled(syncContacts, sessionGeneration))) {
                    throw new IllegalStateException("Contact consent could not be persisted");
                }
                AndroidUtilities.runOnUIThread(() -> {
                    saving = false;
                    if (destroyed) return;
                    if (!sameContainer()) {
                        toast("Этот контейнер больше недоступен. Откройте настройки нужного аккаунта заново.");
                        finishFragment();
                        return;
                    }
                    record = AgramContainerManager.getInstance().getContainer(account);
                    pinField.setText("");
                    dirty = false;
                    try {
                        if (AgramContainerManager.PUSH_DIRECT.equals(pushMode) && !TextUtils.isEmpty(oldEndpoint)) {
                            org.telegram.messenger.MessagesController.getInstance(account).unregisterAgramPush(oldEndpoint);
                            AgramPushState.clear(account);
                        }
                        NotificationsController.getInstance(account).refreshAgramNotificationPrivacy();
                        AgramPushController.getInstance().onPushSettingsChanged(account);
                        AgramNetworkController.getInstance().apply(account);
                        if (enableContacts && activeContainer && org.telegram.messenger.ContactsController.hasContactsPermission()) {
                            org.telegram.messenger.ContactsController.getInstance(account).forceImportContacts();
                        }
                        finishSuccessfulSave();
                    } catch (RuntimeException error) {
                        FileLog.e("Agram settings activation failed: " + error.getClass().getSimpleName());
                        continueButton.setEnabled(true);
                        continueButton.setText(activeContainer ? "СОХРАНИТЬ НАСТРОЙКИ" : "ПРОДОЛЖИТЬ К ВХОДУ");
                        toast("Настройки записаны, но не удалось применить соединение. Повторите попытку или перезапустите приложение.");
                    }
                });
            } catch (Exception error) {
                FileLog.e("Agram settings save failed: " + error.getClass().getSimpleName());
                AndroidUtilities.runOnUIThread(() -> {
                    saving = false;
                    if (destroyed) return;
                    continueButton.setEnabled(true);
                    continueButton.setText(activeContainer ? "СОХРАНИТЬ НАСТРОЙКИ" : "ПРОДОЛЖИТЬ К ВХОДУ");
                    toast("Не удалось сохранить все настройки. Проверьте свободное место и доступ к хранилищу. "
                            + "Часть изменений могла сохраниться; вход не продолжен.");
                });
            }
        });
    }

    private void finishSuccessfulSave() {
        if (activeContainer) {
            toast("Настройки контейнера сохранены");
            finishFragment();
            return;
        }
        boolean hasActiveAccount = false;
        for (int slot = 0; slot < UserConfig.MAX_ACCOUNT_COUNT; slot++) {
            if (UserConfig.getInstance(slot).isClientActivated()) { hasActiveAccount = true; break; }
        }
        if (hasActiveAccount) {
            presentFragment(new LoginActivity(account), true);
        } else if (UserConfig.setSelectedAccountPersisted(account)) {
            presentFragment(new LoginActivity(), true);
        } else {
            continueButton.setEnabled(true);
            continueButton.setText("ПРОДОЛЖИТЬ К ВХОДУ");
            toast("Не удалось сохранить выбранный аккаунт. Проверьте свободное место.");
        }
    }

    private void showPresetPicker() {
        CharSequence[] labels = new CharSequence[AgramContainerManager.getProfilePresetCount()];
        for (int i = 0; i < labels.length; i++) {
            AgramContainerManager.ProfilePreset preset = AgramContainerManager.getProfilePreset(i);
            labels[i] = (i == pendingPresetIndex ? "✓ " : "") + preset.title
                    + "\n" + preset.deviceModel + " · " + preset.systemVersion;
        }
        new AlertDialog.Builder(getParentActivity())
                .setTitle("Пресет устройства")
                .setItems(labels, (dialog, which) -> {
                    markChanged();
                    pendingPresetIndex = which;
                    pendingProfileId = UUID.randomUUID().toString();
                    presetButton.setText(presetLabel());
                    updatePreview();
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
    }

    private void regenerateProfile() {
        markChanged();
        int next = new java.security.SecureRandom().nextInt(AgramContainerManager.getProfilePresetCount());
        if (AgramContainerManager.getProfilePresetCount() > 1 && next == pendingPresetIndex) {
            next = (next + 1) % AgramContainerManager.getProfilePresetCount();
        }
        pendingPresetIndex = next;
        pendingProfileId = UUID.randomUUID().toString();
        selectProfileMode(AgramContainerManager.PROFILE_PRESET);
        presetButton.setText(presetLabel());
        updatePreview();
    }

    private void updatePreview() {
        if (preview == null) return;
        String model;
        String system;
        String previewClientLanguage = clientLanguage();
        String previewSystemLanguage = systemLanguage();
        int previewTimezone = timezoneOffset();
        if (activeContainer) {
            AgramContainerManager.SessionProfile locked = AgramContainerManager.getInstance().resolveSessionProfile(
                    account, detectedDeviceModel(), detectedSystemVersion(), detectedAppVersion(),
                    previewClientLanguage, previewSystemLanguage, previewTimezone);
            model = locked.deviceModel;
            system = locked.systemVersion;
            previewClientLanguage = locked.languageCode;
            previewSystemLanguage = locked.systemLanguageCode;
            previewTimezone = locked.timezoneOffset;
        } else if (selectedProfileMode() == AgramContainerManager.PROFILE_CUSTOM) {
            model = profileText(deviceModelField, detectedDeviceModel());
            system = profileText(systemVersionField, detectedSystemVersion());
        } else {
            AgramContainerManager.ProfilePreset preset = AgramContainerManager.getProfilePreset(pendingPresetIndex);
            model = preset.deviceModel;
            system = preset.systemVersion;
        }

        profileSummary.setText(model + "\n" + system);
        preview.setText(
                "ПЕРЕДАЁТСЯ TELEGRAM\n" +
                        "device_model: " + model +
                        "\nplatform: android" +
                        "\nsystem_version: " + system +
                        "\napp_version: " + detectedAppVersion() +
                        "\napi_id: " + BuildVars.APP_ID +
                        "\nlang_code: " + previewClientLanguage +
                        "\nsystem_lang_code: " + previewSystemLanguage +
                        "\ntz_offset: " + previewTimezone +
                        "\nofficial_app: определяется Telegram (не подделывается)" +
                        "\n\nЛокальный ID профиля: " + pendingProfileId
                        + "\n\nВерсия Agram и api_id настоящие. "
                        + (activeContainer ? "Профиль зафиксирован до завершения сессии."
                        : "После входа профиль фиксируется до завершения сессии."));
    }

    private void applyLockedProfileState() {
        if (!activeContainer) return;
        presetProfile.setVisibility(View.GONE);
        customProfile.setVisibility(View.GONE);
        presetButton.setVisibility(View.GONE);
        regenerateButton.setVisibility(View.GONE);
        customProfileFields.setVisibility(View.GONE);
        presetProfile.setEnabled(false);
        customProfile.setEnabled(false);
        presetButton.setEnabled(false);
        presetButton.setAlpha(.5f);
        regenerateButton.setEnabled(false);
        regenerateButton.setAlpha(.5f);
        deviceModelField.setEnabled(false);
        systemVersionField.setEnabled(false);
    }

    private void selectProfileMode(int mode) {
        markChanged();
        boolean custom = mode == AgramContainerManager.PROFILE_CUSTOM;
        if (!custom) {
            View focused = fragmentView == null ? null : fragmentView.findFocus();
            if (focused != null) {
                AndroidUtilities.hideKeyboard(focused);
            }
            if (deviceModelField != null) {
                deviceModelField.clearFocus();
            }
            if (systemVersionField != null) {
                systemVersionField.clearFocus();
            }
        }
        customProfile.setChecked(custom);
        presetProfile.setChecked(!custom);
        customProfileFields.setVisibility(!activeContainer && custom ? View.VISIBLE : View.GONE);
        presetButton.setVisibility(activeContainer || custom ? View.GONE : View.VISIBLE);
        regenerateButton.setVisibility(activeContainer ? View.GONE : View.VISIBLE);
        updatePreview();
    }

    private void selectNetworkMode(String mode) {
        markChanged();
        boolean proxy = AgramContainerManager.NETWORK_PROXY.equals(mode);
        directNetwork.setChecked(!proxy);
        proxyNetwork.setChecked(proxy);
        proxyFields.setVisibility(proxy ? View.VISIBLE : View.GONE);
        killSwitch.setEnabled(proxy);
        killSwitch.setAlpha(killSwitch.isEnabled() ? 1f : .5f);
        if (!proxy) killSwitch.setChecked(false);
        updatePreview();
    }

    private void selectPushMode(String mode) {
        markChanged();
        boolean embedded = AgramContainerManager.PUSH_AGRAM.equals(mode);
        agramPush.setChecked(embedded);
        directPush.setChecked(!embedded);
        updatePreview();
    }

    private int selectedProfileMode() {
        return customProfile != null && customProfile.isChecked()
                ? AgramContainerManager.PROFILE_CUSTOM : AgramContainerManager.PROFILE_PRESET;
    }

    private String selectedNetworkMode() {
        if (proxyNetwork != null && proxyNetwork.isChecked()) return AgramContainerManager.NETWORK_PROXY;
        return AgramContainerManager.NETWORK_DIRECT;
    }

    private String selectedPushMode() {
        return agramPush != null && agramPush.isChecked()
                ? AgramContainerManager.PUSH_AGRAM : AgramContainerManager.PUSH_DIRECT;
    }

    private String presetLabel() {
        AgramContainerManager.ProfilePreset preset = AgramContainerManager.getProfilePreset(pendingPresetIndex);
        return preset.title + "\n" + preset.deviceModel + " · " + preset.systemVersion;
    }

    private static String clientLanguage() {
        if (LocaleController.getInstance().getCurrentLocaleInfo() != null) {
            return normalizeLanguage(LocaleController.getInstance().getCurrentLocaleInfo().shortName);
        }
        return normalizeLanguage(Locale.getDefault().toLanguageTag());
    }

    private static String systemLanguage() { return normalizeLanguage(Locale.getDefault().toLanguageTag()); }

    private static String normalizeLanguage(String value) {
        return TextUtils.isEmpty(value) ? "en" : value.trim().toLowerCase(Locale.US).replace('_', '-');
    }

    private static int timezoneOffset() { return TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000; }

    private int selectedNotificationPrivacy() {
        if (fullNotifications.isChecked()) return AgramContainerManager.NOTIFICATION_FULL;
        if (authorNotifications.isChecked()) return AgramContainerManager.NOTIFICATION_AUTHOR;
        return AgramContainerManager.NOTIFICATION_HIDDEN;
    }

    private void selectNotificationPrivacy(int privacy) {
        markChanged();
        hiddenNotifications.setChecked(privacy == AgramContainerManager.NOTIFICATION_HIDDEN);
        authorNotifications.setChecked(privacy == AgramContainerManager.NOTIFICATION_AUTHOR);
        fullNotifications.setChecked(privacy == AgramContainerManager.NOTIFICATION_FULL);
    }

    private String detectedAppVersion() {
        try {
            PackageInfo info = getParentActivity().getPackageManager().getPackageInfo(getParentActivity().getPackageName(), 0);
            long code = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
            return info.versionName + " (" + code + ")";
        } catch (Exception ignore) {
            return BuildVars.BUILD_VERSION_STRING;
        }
    }

    private static String detectedDeviceModel() {
        String manufacturer = TextUtils.isEmpty(Build.MANUFACTURER) ? "Android" : Build.MANUFACTURER.trim();
        String model = TextUtils.isEmpty(Build.MODEL) ? "device" : Build.MODEL.trim();
        return model.toLowerCase(Locale.US).startsWith(manufacturer.toLowerCase(Locale.US)) ? model : manufacturer + " " + model;
    }

    private static String detectedSystemVersion() {
        String release = TextUtils.isEmpty(Build.VERSION.RELEASE) ? "unknown" : Build.VERSION.RELEASE;
        return "Android " + release;
    }

    private static int parsePort(String value) {
        try {
            int port = Integer.parseInt(value);
            return port > 0 && port <= 65535 ? port : 0;
        } catch (Exception ignore) { return 0; }
    }

    private void toast(String value) { Toast.makeText(getParentActivity(), value, Toast.LENGTH_LONG).show(); }

    private static String valueOr(String value, String fallback) { return TextUtils.isEmpty(value) ? fallback : value; }

    private static String profileText(EditText field, String fallback) {
        return field == null || TextUtils.isEmpty(field.getText().toString().trim()) ? fallback : field.getText().toString().trim();
    }

    private LinearLayout card(Context context) {
        LinearLayout view = new LinearLayout(context);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(14), AndroidUtilities.dp(16), AndroidUtilities.dp(16));
        themedBackground(view, Theme.key_dialogBackground, 16);
        return view;
    }

    private TextView sectionLabel(Context context, String value) {
        TextView view = text(context, value, 12, Theme.key_windowBackgroundWhiteBlueHeader, true);
        view.setLetterSpacing(.12f);
        view.setPadding(0, 0, 0, AndroidUtilities.dp(8));
        return view;
    }

    private TextView action(Context context, String value) {
        TextView view = text(context, value, 13, Theme.key_windowBackgroundWhiteBlueText, true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(AndroidUtilities.dp(10), 0, AndroidUtilities.dp(10), 0);
        themedBackground(view, Theme.key_dialogBackgroundGray, 10);
        return view;
    }

    private void addDetails(Context context, LinearLayout card, String description) {
        TextView body = text(context, description, 13, Theme.key_windowBackgroundWhiteGrayText, false);
        body.setLineSpacing(AndroidUtilities.dp(2), 1f);
        addExpandableDetails(context, card, "Подробнее", body);
    }

    private void addExpandableDetails(Context context, LinearLayout card, String label, TextView body) {
        TextView toggle = text(context, label, 13, Theme.key_windowBackgroundWhiteBlueText, true);
        toggle.setGravity(Gravity.CENTER_VERTICAL);
        toggle.setBackground(Theme.getSelectorDrawable(false));
        themeDescriptions.add(new ThemeDescription(toggle, ThemeDescription.FLAG_SELECTOR,
                null, null, null, null, Theme.key_listSelector));
        body.setVisibility(View.GONE);
        toggle.setOnClickListener(v -> {
            boolean expanded = body.getVisibility() == View.VISIBLE;
            body.setVisibility(expanded ? View.GONE : View.VISIBLE);
            toggle.setText(expanded ? label : "Скрыть подробности");
            // Reading help is not a settings change; Back must remain non-destructive.
        });
        card.addView(toggle, LayoutHelper.createLinear(-1, 48, 0, 4, 0, 0));
        card.addView(body, LayoutHelper.createLinear(-1, -2));
    }

    private EditText profileInput(Context context, String hint, String value) {
        EditText view = input(context, hint);
        view.setFilters(new InputFilter[]{new InputFilter.LengthFilter(128)});
        view.setText(value == null ? "" : value);
        view.setSelection(view.length());
        return view;
    }

    private EditText input(Context context, String hint) {
        EditTextBoldCursor view = new EditTextBoldCursor(context);
        view.setSingleLine(true);
        view.setTextSize(16);
        view.setHint(hint);
        view.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        view.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        view.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        themeDescriptions.add(new ThemeDescription(view, ThemeDescription.FLAG_TEXTCOLOR, null, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        themeDescriptions.add(new ThemeDescription(view, ThemeDescription.FLAG_HINTTEXTCOLOR, null, null, null, null, Theme.key_windowBackgroundWhiteHintText));
        themeDescriptions.add(new ThemeDescription(view, ThemeDescription.FLAG_CURSORCOLOR, null, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        view.setPadding(AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12), 0);
        themedBackground(view, Theme.key_dialogBackgroundGray, 10);
        return view;
    }

    private ChoiceCell radio(Context context, String title, String subtitle) {
        ChoiceCell view = new ChoiceCell(context);
        view.setTextAndValue(title, subtitle, false, false);
        view.setBackground(Theme.getSelectorDrawable(false));
        themeDescriptions.add(new ThemeDescription(view, ThemeDescription.FLAG_SELECTOR, null, null, null, null, Theme.key_listSelector));
        themeDescriptions.add(new ThemeDescription(view, 0, new Class[]{RadioButtonCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        themeDescriptions.add(new ThemeDescription(view, 0, new Class[]{RadioButtonCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText2));
        themeDescriptions.add(new ThemeDescription(view, ThemeDescription.FLAG_CHECKBOX, new Class[]{RadioButtonCell.class}, new String[]{"radioButton"}, null, null, null, Theme.key_radioBackground));
        themeDescriptions.add(new ThemeDescription(view, ThemeDescription.FLAG_CHECKBOXCHECK, new Class[]{RadioButtonCell.class}, new String[]{"radioButton"}, null, null, null, Theme.key_radioBackgroundChecked));
        return view;
    }

    private ToggleCell settingSwitch(Context context, String title, boolean checked) {
        String shortTitle = title;
        if (title.startsWith("Kill switch")) shortTitle = "Блокировка сети";
        else if (title.contains("отметку о прочтении")) shortTitle = "Скрывать прочтение";
        else if (title.contains("просмотр историй")) shortTitle = "Скрывать истории";
        else if (title.contains("typing")) shortTitle = "Скрывать набор и запись";
        else if (title.contains("взаимодействии")) shortTitle = "Прочитать при действии";
        else if (title.contains("Предупреждать")) shortTitle = "Предупреждения";
        else if (title.contains("Биометрия")) shortTitle = "Биометрия";
        return settingSwitch(context, shortTitle, shortTitle.equals(title) ? "" : title, checked);
    }

    private ToggleCell settingSwitch(Context context, String title, String subtitle, boolean checked) {
        ToggleCell view = new ToggleCell(context);
        view.setTextAndValueAndCheck(title, subtitle, checked, true, false);
        view.setOnClickListener(v -> {
            if (!view.isEnabled()) return;
            view.setChecked(!view.isChecked());
            markChanged();
            if (view.listener != null) view.listener.onChanged(view, view.isChecked());
        });
        view.setBackground(Theme.getSelectorDrawable(false));
        themeDescriptions.add(new ThemeDescription(view, ThemeDescription.FLAG_SELECTOR, null, null, null, null, Theme.key_listSelector));
        themeDescriptions.add(new ThemeDescription(view, 0, new Class[]{TextCheckCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        themeDescriptions.add(new ThemeDescription(view, 0, new Class[]{TextCheckCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText2));
        for (int key : new int[]{Theme.key_switchTrack, Theme.key_switchTrackChecked, Theme.key_windowBackgroundWhite}) {
            themeDescriptions.add(new ThemeDescription(view, 0, new Class[]{TextCheckCell.class}, new String[]{"checkBox"}, null, null, null, key));
        }
        return view;
    }

    private static final class ChoiceCell extends RadioButtonCell {
        private boolean checked;
        ChoiceCell(Context context) { super(context); }
        void setChecked(boolean value) { checked = value; super.setChecked(value, true); }
        boolean isChecked() { return checked; }
    }

    private static final class ToggleCell extends TextCheckCell {
        interface Listener { void onChanged(ToggleCell view, boolean checked); }
        Listener listener;
        ToggleCell(Context context) { super(context, 0); }
        void setOnCheckedChangeListener(Listener value) { listener = value; }
    }

    private TextView text(Context context, String value, int size, int colorKey, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(Theme.getColor(colorKey));
        themeDescriptions.add(new ThemeDescription(view, ThemeDescription.FLAG_TEXTCOLOR, null, null, null, null, colorKey));
        if (bold) view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return view;
    }

    private void themedBackground(View view, int colorKey, int radiusDp) {
        // ShapeDrawable lets ThemeDescription update the paint without losing rounded corners.
        view.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(radiusDp), Theme.getColor(colorKey)));
        themeDescriptions.add(new ThemeDescription(view, ThemeDescription.FLAG_BACKGROUNDFILTER, null, null, null, null, colorKey));
    }

    private void updatePageBackground() {
        if (fragmentView == null) return;
        // Night's windowBackgroundGray is pure black: use the normal list surface in dark themes.
        fragmentView.setBackgroundColor(Theme.getColor(Theme.isCurrentThemeDark()
                ? Theme.key_windowBackgroundWhite : Theme.key_windowBackgroundGray));
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> descriptions = new ArrayList<>(themeDescriptions);
        final int pageStartColor = fragmentView != null && fragmentView.getBackground() instanceof ColorDrawable
                ? ((ColorDrawable) fragmentView.getBackground()).getColor()
                : Theme.getColor(Theme.key_windowBackgroundWhite);
        ThemeDescription.ThemeDescriptionDelegate surfaces = new ThemeDescription.ThemeDescriptionDelegate() {
            @Override
            public void didSetColor() {
                updatePageBackground();
            }

            @Override
            public void onAnimationProgress(float progress) {
                if (fragmentView == null) return;
                // Blend the actual page endpoints, not Night's black separator color when switching to Day.
                int target = Theme.getNonAnimatedColor(Theme.isCurrentThemeDark()
                        ? Theme.key_windowBackgroundWhite : Theme.key_windowBackgroundGray);
                fragmentView.setBackgroundColor(ColorUtils.blendARGB(pageStartColor, target, progress));
            }
        };
        descriptions.add(new ThemeDescription(null, 0, null, null, null, surfaces, Theme.key_windowBackgroundWhite));
        descriptions.add(new ThemeDescription(null, 0, null, null, null, surfaces, Theme.key_windowBackgroundGray));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_actionBarDefault));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_actionBarDefaultIcon));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultTitle));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR, null, null, null, null, Theme.key_actionBarDefaultSelector));
        return descriptions;
    }
}
