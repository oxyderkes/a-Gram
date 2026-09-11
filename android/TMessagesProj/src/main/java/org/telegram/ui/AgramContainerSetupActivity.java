/*
 * This file is part of Agram and is licensed under GNU GPL v2 or later.
 */
package org.telegram.ui;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
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
import android.widget.RadioButton;
import android.widget.Space;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AgramContainerManager;
import org.telegram.messenger.AgramNetworkController;
import org.telegram.messenger.AgramPushController;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

/** One account is always created in one automatically assigned container. */
public class AgramContainerSetupActivity extends BaseFragment {

    private final int account;
    private AgramContainerManager.ContainerRecord record;
    private boolean activeContainer;

    private RadioButton presetProfile;
    private RadioButton customProfile;
    private TextView presetButton;
    private TextView regenerateButton;
    private LinearLayout customProfileFields;
    private EditText deviceModelField;
    private EditText systemVersionField;
    private TextView preview;
    private int pendingPresetIndex;
    private String pendingProfileId;

    private RadioButton directNetwork;
    private RadioButton proxyNetwork;
    private LinearLayout proxyFields;
    private EditText proxyAddressField;
    private EditText proxyPortField;
    private EditText proxyUsernameField;
    private EditText proxyPasswordField;
    private EditText proxySecretField;
    private Switch killSwitch;

    private RadioButton agramPush;
    private RadioButton directPush;

    private EditText pinField;
    private Switch biometricSwitch;
    private Switch ghostReadSwitch;
    private Switch ghostStoriesSwitch;
    private Switch ghostTypingSwitch;
    private Switch ghostOnlineSwitch;
    private Switch ghostReadOnInteractionSwitch;
    private Switch ghostWarnSwitch;
    private RadioButton hiddenNotifications;
    private RadioButton authorNotifications;
    private RadioButton fullNotifications;
    public AgramContainerSetupActivity() {
        this(UserConfig.getLoginTargetAccount());
    }

    public AgramContainerSetupActivity(int account) {
        this.account = account;
        currentAccount = account;
    }

    @Override
    public boolean onFragmentCreate() {
        activeContainer = UserConfig.getInstance(account).isClientActivated();
        record = AgramContainerManager.getInstance().ensureFreshContainerForSessionState(account);
        if (activeContainer && account != UserConfig.selectedAccount) {
            return false;
        }
        pendingPresetIndex = record.presetIndex;
        pendingProfileId = TextUtils.isEmpty(record.profileId) ? UUID.randomUUID().toString() : record.profileId;
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(activeContainer ? "Контейнер аккаунта" : "Новый аккаунт");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = root;
        android.widget.ScrollView scroll = new android.widget.ScrollView(context);
        scroll.setFillViewport(true);
        root.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(20), AndroidUtilities.dp(20), AndroidUtilities.dp(32));
        scroll.addView(content, new android.widget.ScrollView.LayoutParams(-1, -2));

        content.addView(text(context, "Один аккаунт — один контейнер", 26,
                Theme.key_windowBackgroundWhiteBlackText, true));
        TextView subtitle = text(context,
                activeContainer
                        ? "Вы находитесь внутри нужного контейнера. Изменения сети, push и локальной защиты применятся только к этому аккаунту."
                        : "Контейнер назначен автоматически. Выберите профиль устройства, проверьте данные и затем переходите к входу.",
                15, Theme.key_windowBackgroundWhiteGrayText, false);
        subtitle.setLineSpacing(AndroidUtilities.dp(2), 1f);
        content.addView(subtitle, LayoutHelper.createLinear(-1, -2, 0, 8, 0, 18));

        LinearLayout identityCard = card(context);
        content.addView(identityCard, LayoutHelper.createLinear(-1, -2, 0, 0, 0, 12));
        identityCard.addView(sectionLabel(context, "АВТОМАТИЧЕСКИЙ КОНТЕЙНЕР"));
        identityCard.addView(text(context,
                "Аккаунт " + (account + 1) + " · локальная изоляция включена\nВыбор контейнера не требуется и недоступен.",
                15, Theme.key_windowBackgroundWhiteBlackText, true));

