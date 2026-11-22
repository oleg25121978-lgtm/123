package ru.yandex.yandexnavi.rokidhud;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.Build;

/**
 * Keeps the existing TEST9 Yandex navigation process and its Bluetooth HUD
 * relay available after user switches to another app or locks the phone.
 * Does not start navigation, modify a route, or render the phone map.
 */
public final class HudKeepAliveService extends Service {
    private static final String CHANNEL = "test9_rokid_hud_foreground";
    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL, "TEST9 HUD в очках", NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_directions)
                .setContentTitle("Test9 → Rokid HUD")
                .setContentText("Передача навигационного HUD на очки")
                .setOngoing(true)
                .build();
        startForeground(9079, notification);
        return START_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
