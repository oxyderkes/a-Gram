/*
 * This file is part of Agram and is licensed under GNU GPL v2 or later.
 */
package org.telegram.messenger;

/**
 * Backend boundary for requesting Tor bridges.
 *
 * <p>The UI deliberately does not know an endpoint, authentication scheme, or wire format. A
 * concrete implementation can be supplied by the application when those details are available.
 * Callbacks may be invoked from any thread.</p>
 */
public abstract class AgramBridgeRequestClient {
    public static final String TRANSPORT_AUTO = "auto";
    public static final String TRANSPORT_OBFS4 = "obfs4";
    public static final String TRANSPORT_WEBTUNNEL = "webtunnel";
    public static final String TRANSPORT_SNOWFLAKE = "snowflake";
    /** Backend result meaning that Tor should run without bridges; never means bypassing Tor. */
    public static final String TRANSPORT_DIRECT = "direct";

    public interface RequestHandle {
        void cancel();
    }

    public interface Callback<T> {
        void onSuccess(T value);

        void onError(RequestError error);
    }

    public static final class RequestError {
        public final String message;
        public final boolean retryable;

        public RequestError(String message, boolean retryable) {
            this.message = message == null ? "" : message;
            this.retryable = retryable;
        }
    }

    public static final class CaptchaChallenge {
        /** Opaque value that must be returned with the CAPTCHA answer. */
        public final String challengeId;
        /** Encoded image bytes, for example PNG, JPEG, or WebP. */
        public final byte[] imageBytes;

        public CaptchaChallenge(String challengeId, byte[] imageBytes) {
            this.challengeId = challengeId == null ? "" : challengeId;
            this.imageBytes = imageBytes == null ? null : imageBytes.clone();
        }
    }

    public static final class BridgeRequest {
        /** Empty means that the service should infer the country from the request IP. */
        public final String countryCode;
        /** Requested transports; the service may rank its response. */
        public final String[] transports;

        public BridgeRequest(String countryCode, String[] transports) {
            this.countryCode = countryCode == null ? "" : countryCode;
            this.transports = transports == null ? new String[0] : transports.clone();
        }
    }

    public static final class BridgeResponse {
        /** Newline-separated bridge lines returned by the configured service. */
        public final String bridgeLines;
        /** Transport selected by the service, if it reports one. */
        public final String transport;

        public BridgeResponse(String bridgeLines, String transport) {
            this.bridgeLines = bridgeLines == null ? "" : bridgeLines;
            this.transport = transport == null ? "" : transport;
        }
    }

    /** Either contains bridge lines immediately or a backend-required fallback CAPTCHA. */
    public static final class BridgeRequestResult {
        public final BridgeResponse bridges;
        public final CaptchaChallenge challenge;

        public BridgeRequestResult(BridgeResponse bridges, CaptchaChallenge challenge) {
            this.bridges = bridges;
            this.challenge = challenge;
        }
    }

    /** Requests ranked bridge settings; a successful response may not require a CAPTCHA. */
    public abstract RequestHandle requestBridges(
            BridgeRequest request, Callback<BridgeRequestResult> callback);

    /** Completes a backend-provided CAPTCHA challenge. */
    public abstract RequestHandle submitChallenge(
            BridgeRequest request,
            String challengeId,
            String captchaAnswer,
            Callback<BridgeRequestResult> callback);
}
