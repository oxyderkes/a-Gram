/*
 * This file is part of Agram and is licensed under GNU GPL v2 or later.
 */
package org.telegram.messenger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import org.telegram.ui.LaunchActivity;

/** Keeps the embedded Agram Push subscriptions alive on Android 8+. */
public class AgramPushService extends Service {

    private static final String CHANNEL_ID = "agram_push_transport";
    private static final int NOTIFICATION_ID = 0xA612;
    private static volatile AgramPushService instance;
    private int accountCount;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        ApplicationLoader.postInitApplication();
        AgramPushController.getInstance().onServiceStarted();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        AgramPushController.getInstance().onServiceStarted();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        AgramPushController.getInstance().onServiceStopped();
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    static void updateForegroundNotification(int accounts) {
        AgramPushService service = instance;
        if (service == null || service.accountCount == accounts) {
            return;
        }
        service.accountCount = accounts;
        NotificationManager manager = service.getSystemService(NotificationManager.class);
        if (manager != null) {
            try {
                manager.notify(NOTIFICATION_ID, service.buildNotification());
            } catch (RuntimeException error) {
                FileLog.e("Unable to update Agram Push foreground notification");
            }
        }
    }

    private void createChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Agram Push", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Встроенная доставка сигналов о новых сообщениях");
        channel.setShowBadge(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, LaunchActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String text = accountCount > 0
                ? "Фоновые соединения для уведомлений"
                : "Подготовка фонового соединения";
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.notification)
                .setContentTitle("Agram Push")
                .setContentText(text)
                .setContentIntent(pendingIntent)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_SECRET)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }
}
