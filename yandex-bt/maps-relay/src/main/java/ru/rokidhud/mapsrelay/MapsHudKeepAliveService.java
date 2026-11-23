package ru.rokidhud.mapsrelay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.Build;

/** Same foreground keepalive strategy as the proven Navigator TEST9 transport. */
public final class MapsHudKeepAliveService extends Service {
    private static final String CHANNEL = "maps31_rokid_hud_foreground";
    public static final int NOTIFICATION_ID = 9315;

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(nm!=null && Build.VERSION.SDK_INT>=26) {
            nm.createNotificationChannel(new NotificationChannel(
                CHANNEL,"Яндекс Карты → Rokid HUD",NotificationManager.IMPORTANCE_LOW));
        }
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        // Android 7 and earlier have no notification-channel constructor.
        Notification.Builder builder=Build.VERSION.SDK_INT>=26
            ? new Notification.Builder(this,CHANNEL)
            : new Notification.Builder(this);
        Notification notification=builder
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle("Яндекс Карты → Rokid")
            .setContentText("Передача данных маршрута на очки")
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true).build();
        startForeground(NOTIFICATION_ID,notification);
        return START_STICKY;
    }
    @Override public IBinder onBind(Intent intent){return null;}
}
