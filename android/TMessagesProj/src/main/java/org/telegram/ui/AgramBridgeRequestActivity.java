/*
 * This file is part of Agram and is licensed under GNU GPL v2 or later.
 */
package org.telegram.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AgramBridgeRequestClient;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.RadioButtonCell;
import org.telegram.ui.Components.LayoutHelper;

/** UI-only flow for obtaining Tor bridges from an injected service client. */
public class AgramBridgeRequestActivity extends BaseFragment {
    private static final int MAX_CAPTCHA_BYTES = 8 * 1024 * 1024;
    private static final int MAX_CAPTCHA_DIMENSION = 4_096;
    private static final long MAX_CAPTCHA_PIXELS = 8_000_000L;

    public interface Delegate {
        /** Called only after the user confirms; enabled=false means Tor without bridges. */
        void onBridgeConfigurationApproved(boolean enabled, String bridgeLines);
    }

    private enum State {
        UNAVAILABLE,
        IDLE,
        LOADING,
        AWAITING_CAPTCHA,
        SUBMITTING_CAPTCHA,
        SUCCESS,
        ERROR,
        APPLIED
    }

    private final AgramBridgeRequestClient client;
    private final Delegate delegate;

    private State state;
    private String transport = AgramBridgeRequestClient.TRANSPORT_AUTO;
    private String challengeId;
    private AgramBridgeRequestClient.BridgeRequest challengeRequest;
    private String resultBridgeLines;
    private String resultTransport;
    private boolean resultReady;
    private String errorMessage;
    private Bitmap captchaBitmap;
    private AgramBridgeRequestClient.RequestHandle activeRequest;
    private int requestGeneration;

    private RadioButtonCell autoCell;
    private RadioButtonCell obfs4Cell;
    private RadioButtonCell webTunnelCell;
    private RadioButtonCell snowflakeCell;
    private Switch manualCountrySwitch;
    private EditText countryInput;
    private LinearLayout captchaContainer;
    private ImageView captchaImage;
    private EditText captchaInput;
    private ProgressBar progressView;
    private TextView statusView;
    private TextView requestAction;
    private TextView submitAction;
    private LinearLayout resultContainer;
    private TextView resultSummary;
    private TextView resultPreview;
    private TextView resultExplanation;
    private TextView applyAction;

    public AgramBridgeRequestActivity() {
        this(null, null);
    }

    public AgramBridgeRequestActivity(AgramBridgeRequestClient client, Delegate delegate) {
        this.client = client;
        this.delegate = delegate;
        state = client == null ? State.UNAVAILABLE : State.IDLE;
    }

