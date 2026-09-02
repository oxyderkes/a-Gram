/*
 * This file is part of Agram and is licensed under GNU GPL v2 or later.
 */
package org.telegram.messenger;

import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLPeerUnverifiedException;

/**
 * Official Tor Project rdsys/Moat client used by Connection Assist.
 *
 * <p>Bridge addresses are treated as sensitive data: neither responses nor request payloads are
 * written to Telegram logs. Requests use the system TLS verifier plus the SPKI pin published by
 * the current Tor VPN Android client.</p>
 */
public final class AgramRdsysBridgeClient extends AgramBridgeRequestClient {
    private static final String API_ORIGIN = "https://bridges.torproject.org";
    private static final String SETTINGS_PATH = "/moat/circumvention/settings";
    private static final String BUILTIN_PATH = "/moat/circumvention/builtin";
    private static final String CAPTCHA_FETCH_PATH = "/moat/fetch";
    private static final String CAPTCHA_CHECK_PATH = "/moat/check";

    // Tor VPN Android main, CircumventionApi.kt. Pin the SPKI, not a leaf certificate serial.
    private static final String TOR_BRIDGES_SPKI_SHA256 =
            "6cWKjcQb/AUlNln7IHy7ZGGQCRl4cI9gq6FH1o9rhw4=";
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int OVERALL_TIMEOUT_MS = 45_000;
    private static final int MAX_JSON_BYTES = 4 * 1024 * 1024;
    private static final int MAX_CAPTCHA_BYTES = 2 * 1024 * 1024;
    private static final int MAX_BRIDGES = 64;
    private static final int MAX_BRIDGE_LINE_LENGTH = 2_048;