        addProfileCard(context, content);
        addNetworkCard(context, content);
        addPushCard(context, content);
        addGhostCard(context, content);
        addSecurityCard(context, content);

        Space space = new Space(context);
        content.addView(space, LayoutHelper.createLinear(1, 8));
        TextView continueButton = text(context,
                activeContainer ? "СОХРАНИТЬ НАСТРОЙКИ" : "ПРОДОЛЖИТЬ К ВХОДУ",
                14, Theme.key_featuredStickers_buttonText, true);
        continueButton.setGravity(Gravity.CENTER);
        continueButton.setBackground(rounded(Theme.getColor(Theme.key_featuredStickers_addButton), 12));
        continueButton.setOnClickListener(v -> saveAndContinue());
        content.addView(continueButton, LayoutHelper.createLinear(-1, 52));

        applyLockedProfileState();
        selectProfileMode(record.profileMode == AgramContainerManager.PROFILE_CUSTOM
                ? AgramContainerManager.PROFILE_CUSTOM : AgramContainerManager.PROFILE_PRESET);
        selectNetworkMode(record.proxyMode);
        selectPushMode(record.pushMode);
        updatePreview();
        return fragmentView;
    }

    private void addProfileCard(Context context, LinearLayout content) {
        LinearLayout card = card(context);
        content.addView(card, LayoutHelper.createLinear(-1, -2, 0, 0, 0, 12));
        card.addView(sectionLabel(context, "ПРОФИЛЬ УСТРОЙСТВА"));
        presetProfile = radio(context, "Один из 10 пресетов",
                "Стандартная согласованная пара модели и версии Android.");
        customProfile = radio(context, "Вручную",
                "Свои значения модели устройства и версии Android.");
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

        TextView note = text(context,
                "Версия Agram и api_id всегда передаются настоящими. official_app не подделывается. После входа профиль фиксируется до завершения Telegram-сессии.",
                12, Theme.key_windowBackgroundWhiteGrayText, false);
        note.setLineSpacing(AndroidUtilities.dp(2), 1f);
        card.addView(note, LayoutHelper.createLinear(-1, -2, 0, 10, 0, 0));

        preview = text(context, "", 13, Theme.key_windowBackgroundWhiteGrayText, false);
        preview.setTypeface(android.graphics.Typeface.MONOSPACE);
        preview.setTextIsSelectable(true);
        preview.setBackground(rounded(Theme.getColor(Theme.key_windowBackgroundGray), 12));
        preview.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(12), AndroidUtilities.dp(14), AndroidUtilities.dp(12));
        card.addView(preview, LayoutHelper.createLinear(-1, -2, 0, 10, 0, 0));
    }

    private void addNetworkCard(Context context, LinearLayout content) {
        LinearLayout card = card(context);
        content.addView(card, LayoutHelper.createLinear(-1, -2, 0, 0, 0, 12));
        card.addView(sectionLabel(context, "СЕТЬ ЭТОГО КОНТЕЙНЕРА"));
        directNetwork = radio(context, "Прямое соединение", "Без локального прокси.");
        proxyNetwork = radio(context, "Свой прокси", "SOCKS5 или MTProto, только для этого аккаунта.");
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
        TextView note = text(context,
                "При ошибке пользовательского прокси kill switch ставит MTProto-сеть только этого контейнера на паузу. Локальная история остаётся доступной.",
                12, Theme.key_windowBackgroundWhiteGrayText, false);
        note.setLineSpacing(AndroidUtilities.dp(2), 1f);
        card.addView(note, LayoutHelper.createLinear(-1, -2, 0, 8, 0, 0));
    }

    private void addPushCard(Context context, LinearLayout content) {
        LinearLayout card = card(context);
        content.addView(card, LayoutHelper.createLinear(-1, -2, 0, 0, 0, 12));
        card.addView(sectionLabel(context, "PUSH ЭТОГО КОНТЕЙНЕРА"));
        agramPush = radio(context, "Agram Push", "Встроенная доставка с отдельным endpoint этого контейнера.");
        directPush = radio(context, "Прямое MTProto-соединение", "Внутренний fallback без push-endpoint.");
        card.addView(agramPush);
        card.addView(directPush);
        agramPush.setOnClickListener(v -> selectPushMode(AgramContainerManager.PUSH_AGRAM));
        directPush.setOnClickListener(v -> selectPushMode(AgramContainerManager.PUSH_DIRECT));
        TextView note = text(context,
                "Внешнее приложение не требуется. Endpoint хранится в зашифрованной записи контейнера и регистрируется в Telegram как Simple Push type 4 без объединения other_uids. Через SOCKS5 push следует маршруту контейнера. MTProto-прокси не переносит HTTPS push, поэтому Agram Push остаётся недоступен, а не обходит его напрямую.",
                12, Theme.key_windowBackgroundWhiteGrayText, false);
        note.setLineSpacing(AndroidUtilities.dp(2), 1f);
        card.addView(note, LayoutHelper.createLinear(-1, -2, 0, 8, 0, 0));
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
        pinField = input(context, activeContainer ? "Новый PIN — пусто, чтобы не менять" : "PIN — минимум 6 символов (необязательно)");
        pinField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        card.addView(pinField, LayoutHelper.createLinear(-1, 52));
        biometricSwitch = settingSwitch(context, "Биометрия после настройки PIN", record.biometricEnabled);
        card.addView(biometricSwitch);
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
        TextView note = text(context,
                "Auth keys, Telegram-сессия, БД, настройки, поиск, черновики, proxy, push и локальные ключи разделены штатным account namespace и ключом контейнера в Android Keystore.",
                12, Theme.key_windowBackgroundWhiteGrayText, false);
        note.setLineSpacing(AndroidUtilities.dp(2), 1f);
        card.addView(note, LayoutHelper.createLinear(-1, -2, 0, 8, 0, 0));
    }

    private void saveAndContinue() {
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

        if (!record.profileLocked) {
            AgramContainerManager.getInstance().updatePreLoginProfile(
                    account, mode, pendingPresetIndex,
                    deviceModelField.getText().toString(), systemVersionField.getText().toString(),
                    systemLanguage(), clientLanguage(), false, timezoneOffset(), pendingProfileId,
                    pin, biometricSwitch.isChecked() && !TextUtils.isEmpty(pin), selectedNotificationPrivacy());
        } else {
            AgramContainerManager.getInstance().updateContainerSecurity(
                    account, record.name, pin, biometricSwitch.isChecked(), selectedNotificationPrivacy());
        }
        saveGhostMode();
        AgramContainerManager.getInstance().saveNetworkSettings(
                account, networkMode, killSwitch.isChecked(),
                proxyAddressField.getText().toString(), proxyPort,
                proxyUsernameField.getText().toString(), proxyPasswordField.getText().toString(),
                proxySecretField.getText().toString());

        if (AgramContainerManager.PUSH_DIRECT.equals(pushMode)
                && AgramContainerManager.PUSH_AGRAM.equals(record.pushMode)) {
            AgramPushController.getInstance().unregisterAccount(account, activeContainer);
        }
        AgramContainerManager.getInstance().savePushSettings(account, pushMode);
        AgramPushController.getInstance().onPushSettingsChanged(account);
        AgramNetworkController.getInstance().apply(account);

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
        } else {
            UserConfig.setSelectedAccountPersisted(account);
            presentFragment(new LoginActivity(), true);
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
                    pendingPresetIndex = which;
                    pendingProfileId = UUID.randomUUID().toString();
                    presetButton.setText(presetLabel());
                    updatePreview();
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
    }

    private void regenerateProfile() {
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
        String endpointStatus = TextUtils.isEmpty(record.agramPushEndpoint)
                ? "создастся автоматически" : valueOr(record.agramPushStatus, "готов");
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
                        "\n\nЛОКАЛЬНО / ТРАНСПОРТ\n" +
                        "profile_id: " + pendingProfileId +
                        "\nnetwork: " + selectedNetworkMode() + (killSwitch != null && killSwitch.isChecked() ? " · fail-closed" : "") +
                        "\npush: " + selectedPushMode() +
                        "\npush_instance: " + record.agramPushInstance +
                        (AgramContainerManager.PUSH_AGRAM.equals(selectedPushMode())
                                ? "\nendpoint: отдельный · " + endpointStatus : ""));
    }

    private void applyLockedProfileState() {
        if (!activeContainer) return;
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
        customProfileFields.setVisibility(custom ? View.VISIBLE : View.GONE);
        presetButton.setVisibility(custom ? View.GONE : View.VISIBLE);
        regenerateButton.setVisibility(activeContainer ? View.GONE : View.VISIBLE);
        updatePreview();
    }

    private void selectNetworkMode(String mode) {
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

    private void saveGhostMode() {
        AgramContainerManager.getInstance().updateGhostMode(
                account, AgramContainerManager.getInstance().isGhostModeEnabled(account),
                ghostReadSwitch.isChecked(), ghostStoriesSwitch.isChecked(),
                ghostTypingSwitch.isChecked(), ghostOnlineSwitch.isChecked(),
                ghostReadOnInteractionSwitch.isChecked(), ghostWarnSwitch.isChecked());
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

    private static LinearLayout card(Context context) {
        LinearLayout view = new LinearLayout(context);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(14), AndroidUtilities.dp(16), AndroidUtilities.dp(16));
        view.setBackground(rounded(Theme.getColor(Theme.key_windowBackgroundWhite), 16));
        return view;
    }

    private static TextView sectionLabel(Context context, String value) {
        TextView view = text(context, value, 12, Theme.key_windowBackgroundWhiteBlueHeader, true);
        view.setLetterSpacing(.12f);
        view.setPadding(0, 0, 0, AndroidUtilities.dp(8));
        return view;
    }

    private static TextView action(Context context, String value) {
        TextView view = text(context, value, 13, Theme.key_windowBackgroundWhiteBlueText, true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(AndroidUtilities.dp(10), 0, AndroidUtilities.dp(10), 0);
        view.setBackground(rounded(Theme.getColor(Theme.key_windowBackgroundGray), 10));
        return view;
    }

    private static EditText profileInput(Context context, String hint, String value) {
        EditText view = input(context, hint);
        view.setFilters(new InputFilter[]{new InputFilter.LengthFilter(128)});
        view.setText(value == null ? "" : value);
        view.setSelection(view.length());
        return view;
    }

    private static EditText input(Context context, String hint) {
        EditText view = new EditText(context);
        view.setSingleLine(true);
        view.setTextSize(16);
        view.setHint(hint);
        view.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        view.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        view.setPadding(AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12), 0);
        view.setBackground(rounded(Theme.getColor(Theme.key_windowBackgroundGray), 10));
        return view;
    }

    private static RadioButton radio(Context context, String title, String subtitle) {
        RadioButton view = new RadioButton(context);
        view.setText(title + "\n" + subtitle);
        view.setTextSize(15);
        view.setLineSpacing(AndroidUtilities.dp(2), 1f);
        view.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        view.setButtonTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{Theme.getColor(Theme.key_featuredStickers_addButton), Theme.getColor(Theme.key_windowBackgroundWhiteGrayText)}));
        view.setPadding(0, AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4));
        return view;
    }

    private static Switch settingSwitch(Context context, String title, boolean checked) {
        Switch view = new Switch(context);
        view.setText(title);
        view.setTextSize(15);
        view.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        view.setChecked(checked);
        view.setPadding(0, AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4));
        return view;
    }

    private static TextView text(Context context, String value, int size, int colorKey, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(Theme.getColor(colorKey));
        if (bold) view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return view;
    }

    private static GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(AndroidUtilities.dp(radiusDp));
        return drawable;
    }
}