    @Override
    public void onFragmentDestroy() {
        cancelActiveRequest();
        if (captchaImage != null) {
            captchaImage.setImageDrawable(null);
        }
        if (captchaBitmap != null) {
            captchaBitmap.recycle();
            captchaBitmap = null;
        }
        super.onFragmentDestroy();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Connection Assist");
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

        content.addView(title(context, "Подобрать подключение"));
        content.addView(body(context,
                "Connection Assist обращается к официальному сервису Tor Project. Запрос выполняется до запуска Tor, "
                        + "поэтому сервис видит текущий внешний IP или IP активного VPN; Telegram этот запрос не получает. "
                        + "Если сервис недоступен, авто-режим использует встроенный Snowflake. CAPTCHA появится только для "
                        + "официального запасного запроса obfs4."),
                LayoutHelper.createLinear(-1, -2, 0, 6, 0, 14));

        LinearLayout transportCard = card(context);
        content.addView(transportCard, LayoutHelper.createLinear(-1, -2, 0, 0, 0, 12));
        transportCard.addView(label(context, "ТРАНСПОРТ"));

        autoCell = transportCell(context, "Авто",
                "Запросить доступные варианты и использовать порядок, рекомендованный сервисом.", true);
        autoCell.setOnClickListener(v -> selectTransport(AgramBridgeRequestClient.TRANSPORT_AUTO));
        transportCard.addView(autoCell, LayoutHelper.createLinear(-1, -2));

        obfs4Cell = transportCell(context, "obfs4",
                "Маскируемый транспорт Tor с отдельным transport-плагином.", true);
        obfs4Cell.setOnClickListener(v -> selectTransport(AgramBridgeRequestClient.TRANSPORT_OBFS4));
        transportCard.addView(obfs4Cell, LayoutHelper.createLinear(-1, -2));

        webTunnelCell = transportCell(context, "WebTunnel",
                "Транспорт, маскирующий соединение под обычный HTTPS-трафик.", true);
        webTunnelCell.setOnClickListener(v -> selectTransport(AgramBridgeRequestClient.TRANSPORT_WEBTUNNEL));
        transportCard.addView(webTunnelCell, LayoutHelper.createLinear(-1, -2));

        snowflakeCell = transportCell(context, "Snowflake",
                "Одноразовые прокси добровольцев; доступность зависит от текущей сети.", false);
        snowflakeCell.setOnClickListener(v -> selectTransport(AgramBridgeRequestClient.TRANSPORT_SNOWFLAKE));
        transportCard.addView(snowflakeCell, LayoutHelper.createLinear(-1, -2));

        manualCountrySwitch = new Switch(context);
        manualCountrySwitch.setText("Указать страну вручную");
        manualCountrySwitch.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        manualCountrySwitch.setTextSize(15);
        manualCountrySwitch.setPadding(0, AndroidUtilities.dp(2), 0, AndroidUtilities.dp(2));
        manualCountrySwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            countryInput.setVisibility(checked ? View.VISIBLE : View.GONE);
            if (!checked) {
                countryInput.setText("");
            } else {
                countryInput.requestFocus();
            }
            invalidateResponse();
        });
        transportCard.addView(manualCountrySwitch, LayoutHelper.createLinear(-1, 48, 0, 8, 0, 0));

        countryInput = input(context, "Код страны, например BY");
        countryInput.setSingleLine(true);
        countryInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        countryInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        countryInput.setFilters(new InputFilter[]{
                new InputFilter.AllCaps(), new InputFilter.LengthFilter(2)});
        countryInput.setVisibility(View.GONE);
        countryInput.setContentDescription("Двухбуквенный код страны ISO");
        countryInput.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable editable) {
                if (challengeId != null || resultBridgeLines != null) {
                    invalidateResponse();
                }
            }
        });
        transportCard.addView(countryInput, LayoutHelper.createLinear(-1, 48, 0, 4, 0, 0));
        transportCard.addView(body(context,
                "Оставьте выключенным для автоматического определения по IP. При ручном выборе введите двухбуквенный ISO-код страны."),
                LayoutHelper.createLinear(-1, -2, 0, 8, 0, 0));

        LinearLayout requestCard = card(context);
        content.addView(requestCard, LayoutHelper.createLinear(-1, -2, 0, 0, 0, 12));
        requestCard.addView(label(context, "ПОДБОР МОСТОВ"));
        statusView = body(context, "");
        statusView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        statusView.setFocusable(true);
        requestCard.addView(statusView, LayoutHelper.createLinear(-1, -2, 0, 6, 0, 10));

        progressView = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        progressView.setIndeterminate(true);
        requestCard.addView(progressView, LayoutHelper.createLinear(-1, 4, 0, 0, 0, 10));

        requestAction = action(context, "ПОДОБРАТЬ АВТОМАТИЧЕСКИ");
        requestAction.setContentDescription("Подобрать настройки мостов автоматически");
        requestAction.setOnClickListener(v -> requestRecommendation());
        requestCard.addView(requestAction, LayoutHelper.createLinear(-1, 46));

        captchaContainer = new LinearLayout(context);
        captchaContainer.setOrientation(LinearLayout.VERTICAL);
        requestCard.addView(captchaContainer, LayoutHelper.createLinear(-1, -2, 0, 14, 0, 0));
        captchaContainer.addView(label(context, "CAPTCHA ЗАПРОШЕНА СЕРВИСОМ"));

        FrameLayout captchaFrame = new FrameLayout(context);
        captchaFrame.setBackground(rounded(Theme.getColor(Theme.key_windowBackgroundGray), 10));
        captchaFrame.setContentDescription("Область изображения CAPTCHA");
        captchaContainer.addView(captchaFrame, LayoutHelper.createLinear(-1, 180, 0, 8, 0, 0));
        captchaImage = new ImageView(context);
        captchaImage.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        captchaImage.setAdjustViewBounds(true);
        captchaImage.setContentDescription("CAPTCHA. Введите символы с изображения ниже");
        captchaFrame.addView(captchaImage, LayoutHelper.createFrame(-1, -1, Gravity.CENTER, 10, 10, 10, 10));

        captchaInput = input(context, "Символы с изображения");
        captchaInput.setSingleLine(true);
        captchaInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        captchaInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        captchaInput.setContentDescription("Ответ CAPTCHA");
        captchaInput.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable editable) {
                updateUi();
            }
        });
        captchaInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE && submitAction.isEnabled()) {
                submitCaptcha();
                return true;
            }
            return false;
        });
        captchaContainer.addView(captchaInput, LayoutHelper.createLinear(-1, 48, 0, 10, 0, 0));

        submitAction = action(context, "ОТПРАВИТЬ CAPTCHA");
        submitAction.setOnClickListener(v -> submitCaptcha());
        captchaContainer.addView(submitAction, LayoutHelper.createLinear(-1, 46, 0, 8, 0, 0));
        captchaContainer.addView(body(context,
                "Если изображение не читается, снова нажмите «Подобрать автоматически», чтобы обновить запрос."),
                LayoutHelper.createLinear(-1, -2, 0, 8, 0, 0));

        resultContainer = card(context);
        content.addView(resultContainer, LayoutHelper.createLinear(-1, -2));
        resultContainer.addView(label(context, "ПОДТВЕРЖДЕНИЕ"));
        resultSummary = body(context, "");
        resultContainer.addView(resultSummary, LayoutHelper.createLinear(-1, -2, 0, 6, 0, 8));
        resultPreview = mono(context);
        resultContainer.addView(resultPreview, LayoutHelper.createLinear(-1, -2));
        resultExplanation = body(context, "");
        resultContainer.addView(resultExplanation, LayoutHelper.createLinear(-1, -2, 0, 8, 0, 0));
        applyAction = action(context, "ПРИМЕНИТЬ И ПЕРЕЗАПУСТИТЬ TOR");
        applyAction.setOnClickListener(v -> applyBridges());
        resultContainer.addView(applyAction, LayoutHelper.createLinear(-1, 46, 0, 10, 0, 0));

        updateUi();
        return fragmentView;
    }

    private static RadioButtonCell transportCell(Context context, String title,
                                                  String description, boolean divider) {
        RadioButtonCell cell = new RadioButtonCell(context);
        cell.setBackgroundDrawable(Theme.getSelectorDrawable(false));
        cell.setTextAndValue(title, description, divider, false);
        return cell;
    }

    private void selectTransport(String value) {
        if (!TextUtils.equals(transport, value)) {
            transport = value;
            invalidateResponse();
        }
    }

    private void invalidateResponse() {
        cancelActiveRequest();
        challengeId = null;
        challengeRequest = null;
        resultBridgeLines = null;
        resultTransport = null;
        resultReady = false;
        errorMessage = null;
        if (captchaInput != null) {
            captchaInput.setText("");
        }
        replaceCaptchaBitmap(null);
        state = client == null ? State.UNAVAILABLE : State.IDLE;
        updateUi();
    }

    private AgramBridgeRequestClient.BridgeRequest requestFromSelection() {
        String countryCode = "";
        if (manualCountrySwitch.isChecked()) {
            countryCode = countryInput.getText().toString().trim().toUpperCase();
            if (!countryCode.matches("[A-Z]{2}")) {
                countryInput.requestFocus();
                setError("Введите двухбуквенный ISO-код страны.");
                return null;
            }
        }
        String[] transports;
        if (AgramBridgeRequestClient.TRANSPORT_AUTO.equals(transport)) {
            transports = new String[]{
                    AgramBridgeRequestClient.TRANSPORT_OBFS4,
                    AgramBridgeRequestClient.TRANSPORT_WEBTUNNEL,
                    AgramBridgeRequestClient.TRANSPORT_SNOWFLAKE
            };
        } else {
            transports = new String[]{transport};
        }
        return new AgramBridgeRequestClient.BridgeRequest(countryCode, transports);
    }

    private void requestRecommendation() {
        if (client == null) {
            setError("Сервис получения мостов не подключён к этой сборке.");
            return;
        }
        AgramBridgeRequestClient.BridgeRequest request = requestFromSelection();
        if (request == null) {
            return;
        }
        cancelActiveRequest();
        challengeId = null;
        challengeRequest = null;
        resultBridgeLines = null;
        resultTransport = null;
        resultReady = false;
        errorMessage = null;
        captchaInput.setText("");
        replaceCaptchaBitmap(null);
        final int generation = requestGeneration;
        state = State.LOADING;
        updateUi();
        try {
            AgramBridgeRequestClient.RequestHandle handle = client.requestBridges(
                    request, new ResultCallback(generation, request));
            keepHandleIfCurrent(generation, State.LOADING, handle);
        } catch (RuntimeException error) {
            if (generation == requestGeneration) {
                setError(safeErrorMessage(error));
            }
        }
    }

    private void submitCaptcha() {
        if (client == null || TextUtils.isEmpty(challengeId) || challengeRequest == null) {
            setError("Сначала выполните автоматический подбор.");
            return;
        }
        String answer = captchaInput.getText().toString().trim();
        if (TextUtils.isEmpty(answer)) {
            captchaInput.requestFocus();
            setError("Введите символы с изображения.");
            return;
        }
        AndroidUtilities.hideKeyboard(captchaInput);
        cancelActiveRequest();
        errorMessage = null;
        final int generation = requestGeneration;
        final AgramBridgeRequestClient.BridgeRequest request = challengeRequest;
        final String requestedChallenge = challengeId;
        state = State.SUBMITTING_CAPTCHA;
        updateUi();
        try {
            AgramBridgeRequestClient.RequestHandle handle = client.submitChallenge(
                    request, requestedChallenge, answer, new ResultCallback(generation, request));
            keepHandleIfCurrent(generation, State.SUBMITTING_CAPTCHA, handle);
        } catch (RuntimeException error) {
            if (generation == requestGeneration) {
                setError(safeErrorMessage(error));
            }
        }
    }

    private final class ResultCallback
            implements AgramBridgeRequestClient.Callback<AgramBridgeRequestClient.BridgeRequestResult> {
        private final int generation;
        private final AgramBridgeRequestClient.BridgeRequest request;

        private ResultCallback(int generation, AgramBridgeRequestClient.BridgeRequest request) {
            this.generation = generation;
            this.request = request;
        }

        @Override
        public void onSuccess(AgramBridgeRequestClient.BridgeRequestResult value) {
            AndroidUtilities.runOnUIThread(() -> onResult(generation, request, value));
        }

        @Override
        public void onError(AgramBridgeRequestClient.RequestError error) {
            AndroidUtilities.runOnUIThread(() -> onRequestError(generation, error));
        }
    }

    private void onResult(int generation, AgramBridgeRequestClient.BridgeRequest request,
                          AgramBridgeRequestClient.BridgeRequestResult result) {
        if (!isCurrentRequest(generation)) {
            return;
        }
        activeRequest = null;
        if (result != null && result.bridges != null) {
            String lines = result.bridges.bridgeLines.trim();
            boolean direct = AgramBridgeRequestClient.TRANSPORT_DIRECT.equals(result.bridges.transport);
            if (!TextUtils.isEmpty(lines) || direct) {
                resultBridgeLines = lines;
                resultTransport = result.bridges.transport;
                resultReady = true;
                challengeId = null;
                challengeRequest = null;
                replaceCaptchaBitmap(null);
                errorMessage = null;
                state = State.SUCCESS;
                updateUi();
                statusView.announceForAccessibility(
                        direct
                                ? "Рекомендован Tor без мостов. Проверьте и подтвердите применение."
                                : "Мосты получены. Проверьте результат и подтвердите применение.");
                return;
            }
        }
        AgramBridgeRequestClient.CaptchaChallenge challenge = result == null ? null : result.challenge;
        if (challenge == null || TextUtils.isEmpty(challenge.challengeId)
                || challenge.imageBytes == null || challenge.imageBytes.length == 0) {
            setError("Сервис не вернул ни настройки мостов, ни CAPTCHA.");
            return;
        }
        if (challenge.imageBytes.length > MAX_CAPTCHA_BYTES) {
            setError("Изображение CAPTCHA слишком большое.");
            return;
        }
        Bitmap bitmap;
        try {
            bitmap = decodeCaptcha(challenge.imageBytes);
        } catch (IllegalArgumentException | OutOfMemoryError error) {
            setError("Не удалось прочитать изображение CAPTCHA.");
            return;
        }
        challengeId = challenge.challengeId;
        challengeRequest = request;
        replaceCaptchaBitmap(bitmap);
        captchaInput.setText("");
        errorMessage = null;
        state = State.AWAITING_CAPTCHA;
        updateUi();
        captchaInput.requestFocus();
        statusView.announceForAccessibility("Сервис запросил CAPTCHA.");
    }

    private static Bitmap decodeCaptcha(byte[] encoded) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(encoded, 0, encoded.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0
                || bounds.outWidth > MAX_CAPTCHA_DIMENSION
                || bounds.outHeight > MAX_CAPTCHA_DIMENSION
                || (long) bounds.outWidth * bounds.outHeight > MAX_CAPTCHA_PIXELS) {
            throw new IllegalArgumentException("Invalid CAPTCHA dimensions");
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inSampleSize = 1;
        while (bounds.outWidth / options.inSampleSize > 2_048
                || bounds.outHeight / options.inSampleSize > 2_048
                || (long) (bounds.outWidth / options.inSampleSize)
                * (bounds.outHeight / options.inSampleSize) > 4_000_000L) {
            options.inSampleSize *= 2;
        }
        Bitmap bitmap = BitmapFactory.decodeByteArray(encoded, 0, encoded.length, options);
        if (bitmap == null) {
            throw new IllegalArgumentException("Invalid CAPTCHA image");
        }
        return bitmap;
    }

    private void onRequestError(int generation, AgramBridgeRequestClient.RequestError error) {
        if (!isCurrentRequest(generation)) {
            return;
        }
        activeRequest = null;
        String message = error == null || TextUtils.isEmpty(error.message)
                ? "Не удалось выполнить запрос мостов."
                : error.message;
        if (error != null && !error.retryable) {
            message += " Повтор запроса сейчас недоступен.";
        }
        setError(message);
    }

    private void keepHandleIfCurrent(int generation, State expected,
                                     AgramBridgeRequestClient.RequestHandle handle) {
        if (generation == requestGeneration && state == expected) {
            activeRequest = handle;
        } else if (handle != null) {
            handle.cancel();
        }
    }

    private void applyBridges() {
        if (state != State.SUCCESS || !resultReady) {
            return;
        }
        if (delegate == null) {
            setError("Обработчик применения мостов не подключён.");
            return;
        }
        try {
            boolean enableBridges = !AgramBridgeRequestClient.TRANSPORT_DIRECT.equals(resultTransport);
            delegate.onBridgeConfigurationApproved(enableBridges, resultBridgeLines);
            state = State.APPLIED;
            errorMessage = null;
            updateUi();
            String appliedMessage = enableBridges
                    ? "Мосты применены, Tor перезапускается"
                    : "Мосты отключены, Tor перезапускается";
            Toast.makeText(getParentActivity(), appliedMessage, Toast.LENGTH_SHORT).show();
            statusView.announceForAccessibility(appliedMessage);
        } catch (RuntimeException error) {
            setError(safeErrorMessage(error));
        }
    }

    private void setError(String message) {
        errorMessage = TextUtils.isEmpty(message) ? "Не удалось выполнить запрос мостов." : message;
        state = State.ERROR;
        updateUi();
        if (statusView != null) {
            statusView.announceForAccessibility(errorMessage);
        }
    }

    private boolean isCurrentRequest(int generation) {
        return generation == requestGeneration && fragmentView != null;
    }

    private void cancelActiveRequest() {
        requestGeneration++;
        if (activeRequest != null) {
            try {
                activeRequest.cancel();
            } catch (RuntimeException ignore) {
                // Cancellation is best effort; stale callbacks are rejected by generation.
            }
            activeRequest = null;
        }
    }

    private void replaceCaptchaBitmap(Bitmap value) {
        if (captchaImage != null) {
            captchaImage.setImageBitmap(value);
        }
        if (captchaBitmap != null && captchaBitmap != value) {
            captchaBitmap.recycle();
        }
        captchaBitmap = value;
    }

    private void updateUi() {
        if (statusView == null) {
            return;
        }
        boolean busy = state == State.LOADING || state == State.SUBMITTING_CAPTCHA;
        boolean hasChallenge = !TextUtils.isEmpty(challengeId) && captchaBitmap != null;
        boolean hasResult = resultReady;
        boolean controlsEnabled = !busy && state != State.APPLIED;

        autoCell.setChecked(AgramBridgeRequestClient.TRANSPORT_AUTO.equals(transport), true);
        obfs4Cell.setChecked(AgramBridgeRequestClient.TRANSPORT_OBFS4.equals(transport), true);
        webTunnelCell.setChecked(AgramBridgeRequestClient.TRANSPORT_WEBTUNNEL.equals(transport), true);
        snowflakeCell.setChecked(AgramBridgeRequestClient.TRANSPORT_SNOWFLAKE.equals(transport), true);
        autoCell.setEnabled(controlsEnabled);
        obfs4Cell.setEnabled(controlsEnabled);
        webTunnelCell.setEnabled(controlsEnabled);
        snowflakeCell.setEnabled(controlsEnabled);
        manualCountrySwitch.setEnabled(controlsEnabled);
        countryInput.setEnabled(controlsEnabled);

        progressView.setVisibility(busy ? View.VISIBLE : View.GONE);
        requestAction.setVisibility(hasResult || state == State.APPLIED ? View.GONE : View.VISIBLE);
        setActionEnabled(requestAction, client != null && !busy);

        captchaContainer.setVisibility(hasChallenge && !hasResult ? View.VISIBLE : View.GONE);
        captchaInput.setEnabled(!busy);
        setActionEnabled(submitAction,
                !busy && !TextUtils.isEmpty(captchaInput.getText().toString().trim()));

        resultContainer.setVisibility(hasResult ? View.VISIBLE : View.GONE);
        if (hasResult) {
            boolean direct = AgramBridgeRequestClient.TRANSPORT_DIRECT.equals(resultTransport);
            int count = countNonEmptyLines(resultBridgeLines);
            String shownTransport = !TextUtils.isEmpty(resultTransport)
                    ? resultTransport
                    : AgramBridgeRequestClient.TRANSPORT_AUTO.equals(transport) ? "авто" : transport;
            resultSummary.setText(direct
                    ? "Рекомендация: Tor без мостов"
                    : "Получено мостов: " + count + " · транспорт: " + shownTransport);
            resultPreview.setText(resultBridgeLines);
            resultPreview.setVisibility(direct ? View.GONE : View.VISIBLE);
            resultExplanation.setText(direct
                    ? "Tor продолжит работать, но без мостов. Это не включает прямое подключение Telegram. После подтверждения общий Tor перезапустится."
                    : "После подтверждения текущие мосты будут заменены, использование мостов включится, а общий Tor перезапустится.");
            applyAction.setText(state == State.APPLIED
                    ? direct ? "МОСТЫ ОТКЛЮЧЕНЫ" : "МОСТЫ ПРИМЕНЕНЫ"
                    : direct ? "ОТКЛЮЧИТЬ МОСТЫ И ПЕРЕЗАПУСТИТЬ TOR"
                    : "ПРИМЕНИТЬ И ПЕРЕЗАПУСТИТЬ TOR");
            setActionEnabled(applyAction, state == State.SUCCESS);
        }

        statusView.setTextColor(Theme.getColor(state == State.ERROR
                ? Theme.key_text_RedRegular
                : state == State.SUCCESS || state == State.APPLIED
                ? Theme.key_windowBackgroundWhiteGreenText
                : Theme.key_windowBackgroundWhiteGrayText));
        switch (state) {
            case UNAVAILABLE:
                statusView.setText("Сервис получения мостов не подключён к этой сборке. "
                        + "Ручной ввод мостов остаётся доступен на предыдущем экране.");
                break;
            case IDLE:
                statusView.setText("Выберите транспорт и запустите автоматический подбор.");
                break;
            case LOADING:
                statusView.setText("Получаем рекомендованные настройки…");
                break;
            case AWAITING_CAPTCHA:
                statusView.setText("Сервис запросил CAPTCHA. Введите символы с изображения.");
                break;
            case SUBMITTING_CAPTCHA:
                statusView.setText("Проверяем CAPTCHA и получаем настройки…");
                break;
            case SUCCESS:
                statusView.setText(AgramBridgeRequestClient.TRANSPORT_DIRECT.equals(resultTransport)
                        ? "Сервис рекомендует Tor без мостов. Подтвердите применение."
                        : "Настройки получены. Проверьте мосты и подтвердите применение.");
                break;
            case ERROR:
                statusView.setText(errorMessage);
                break;
            case APPLIED:
                statusView.setText(AgramBridgeRequestClient.TRANSPORT_DIRECT.equals(resultTransport)
                        ? "Мосты отключены. Tor перезапускается."
                        : "Мосты применены. Tor перезапускается.");
                break;
        }
    }

    private static int countNonEmptyLines(String value) {
        int count = 0;
        for (String line : value.split("\\r?\\n")) {
            if (!TextUtils.isEmpty(line.trim())) {
                count++;
            }
        }
        return count;
    }

    private static String safeErrorMessage(RuntimeException error) {
        return error == null || TextUtils.isEmpty(error.getMessage())
                ? "Не удалось выполнить запрос мостов."
                : error.getMessage();
    }

    private static void setActionEnabled(TextView view, boolean enabled) {
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : .45f);
    }

    private abstract static class SimpleTextWatcher implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
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
        return text(context, value, 24, Theme.key_windowBackgroundWhiteBlackText, true);
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

    private static EditText input(Context context, String hint) {
        EditText view = new EditText(context);
        view.setHint(hint);
        view.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        view.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        view.setTextSize(15);
        view.setPadding(AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12), 0);
        view.setBackground(rounded(Theme.getColor(Theme.key_windowBackgroundGray), 10));
        return view;
    }

    private static TextView action(Context context, String value) {
        TextView view = text(context, value, 13, Theme.key_featuredStickers_buttonText, true);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(AndroidUtilities.dp(44));
        view.setFocusable(true);
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
