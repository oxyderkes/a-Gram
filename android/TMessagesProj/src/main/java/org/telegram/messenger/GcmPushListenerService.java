/* Agram, GPL v2 or later. */
package org.telegram.messenger;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import androidx.annotation.Keep;

/** Inert upgrade compatibility component; removed from Agram's merged manifest. */
@Keep
public final class GcmPushListenerService extends Service {
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        stopSelf(startId);
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public void onNewToken(String token) {
        // Never log, persist or forward a delayed legacy token.
    }
}
