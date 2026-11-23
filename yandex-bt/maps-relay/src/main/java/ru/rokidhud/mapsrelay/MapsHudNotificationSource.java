package ru.rokidhud.mapsrelay;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Read-only fallback: only notifications posted by this SAME Yandex Maps APK. */
public final class MapsHudNotificationSource {
    private static final Pattern DISTANCE=Pattern.compile(
        "(?<![\\d.,])(\\d{1,4}(?:[,.]\\d)?)\\s*(км|метр(?:а|ов)?|м)(?![а-яa-z])",
        Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE);
    private static final Pattern ARRIVAL=Pattern.compile(
        "(?<!\\d)(?:[01]?\\d|2[0-3]):[0-5]\\d(?!\\d)");
    private MapsHudNotificationSource(){}

    public static final class Snapshot{
        public final String originalTitle,originalText,turnDistance,arrival;
        public final int turn; // -1 right, +1 left, +2 U turn, 0 straight/unknown
        public final long postTimeMs;
        private Snapshot(String title,String text,String distance,String eta,int kind,long at){
            originalTitle=title;originalText=text;turnDistance=distance;
            arrival=eta;turn=kind;postTimeMs=at;
        }
        public boolean hasManeuver() {
            return turnDistance!=null || turn!=0;
        }
    }
    private static String extract(Bundle extras,String key){
        CharSequence value=extras.getCharSequence(key);
        return value==null?"":value.toString().trim().replace('\n',' ');
    }
    public static Snapshot read(Context context) {
        try {
            NotificationManager manager=(NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
            if(manager==null)return null;
            StatusBarNotification[] active=manager.getActiveNotifications();
            if(active==null)return null;
            Snapshot best=null;
            for(StatusBarNotification sbn:active){
                if(!context.getPackageName().equals(sbn.getPackageName()) ||
                    sbn.getId()==MapsHudKeepAliveService.NOTIFICATION_ID)continue;
                Notification notification=sbn.getNotification();
                if(notification==null || notification.extras==null)continue;
                Bundle e=notification.extras;
                String title=extract(e,Notification.EXTRA_TITLE);
                String body=extract(e,Notification.EXTRA_TEXT);
                String big=extract(e,Notification.EXTRA_BIG_TEXT);
                String sub=extract(e,Notification.EXTRA_SUB_TEXT);
                String summary=(title+" "+body+" "+big+" "+sub).trim();
                String low=summary.toLowerCase(Locale.ROOT);
                if(summary.isEmpty() || summary.length()>800)continue;
                // Exclude general Yandex reminders/status. This fallback is
                // read only after an actual route was armed in the phone UI.
                boolean nav=low.contains("поверн")||low.contains("поворот")||
                    low.contains("направо")||low.contains("налево")||
                    low.contains("разворот")||low.contains("съезд")||
                    low.contains("прямо")||low.contains("держитесь")||
                    low.contains("двигайтесь")||low.contains("выезд")||
                    low.contains("exit ")||low.contains("turn ");
                // Generic "маршрут 5 км" isn't distance to the next turn.
                // Only explicit maneuver language may drive the HUD.
                if(!nav)continue;
                Matcher dm=DISTANCE.matcher(summary);
                String distance=dm.find()?dm.group(1)+" "+dm.group(2):null;
                Matcher em=ARRIVAL.matcher(summary);
                String eta=(low.contains("прибыт")||low.contains("eta")||
                    low.contains("arrival")) && em.find() ? em.group() : null;
                int turn=0;
                if(low.contains("разворот"))turn=2;
                else if(low.contains("налево")||low.contains("левее"))turn=1;
                else if(low.contains("направо")||low.contains("правее"))turn=-1;
                // Only draw a maneuver if direction or distance genuinely
                // came from Yandex's own notification; never manufacture lanes.
                if(distance==null && turn==0)continue;
                Snapshot candidate=new Snapshot(title,body,distance,eta,turn,sbn.getPostTime());
                if(best==null || candidate.postTimeMs>best.postTimeMs)best=candidate;
            }
            return best;
        }catch(Throwable ignored){return null;}
    }
}
