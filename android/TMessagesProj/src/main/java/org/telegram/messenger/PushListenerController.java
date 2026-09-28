package org.telegram.messenger;

import androidx.annotation.IntDef;
import androidx.annotation.Keep;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Compatibility surface for upstream callers. Agram uses per-container Simple
 * Push or direct MTProto; legacy FCM/Huawei messages and tokens are never used.
 */
@Keep
public class PushListenerController {
    public static final int PUSH_TYPE_FIREBASE = 2, PUSH_TYPE_HUAWEI = 13;
    public static final int NOTIFICATION_ID = 1;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({PUSH_TYPE_FIREBASE, PUSH_TYPE_HUAWEI})
    public @interface PushType { }

    public static void sendRegistrationToServer(@PushType int pushType, String token) {
        // Intentionally inert, including callbacks carrying an old shared token.
        // Do not initialize accounts, save the token, or send token statistics.
    }

    public static void processRemoteMessage(@PushType int pushType, String data, long time) {
        // A stale platform delivery must not wake or modify any Agram account.
    }

    @Keep
    public interface IPushListenerServiceProvider {
        boolean hasServices();
        String getLogTitle();
        void onRequestPushToken();
        @PushType int getPushType();
    }

    public static final class GooglePushListenerServiceProvider implements IPushListenerServiceProvider {
        public static final GooglePushListenerServiceProvider INSTANCE = new GooglePushListenerServiceProvider();
        private Boolean hasServices;

        private GooglePushListenerServiceProvider() { }

        @Override
        public String getLogTitle() {
            return "Google Play Services";
        }

        @Override
        public int getPushType() {
            return PUSH_TYPE_FIREBASE;
        }

        @Override
        public void onRequestPushToken() {
            // Kept for source compatibility; never initialize Firebase messaging.
        }

        @Override
        public boolean hasServices() {
            // Also used by location and Google sign-in, independently of push.
            if (hasServices == null) {
                try {
                    hasServices = GoogleApiAvailability.getInstance()
                            .isGooglePlayServicesAvailable(ApplicationLoader.applicationContext) == ConnectionResult.SUCCESS;
                } catch (Exception e) {
                    hasServices = false;
                }
            }
            return hasServices;
        }
    }
}
