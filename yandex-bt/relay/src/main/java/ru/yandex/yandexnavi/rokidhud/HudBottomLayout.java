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
        p.setStrokeWidth(4.4f);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        c.save();
        c.translate(cx,cy);
        // Route arrow ~30% smaller, positioned lower; leave lane arrows larger.
        c.scale(0.72f,0.72f);
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
        float start=171;
        for(int i=0;i<n;i++) {
            Object item=arrows.get(i);
            boolean active=recommended!=null && i<recommended.size()
                && Boolean.TRUE.equals(recommended.get(i));
            String val=item==null?"":String.valueOf(item);
            text(c,p,fit(val,30,p,18f),start+i*31,610,18,active?GREEN:MUTED,active);
        }
    }
    private static boolean miniMap(Canvas c,View hud,RectF bounds) {
        try {
            Object route=field(hud,"routePoints");
            if(!(route instanceof List) || ((List<?>)route).size()<2)return false;
            Method m=hud.getClass().getDeclaredMethod("drawMiniMap",Canvas.class,RectF.class);
            m.setAccessible(true);
            // drawMiniMap uses full-size fixed pixel strokes/vehicle markers.
            // Merely shrinking RectF does NOT shrink those thicknesses.
            // Supply a 7x virtual viewport, then scale every vector/marker by 1/7
            // within the same 125x111 display box. Clip prevents road spillover.
            final float shrink=7f;
            c.save();
            try {
                c.clipRect(bounds);
                c.translate(bounds.left,bounds.top);
                c.scale(1f/shrink,1f/shrink);
                RectF virtualBounds=new RectF(
                    0f,0f,bounds.width()*shrink,bounds.height()*shrink);
                m.invoke(hud,c,virtualBounds);
            } finally { c.restore(); }
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

        // Bottom HUD, no opaque panels. Smaller regular weight for speed.
        // Time on the bottom line comes from Rokid itself (receiver app).
        text(out,p,"СКОРОСТЬ",14,516,10,MUTED,false);
        text(out,p,speed>=0 ? String.valueOf(speed) : "—",14,547,26,WHITE,false);
        text(out,p,"км/ч",71,545,11,MUTED,false);
        if(limit>0 && limit<200) {
            p.setStyle(Paint.Style.STROKE);p.setColor(GREEN);p.setStrokeWidth(1.6f);
            out.drawCircle(123,536,13,p);
            String n=String.valueOf(limit);
            text(out,p,n,123-n.length()*4.1f,541,12,GREEN,false);
        }
        // Only Yandex-confirmed ETA is acceptable; this data is not wired yet.
        text(out,p,"ETA —",14,596,13,MUTED,false);

        // Maneuver is smaller, lower and visually secondary to real road view.
        arrow(out,p,turn,193,545);
        text(out,p,distance(metres),219,542,17,WHITE,false);
        text(out,p,turnLabel(turn),220,563,12,GREEN,false);
        if(!road.isEmpty()) {
            text(out,p,fit(road,166,p,11f),166,585,11,MUTED,false);
        }
        lanes(out,p,hud);

        boolean hasMini=miniMap(out,hud,new RectF(345,461,470,572));
        if(!hasMini) {
            p.setStyle(Paint.Style.STROKE);p.setColor(MUTED);p.setStrokeWidth(1);
            // No fictitious map: a subtle outline only while route geometry is absent.
            out.drawRoundRect(new RectF(346,462,468,572),6,6,p);
        }
        // Lower-right y590..636 reserved for real glasses Bluetooth/Wi-Fi/battery icons,
        // drawn by Rokid receiver, not by the phone with wrong battery stats.
        out.restore();
    }
}