    private static final ExecutorService REQUEST_EXECUTOR = new ThreadPoolExecutor(
            2, 2, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8), new ThreadFactory() {
                private int index;

                @Override
                public synchronized Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "agram-rdsys-" + (++index));
                    thread.setDaemon(true);
                    thread.setPriority(Thread.NORM_PRIORITY - 1);
                    return thread;
                }
            }, new ThreadPoolExecutor.AbortPolicy());

    @Override
    public RequestHandle requestBridges(BridgeRequest request,
                                        Callback<BridgeRequestResult> callback) {
        final BridgeRequest safeRequest;
        try {
            safeRequest = normalizeRequest(request);
        } catch (IllegalArgumentException error) {
            return failImmediately(callback, error.getMessage(), false);
        }
        return execute(callback, handle -> requestRecommendations(handle, safeRequest));
    }

    @Override
    public RequestHandle submitChallenge(BridgeRequest request,
                                         String challengeId,
                                         String captchaAnswer,
                                         Callback<BridgeRequestResult> callback) {
        final BridgeRequest safeRequest;
        final ChallengeToken token;
        try {
            safeRequest = normalizeRequest(request);
            token = ChallengeToken.decode(challengeId);
            if (TextUtils.isEmpty(captchaAnswer) || captchaAnswer.trim().length() > 128) {
                throw new IllegalArgumentException("Введите ответ CAPTCHA");
            }
        } catch (IllegalArgumentException error) {
            return failImmediately(callback, error.getMessage(), false);
        }
        final String answer = captchaAnswer.trim();
        return execute(callback, handle -> submitCaptcha(handle, safeRequest, token, answer));
    }

    private BridgeRequestResult requestRecommendations(ConnectionHandle handle,
                                                        BridgeRequest request) throws Exception {
        JSONObject payload = settingsPayload(request, true);
        JSONObject response;
        try {
            response = postJson(handle, SETTINGS_PATH, payload, "application/json; charset=utf-8");
            throwIfApiError(response);
        } catch (ApiException error) {
            if (error.code == 419 && supports(request, TRANSPORT_OBFS4)) {
                return requestCaptcha(handle, request, TRANSPORT_OBFS4);
            }
            if (error.code == 404 || error.code == 406) {
                if (supportsOnly(request, TRANSPORT_OBFS4)) {
                    return requestCaptcha(handle, request, TRANSPORT_OBFS4);
                }
                if (supports(request, TRANSPORT_SNOWFLAKE)) {
                    return bundledSnowflake();
                }
            }
            throw error;
        } catch (IOException error) {
            if (supports(request, TRANSPORT_SNOWFLAKE) && canUseOfflineFallback(error)) {
                return bundledSnowflake();
            }
            throw error;
        }

        JSONArray settings = response.optJSONArray("settings");
        if (settings == null) {
            throw new ApiException(0, "Сервис Tor вернул ответ без настроек", true);
        }
        if (settings.length() == 0) {
            // This is rdsys' explicit signal that Tor should work without bridges. Telegram still
            // remains fail-closed behind the embedded Tor daemon.
            return new BridgeRequestResult(new BridgeResponse("", TRANSPORT_DIRECT), null);
        }

        JSONObject builtins = null;
        LinkedHashSet<String> lines = new LinkedHashSet<>();
        String firstTransport = "";
        boolean mixedTransports = false;
        for (int i = 0; i < settings.length() && lines.size() < MAX_BRIDGES; i++) {
            JSONObject wrapper = settings.optJSONObject(i);
            JSONObject bridges = wrapper == null ? null : wrapper.optJSONObject("bridges");
            if (bridges == null) {
                continue;
            }
            String transport = normalizeTransport(bridges.optString("type", ""));
            if (TextUtils.isEmpty(transport) || !supports(request, transport)) {
                continue;
            }
            JSONArray bridgeStrings = bridges.optJSONArray("bridge_strings");
            if ((bridgeStrings == null || bridgeStrings.length() == 0)
                    && "builtin".equals(bridges.optString("source", ""))) {
                if (builtins == null) {
                    builtins = getJson(handle, BUILTIN_PATH);
                    throwIfApiError(builtins);
                }
                bridgeStrings = builtins.optJSONArray(transport);
            }
            int before = lines.size();
            addBridgeLines(lines, bridgeStrings, transport);
            if (lines.size() > before) {
                if (TextUtils.isEmpty(firstTransport)) {
                    firstTransport = transport;
                } else if (!firstTransport.equals(transport)) {
                    mixedTransports = true;
                }
            }
        }
        if (lines.isEmpty()) {
            if (supports(request, TRANSPORT_SNOWFLAKE)) {
                return bundledSnowflake();
            }
            throw new ApiException(0, "Сервис Tor не вернул совместимых мостов", true);
        }
        return new BridgeRequestResult(
                new BridgeResponse(joinLines(lines), mixedTransports || TextUtils.isEmpty(firstTransport)
                        ? TRANSPORT_AUTO : firstTransport), null);
    }

    private BridgeRequestResult requestCaptcha(ConnectionHandle handle,
                                               BridgeRequest request,
                                               String transport) throws Exception {
        JSONObject item = new JSONObject();
        item.put("type", "client-transports");
        item.put("version", "0.1.0");
        item.put("supported", new JSONArray().put(transport));
        JSONObject payload = new JSONObject();
        payload.put("data", new JSONArray().put(item));

        JSONObject response = postJson(handle, CAPTCHA_FETCH_PATH, payload,
                "application/vnd.api+json");
        throwIfApiError(response);
        JSONArray data = response.optJSONArray("data");
        JSONObject challenge = data == null ? null : data.optJSONObject(0);
        String opaqueChallenge = challenge == null ? "" : challenge.optString("challenge", "");
        String imageValue = challenge == null ? "" : challenge.optString("image", "");
        if (TextUtils.isEmpty(opaqueChallenge) || TextUtils.isEmpty(imageValue)) {
            throw new ApiException(0, "Сервис Tor не выдал CAPTCHA", true);
        }
        byte[] image;
        try {
            image = Base64.decode(stripDataUrl(imageValue), Base64.DEFAULT);
        } catch (IllegalArgumentException error) {
            throw new ApiException(0, "Сервис Tor вернул повреждённую CAPTCHA", true);
        }
        if (image.length == 0 || image.length > MAX_CAPTCHA_BYTES) {
            throw new ApiException(0, "Некорректный размер CAPTCHA", true);
        }
        String serverTransport = challengeTransport(challenge, transport);
        if (!supports(request, serverTransport)) {
            throw new ApiException(0, "Сервис Tor запросил неподдерживаемый транспорт CAPTCHA", false);
        }
        String token = new ChallengeToken(serverTransport, opaqueChallenge).encode();
        return new BridgeRequestResult(null, new CaptchaChallenge(token, image));
    }

    private BridgeRequestResult submitCaptcha(ConnectionHandle handle,
                                               BridgeRequest request,
                                               ChallengeToken token,
                                               String answer) throws Exception {
        if (!supports(request, token.transport)) {
            throw new ApiException(0, "Транспорт CAPTCHA больше не выбран", false);
        }
        JSONObject item = new JSONObject();
        item.put("type", "moat-solution");
        item.put("id", "2");
        item.put("version", "0.1.0");
        item.put("transport", token.transport);
        item.put("challenge", token.challenge);
        item.put("qrcode", "false");
        item.put("solution", answer);
        JSONObject payload = new JSONObject();
        payload.put("data", new JSONArray().put(item));

        JSONObject response = postJson(handle, CAPTCHA_CHECK_PATH, payload,
                "application/vnd.api+json");
        throwIfApiError(response);
        JSONArray data = response.optJSONArray("data");
        JSONObject result = data == null ? null : data.optJSONObject(0);
        JSONArray bridgeStrings = result == null ? null : result.optJSONArray("bridges");
        LinkedHashSet<String> lines = new LinkedHashSet<>();
        addBridgeLines(lines, bridgeStrings, token.transport);
        if (lines.isEmpty()) {
            throw new ApiException(0, "После CAPTCHA сервис Tor не вернул мосты", true);
        }
        return new BridgeRequestResult(
                new BridgeResponse(joinLines(lines), token.transport), null);
    }

    private interface RequestOperation {
        BridgeRequestResult run(ConnectionHandle handle) throws Exception;
    }

    private RequestHandle execute(Callback<BridgeRequestResult> callback,
                                  RequestOperation operation) {
        ConnectionHandle handle = new ConnectionHandle();
        final Future<?> future;
        try {
            future = REQUEST_EXECUTOR.submit(() -> {
                BridgeRequestResult result = null;
                RequestError failure = null;
                try {
                    result = operation.run(handle);
                } catch (Exception error) {
                    failure = toRequestError(error);
                } finally {
                    handle.closeConnection();
                }
                try {
                    if (!handle.cancelled.get() && callback != null) {
                        if (failure == null) {
                            callback.onSuccess(result);
                        } else {
                            callback.onError(failure);
                        }
                    }
                } finally {
                    handle.clearFuture();
                }
            });
        } catch (RejectedExecutionException error) {
            return failImmediately(callback, "Слишком много одновременных запросов мостов", true);
        }
        handle.setFuture(future);
        return handle;
    }

    private static BridgeRequestResult bundledSnowflake() {
        return new BridgeRequestResult(new BridgeResponse(
                AgramTorBridgePool.builtInSnowflakeLine(), TRANSPORT_SNOWFLAKE), null);
    }

    private static boolean canUseOfflineFallback(IOException error) {
        return !(error instanceof SSLPeerUnverifiedException)
                && !(error instanceof javax.net.ssl.SSLHandshakeException);
    }

    private static boolean supportsOnly(BridgeRequest request, String transport) {
        return request.transports.length == 1 && transport.equals(request.transports[0]);
    }

    private static String challengeTransport(JSONObject challenge, String fallback)
            throws ApiException {
        if (challenge == null || !challenge.has("transport")) {
            return fallback;
        }
        Object value = challenge.opt("transport");
        String result = "";
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            if (array.length() > 0) {
                result = normalizeTransport(array.optString(0, ""));
            }
        } else {
            result = normalizeTransport(String.valueOf(value));
        }
        if (TextUtils.isEmpty(result) || TRANSPORT_AUTO.equals(result)) {
            throw new ApiException(0, "Сервис Tor вернул некорректный транспорт CAPTCHA", false);
        }
        return result;
    }

    private static RequestHandle failImmediately(Callback<BridgeRequestResult> callback,
                                                 String message,
                                                 boolean retryable) {
        ConnectionHandle handle = new ConnectionHandle();
        if (callback != null) {
            AndroidUtilities.runOnUIThread(() -> {
                if (!handle.cancelled.get()) {
                    callback.onError(new RequestError(message, retryable));
                }
            });
        }
        return handle;
    }

    private static BridgeRequest normalizeRequest(BridgeRequest request) {
        String country = request == null ? "" : request.countryCode.trim().toLowerCase(Locale.US);
        if (!TextUtils.isEmpty(country) && !country.matches("[a-z]{2}")) {
            throw new IllegalArgumentException("Регион должен быть двухбуквенным ISO-кодом, например BY");
        }
        Set<String> transports = new LinkedHashSet<>();
        boolean automatic = request == null || request.transports.length == 0;
        if (request != null) {
            for (String value : request.transports) {
                String transport = normalizeTransport(value);
                if (TRANSPORT_AUTO.equals(transport)) {
                    transports.clear();
                    automatic = true;
                    break;
                }
                if (TextUtils.isEmpty(transport)) {
                    throw new IllegalArgumentException("Неизвестный транспорт Tor");
                }
                transports.add(transport);
            }
        }
        if (automatic) {
            transports.add(TRANSPORT_OBFS4);
            transports.add(TRANSPORT_WEBTUNNEL);
            transports.add(TRANSPORT_SNOWFLAKE);
        } else if (transports.isEmpty()) {
            throw new IllegalArgumentException("Выберите хотя бы один транспорт Tor");
        }
        return new BridgeRequest(country, transports.toArray(new String[0]));
    }

    private static String normalizeTransport(String value) {
        String transport = value == null ? "" : value.trim().toLowerCase(Locale.US);
        if (TRANSPORT_AUTO.equals(transport)
                || TRANSPORT_OBFS4.equals(transport)
                || TRANSPORT_WEBTUNNEL.equals(transport)
                || TRANSPORT_SNOWFLAKE.equals(transport)) {
            return transport;
        }
        return "";
    }

    private static boolean supports(BridgeRequest request, String transport) {
        for (String value : request.transports) {
            if (transport.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static JSONObject settingsPayload(BridgeRequest request, boolean includeCountry)
            throws JSONException {
        JSONObject payload = new JSONObject();
        if (includeCountry && !TextUtils.isEmpty(request.countryCode)) {
            payload.put("country", request.countryCode);
        }
        JSONArray transports = new JSONArray();
        for (String value : request.transports) {
            transports.put(value);
        }
        payload.put("transports", transports);
        return payload;
    }

    private static void addBridgeLines(Set<String> destination,
                                       JSONArray values,
                                       String expectedTransport) throws ApiException {
        if (values == null) {
            return;
        }
        for (int i = 0; i < values.length() && destination.size() < MAX_BRIDGES; i++) {
            String line = values.optString(i, "").trim();
            if (line.regionMatches(true, 0, "Bridge ", 0, 7)) {
                line = line.substring(7).trim();
            }
            if (TextUtils.isEmpty(line) || line.length() > MAX_BRIDGE_LINE_LENGTH
                    || containsControlCharacter(line)) {
                continue;
            }
            String actualTransport = line.indexOf(' ') > 0
                    ? line.substring(0, line.indexOf(' ')).toLowerCase(Locale.US) : "vanilla";
            if (!"vanilla".equals(expectedTransport) && !expectedTransport.equals(actualTransport)) {
                continue;
            }
            try {
                String normalized = AgramTorManager.normalizeBridgeLines(line);
                if (!TextUtils.isEmpty(normalized)) {
                    destination.add(normalized);
                }
            } catch (IllegalArgumentException ignore) {
                // Ignore one malformed server item without discarding other valid bridges.
            }
        }
        if (destination.size() > MAX_BRIDGES) {
            throw new ApiException(0, "Сервис Tor вернул слишком много мостов", false);
        }
    }

    private static boolean containsControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character < 0x20 && character != '\t') {
                return true;
            }
        }
        return false;
    }

    private static String joinLines(Set<String> lines) {
        StringBuilder result = new StringBuilder();
        for (String line : lines) {
            if (result.length() > 0) {
                result.append('\n');
            }
            result.append(line);
        }
        return result.toString();
    }

    private static JSONObject getJson(ConnectionHandle handle, String path) throws Exception {
        return requestJson(handle, "GET", path, null, "application/json; charset=utf-8");
    }

    private static JSONObject postJson(ConnectionHandle handle,
                                       String path,
                                       JSONObject payload,
                                       String contentType) throws Exception {
        return requestJson(handle, "POST", path, payload.toString(), contentType);
    }

    private static JSONObject requestJson(ConnectionHandle handle,
                                          String method,
                                          String path,
                                          String body,
                                          String contentType) throws Exception {
        if (handle.cancelled.get()) {
            throw new IOException("cancelled");
        }
        URL url = new URL(API_ORIGIN + path);
        if (!"https".equals(url.getProtocol()) || !"bridges.torproject.org".equals(url.getHost())) {
            throw new SSLPeerUnverifiedException("Unexpected Tor service origin");
        }
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        handle.setConnection(connection);
        long deadline = SystemClock.elapsedRealtime() + OVERALL_TIMEOUT_MS;
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Accept", "application/json, application/vnd.api+json");
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("Cache-Control", "no-store");
            connection.setRequestProperty("User-Agent", "Agram-Android ConnectionAssist/1");
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(bytes.length);
                connection.setRequestProperty("Content-Type", contentType);
                connection.connect();
                verifyPin(connection);
                try (OutputStream output = connection.getOutputStream()) {
                    if (handle.cancelled.get() || Thread.currentThread().isInterrupted()) {
                        throw new IOException("cancelled");
                    }
                    output.write(bytes);
                    output.flush();
                }
            } else {
                connection.connect();
                verifyPin(connection);
            }
            int status = connection.getResponseCode();
            if (status != HttpsURLConnection.HTTP_OK) {
                throw new ApiException(status, "Сервис Tor ответил HTTP " + status,
                        status >= 500 || status == 408 || status == 429);
            }
            String response = readLimited(handle, connection.getInputStream(), MAX_JSON_BYTES,
                    deadline);
            if (handle.cancelled.get()) {
                throw new IOException("cancelled");
            }
            try {
                return new JSONObject(response);
            } catch (JSONException error) {
                throw new ApiException(0, "Сервис Tor вернул повреждённый JSON", true);
            }
        } finally {
            handle.clearConnection(connection);
            connection.disconnect();
        }
    }

    private static String readLimited(ConnectionHandle handle, InputStream input, int limit,
                                      long deadline) throws IOException {
        try (InputStream stream = input;
             ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(32_768, limit))) {
            byte[] buffer = new byte[8_192];
            int total = 0;
            int read;
            while ((read = stream.read(buffer)) != -1) {
                if (handle.cancelled.get() || Thread.currentThread().isInterrupted()) {
                    throw new IOException("cancelled");
                }
                if (SystemClock.elapsedRealtime() > deadline) {
                    throw new SocketTimeoutException("Tor service request deadline exceeded");
                }
                total += read;
                if (total > limit) {
                    throw new IOException("Tor service response is too large");
                }
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static void verifyPin(HttpsURLConnection connection) throws Exception {
        byte[] expected = Base64.decode(TOR_BRIDGES_SPKI_SHA256, Base64.DEFAULT);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        Certificate[] chain = connection.getServerCertificates();
        for (Certificate certificate : chain) {
            if (certificate instanceof X509Certificate) {
                byte[] actual = digest.digest(certificate.getPublicKey().getEncoded());
                if (MessageDigest.isEqual(expected, actual)) {
                    return;
                }
            }
        }
        throw new SSLPeerUnverifiedException("Tor service certificate pin mismatch");
    }

    private static void throwIfApiError(JSONObject response) throws ApiException {
        JSONArray errors = response.optJSONArray("errors");
        if (errors == null || errors.length() == 0) {
            return;
        }
        JSONObject first = errors.optJSONObject(0);
        int code = first == null ? 0 : first.optInt("code", 0);
        String detail = first == null ? "" : sanitizeDetail(first.optString("detail", ""));
        if (TextUtils.isEmpty(detail)) {
            detail = "Сервис Tor отклонил запрос";
        }
        throw new ApiException(code, detail, code == 419 || code == 429 || code >= 500);
    }

    private static String sanitizeDetail(String value) {
        String result = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
        if (result.length() > 200) {
            result = result.substring(0, 200) + "…";
        }
        return result;
    }

    private static String stripDataUrl(String value) {
        int marker = value.indexOf("base64,");
        return marker >= 0 ? value.substring(marker + 7) : value;
    }

    private static RequestError toRequestError(Throwable error) {
        if (error instanceof ApiException) {
            ApiException api = (ApiException) error;
            return new RequestError(api.getMessage(), api.retryable);
        }
        if (error instanceof SocketTimeoutException) {
            return new RequestError("Сервис Tor не ответил вовремя", true);
        }
        if (error instanceof UnknownHostException) {
            return new RequestError("Не удалось найти официальный сервис мостов Tor", true);
        }
        if (error instanceof SSLPeerUnverifiedException) {
            return new RequestError("Не удалось подтвердить сертификат сервиса Tor", false);
        }
        if (error instanceof IOException) {
            return new RequestError("Не удалось связаться с официальным сервисом мостов Tor", true);
        }
        return new RequestError("Ошибка Connection Assist: " + error.getClass().getSimpleName(), false);
    }

    private static final class ApiException extends Exception {
        final int code;
        final boolean retryable;

        ApiException(int code, String message, boolean retryable) {
            super(message);
            this.code = code;
            this.retryable = retryable;
        }
    }

    private static final class ConnectionHandle implements RequestHandle {
        final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile HttpsURLConnection connection;
        private volatile Future<?> future;

        @Override
        public void cancel() {
            cancelled.set(true);
            closeConnection();
            Future<?> value = future;
            if (value != null) {
                value.cancel(true);
            }
        }

        void setFuture(Future<?> value) {
            future = value;
            if (cancelled.get() && value != null) {
                value.cancel(true);
            }
        }

        void clearFuture() {
            future = null;
        }

        void setConnection(HttpsURLConnection value) throws IOException {
            if (cancelled.get()) {
                value.disconnect();
                throw new IOException("cancelled");
            }
            connection = value;
            if (cancelled.get()) {
                closeConnection();
                throw new IOException("cancelled");
            }
        }

        void clearConnection(HttpsURLConnection value) {
            if (connection == value) {
                connection = null;
            }
        }

        void closeConnection() {
            HttpsURLConnection value = connection;
            connection = null;
            if (value != null) {
                value.disconnect();
            }
        }
    }

    /** Packs the server challenge together with the selected transport without exposing either. */
    private static final class ChallengeToken {
        final String transport;
        final String challenge;

        ChallengeToken(String transport, String challenge) {
            this.transport = transport;
            this.challenge = challenge;
        }

        String encode() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("t", transport);
            json.put("c", challenge);
            return Base64.encodeToString(json.toString().getBytes(StandardCharsets.UTF_8),
                    Base64.NO_WRAP | Base64.URL_SAFE);
        }

        static ChallengeToken decode(String encoded) {
            try {
                byte[] clear = Base64.decode(encoded, Base64.NO_WRAP | Base64.URL_SAFE);
                JSONObject json = new JSONObject(new String(clear, StandardCharsets.UTF_8));
                String transport = normalizeTransport(json.optString("t", ""));
                String challenge = json.optString("c", "");
                if (TextUtils.isEmpty(transport) || TRANSPORT_AUTO.equals(transport)
                        || TextUtils.isEmpty(challenge) || challenge.length() > 4_096) {
                    throw new IllegalArgumentException("CAPTCHA устарела, запросите новую");
                }
                return new ChallengeToken(transport, challenge);
            } catch (Exception error) {
                if (error instanceof IllegalArgumentException) {
                    throw (IllegalArgumentException) error;
                }
                throw new IllegalArgumentException("CAPTCHA устарела, запросите новую");
            }
        }
    }
}
