package ru.yandex.yandexnavi.rokidhud;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Rokid-style unobtrusive lower strip. Reads only live fields from TEST9's existing NavigationView. */
public final class HudBottomLayout {
    private static final int GREEN=Color.rgb(98,255,137);
    private static final int MUTED=Color.rgb(89,170,110);
    private static final int WHITE=Color.rgb(219,255,225);
    private static final int W=480, H=640;

    private HudBottomLayout() {}

    private static Object field(View hud,String name) {
        try {
            Field f=hud.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(hud);
        } catch(Throwable ignored) {return null;}
    }
    private static int valueInt(View hud,String name,int fallback) {
        Object x=field(hud,name);
        return x instanceof Number ? ((Number)x).intValue() : fallback;
    }
    private static double valueDouble(View hud,String name,double fallback) {
        Object x=field(hud,name);
        return x instanceof Number ? ((Number)x).doubleValue() : fallback;
    }
    private static String valueString(View hud,String name) {
        Object x=field(hud,name);
        return x instanceof String ? ((String)x) : "";
    }
    private static void text(Canvas c,Paint p,String value,float x,float y,float size,int color,boolean bold) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        p.setTextSize(size);
        p.setTypeface(bold?Typeface.create("sans-serif-medium",Typeface.BOLD):Typeface.DEFAULT);
        p.setTextAlign(Paint.Align.LEFT);
        c.drawText(value,x,y,p);
    }
    private static String fit(String label,int max,Paint p,float size) {
        if(label==null)return "";
        String t=label.trim().replace('\n',' ');
        if(t.length()>100)t=t.substring(0,100);
        p.setTextSize(size);
        while(t.length()>2 && p.measureText(t)>max)t=t.substring(0,t.length()-2)+"…";
        return t;
    }
    private static void arrow(Canvas c,Paint p,int turn,float cx,float cy) {
        p.setStyle(Paint.Style.STROKE);
        p.setColor(GREEN);
        p.setStrokeWidth(5.5f);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        c.save();
        c.translate(cx,cy);
        Path path=new Path();
        if(turn==-1) { // TEST9 original onDraw: -1 = right
            path.moveTo(-17,24);path.lineTo(-17,-6);path.lineTo(18,-6);
            path.moveTo(6,-19);path.lineTo(18,-6);path.lineTo(6,8);
        } else if(turn==1) { // +1 = left
            path.moveTo(17,24);path.lineTo(17,-6);path.lineTo(-18,-6);
            path.moveTo(-6,-19);path.lineTo(-18,-6);path.lineTo(-6,8);
        } else if(Math.abs(turn)==2) {
            path.moveTo(14,24);path.lineTo(14,-4);
            path.cubicTo(14,-22,-18,-22,-18,-4);path.lineTo(-18,21);
            path.moveTo(-30,9);path.lineTo(-18,21);path.lineTo(-6,9);
        } else {
            path.moveTo(0,25);path.lineTo(0,-24);
            path.moveTo(-13,-11);path.lineTo(0,-24);path.lineTo(13,-11);
        }
        c.drawPath(path,p);
        c.restore();
        p.setStyle(Paint.Style.FILL);
    }
    private static String turnLabel(int turn) {
        if(turn==-1) return "Направо";
        if(turn==1) return "Налево";
        if(Math.abs(turn)==2) return "Разворот";
        return "Прямо";
    }
    private static String distance(double meters) {
        if(Double.isNaN(meters) || meters<0) return "—";
        return meters<1000 ? Math.round(meters)+" м" :
            String.format(Locale.ROOT,"%.1f км",meters/1000.0);
    }
    @SuppressWarnings("unchecked")
    private static void lanes(Canvas c,Paint p,View hud) {
        Object a=field(hud,"laneArrows");
        Object b=field(hud,"laneRecommended");
        if(!(a instanceof List))return;
        List<?> arrows=(List<?>)a;
        List<?> recommended=b instanceof List?(List<?>)b:null;
        if(arrows.isEmpty())return;
        int n=Math.min(arrows.size(),5);
        float start=170;
        for(int i=0;i<n;i++) {
            Object item=arrows.get(i);
            boolean active=recommended!=null && i<recommended.size()
                && Boolean.TRUE.equals(recommended.get(i));
            String val=item==null?"":String.valueOf(item);
            text(c,p,fit(val,25,p,16f),start+i*27,619,16,active?GREEN:MUTED,active);
        }
    }
    private static boolean miniMap(Canvas c,View hud,RectF bounds) {
        try {
            Object route=field(hud,"routePoints");
            if(!(route instanceof List) || ((List<?>)route).size()<2)return false;
            Method m=hud.getClass().getDeclaredMethod("drawMiniMap",Canvas.class,RectF.class);
            m.setAccessible(true);
            c.save();
            try { m.invoke(hud,c,bounds); } finally { c.restore(); }
            return true;
        } catch(Throwable ignored){return false;}
    }
    public static void render(Canvas out,View hud,int width,int height) {
        out.save();
        out.scale(width/(float)W,height/(float)H);
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        int speed=valueInt(hud,"speedKmh",-1);
        int limit=valueInt(hud,"speedLimitKmh",-1);
        int turn=valueInt(hud,"turn",0);
        double metres=valueDouble(hud,"distanceMeters",Double.NaN);
        String road=valueString(hud,"road");

        // No graphic or background in the top two thirds.
        text(out,p,"СКОРОСТЬ",17,480,12,MUTED,false);
        text(out,p,speed>=0 ? String.valueOf(speed) : "—",17,520,36,WHITE,true);
        text(out,p,"км/ч",84,518,13,MUTED,false);
        if(limit>0 && limit<200) {
            p.setStyle(Paint.Style.STROKE);p.setColor(GREEN);p.setStrokeWidth(2.5f);
            out.drawCircle(115,504,17,p);
            String n=String.valueOf(limit);
            p.setTextAlign(Paint.Align.CENTER);
            text(out,p,n,115-n.length()*5,510,14,GREEN,true);
        }
        // Route ETA is not exposed by the existing TEST9 NavigationView:
        // do not calculate or invent a time from the next-turn distance.
        text(out,p,"Прибытие: —",17,568,15,MUTED,false);
        String now=new SimpleDateFormat("HH:mm",Locale.getDefault()).format(new Date());
        text(out,p,"Сейчас "+now,17,606,16,GREEN,false);

        arrow(out,p,turn,202,505);
        text(out,p,distance(metres),238,503,23,WHITE,true);
        text(out,p,turnLabel(turn),235,525,15,GREEN,false);
        if(!road.isEmpty())text(out,p,fit(road,154,p,12f),166,581,12,MUTED,false);
        lanes(out,p,hud);

        boolean hasMini=miniMap(out,hud,new RectF(341,460,469,555));
        if(!hasMini) {
            p.setStyle(Paint.Style.STROKE);p.setColor(MUTED);p.setStrokeWidth(1);
            out.drawRoundRect(new RectF(341,460,469,555),5,5,p);
            text(out,p,"Карта",372,511,15,MUTED,false);
        }
        // Lower-right reserved for device-local Bluetooth/signal/battery icons,
        // drawn by Rokid receiver, not by the phone with wrong battery stats.
        out.restore();
    }
}
