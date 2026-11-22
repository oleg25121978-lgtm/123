package ru.rokidhud.phone;

import android.app.Notification;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.util.LinkedHashSet;

/** Only reads Yandex notification text; never captures/mirrors a screen. */
public class YandexListener extends NotificationListenerService {
    private String activeKey;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override public void onListenerConnected() {
        super.onListenerConnected();
        StatusBarNotification[] active = getActiveNotifications();
        if (active != null) for (StatusBarNotification sbn : active) onNotificationPosted(sbn);
    }
    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        String p = sbn.getPackageName();
        if (!NavData.YANDEX_MAPS.equals(p) && !NavData.YANDEX_NAVI.equals(p)) return;
        Notification n = sbn.getNotification();
        Bundle e = n.extras;
        LinkedHashSet<String> texts = new LinkedHashSet<>();
        for (String k : new String[]{Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT,
                Notification.EXTRA_SUB_TEXT, Notification.EXTRA_SUMMARY_TEXT}) {
            CharSequence c = e.getCharSequence(k);
            if (c != null && c.length() > 0) texts.add(c.toString());
        }
        CharSequence[] lines = e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (lines != null) for (CharSequence line : lines) if (line != null) texts.add(line.toString());
        // More detailed and often bitmap-only Yandex RemoteViews are not yet decoded.
        String joined = String.join(" | ", texts);
        NavData next = NavData.parse(p, joined);
        if (next != null) {
            if (!next.distance.isEmpty()) {
                activeKey = sbn.getKey();
                NavData.current = next;
            } else if (activeKey != null && activeKey.equals(sbn.getKey())) {
                NavData.current = null;
            }
            BluetoothHudService.debugText = "Яндекс: " + joined;
        }
    }
    @Override public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn != null && sbn.getKey().equals(activeKey)) {
            activeKey = null;
            NavData.current = null;
        }
    }
    @Override public void onListenerDisconnected() {
        activeKey = null;
        NavData.current = null;
        super.onListenerDisconnected();
    }
}
