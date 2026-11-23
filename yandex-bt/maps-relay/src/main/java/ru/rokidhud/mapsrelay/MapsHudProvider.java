package ru.rokidhud.mapsrelay;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.os.Build;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Diagnostic Maps 31.1.5 -> Rokid bridge, independent of Navigator TEST9.
 *
 * No hook into route computation: only original visible Yandex guidance Views.
 * No screen mirroring: paints the three relevant widgets on an alpha HUD.
 * ETA is read from the original ContextEtaView text; otherwise omitted.
 *
 * This is a PROBE: the actual view hierarchy and activity lifecycle must
 * be checked on the user's Mate X3. Does not access private login data.
 */
public final class MapsHudProvider extends ContentProvider {
    private static final String TAG="YMaps31HUDProbe";
    private static final UUID SPP=UUID.fromString("6c8c5612-483f-4d21-94e9-0315d3d9e615");
    private static final long PERIOD=850L; // tested TEST9 Bluetooth framing cadence
    private static final int W=480,H=640;
    private static final int WHITE=Color.rgb(230,255,232);
    private static final int GREEN=Color.rgb(105,247,133);
    private static final int DIM=Color.rgb(95,158,114);
    private final Handler handler=new Handler(Looper.getMainLooper());
    private volatile byte[] frame;
    private volatile byte[] lastGoodFrame;
    private byte[] lastNativeFrame; // compare UI to UI, NOT to notification PNG
    private long lastGoodFrameAt;
    private String lastNotificationIdentity;
    private long lastNotificationAt;
    private final MapsHudMiniMap miniMap=new MapsHudMiniMap();
    private boolean keepAliveStarted=false;
    private int idleTicks=0;
    private static final long CACHED_FRAME_GRACE_MS=15000L;
    private volatile boolean running=true;
    private WeakReference<Activity> activity=new WeakReference<>(null);
    private boolean foreground=false, permissionAsked=false;
    private int navigationFrames=0;
    private boolean routeArmed=false;
    private View mostRecentManeuver;
    private Widgets lastLiveWidgets; // current route's verified original Android Views
    private long lastActiveNavigationMs;
    private volatile BluetoothSocket client;
    private Thread serverThread;
    private volatile BluetoothServerSocket listener;

    private final Application.ActivityLifecycleCallbacks callbacks=new Application.ActivityLifecycleCallbacks(){
        @Override public void onActivityResumed(Activity a){
            boolean different=activity.get()!=a;
            activity=new WeakReference<>(a);
            foreground=true;
            if(different){
                navigationFrames=0;
                routeArmed=false;
                mostRecentManeuver=null;
                lastLiveWidgets=null;
            }
        }
        @Override public void onActivityPaused(Activity a){
            if(activity.get()!=a)return;
            foreground=false;
            // TEST9 rule: pausing an Activity is NOT equivalent to cancelling
            // active navigation. Preserve the actual guidance Views and sender.
            // A running foreground service keeps the Bluetooth process alive.
        }
        @Override public void onActivityDestroyed(Activity a){
            if(activity.get()==a){
                activity=new WeakReference<>(null);
                foreground=false;
                lastLiveWidgets=null;
                mostRecentManeuver=null;
                navigationFrames=0;
                // Android may destroy the UI while its guidance service runs.
                // Retain only a SHORT last-good-frame grace; do not label a
                // permanently frozen maneuver as live navigation.
                frame=recentFrame();
            }
        }
        @Override public void onActivityCreated(Activity a,Bundle saved){}
        @Override public void onActivityStarted(Activity a){}
        @Override public void onActivityStopped(Activity a){}
        @Override public void onActivitySaveInstanceState(Activity a,Bundle state){}
    };
    private final Runnable sampler=new Runnable(){
        @Override public void run(){
            if(!running)return;
            try { sample(); }
            catch(Throwable e){ Log.w(TAG,"sample: skip frame",e); frame=recentFrame(); }
            handler.postDelayed(this,PERIOD);
        }
    };

    @Override public boolean onCreate(){
        Context ctx=getContext();
        if(ctx==null || !(ctx.getApplicationContext() instanceof Application))return false;
        ((Application)ctx.getApplicationContext()).registerActivityLifecycleCallbacks(callbacks);
        // No Bluetooth or UI work during license agreement or first-run onboarding.
        handler.postDelayed(sampler,2800L);
        return true;
    }

    private static final class Widgets{
        View eta,maneuver,speed,lanes,map;
    }
    private static void find(View v,Widgets w,int depth){
        if(v==null || depth>70 || v.getVisibility()!=View.VISIBLE)return;
        String cls=v.getClass().getName();
        if(cls.endsWith(".ContextEtaView"))w.eta=v;
        if(cls.endsWith(".ContextManeuverView"))w.maneuver=v;
        if(cls.endsWith(".SpeedViewImpl"))w.speed=v;
        // Only actual native lane widgets; absent lanes are never synthesized.
        String lower=cls.toLowerCase(Locale.ROOT);
        if(w.lanes==null && lower.contains("lane") &&
           (lower.endsWith("view") || lower.contains("lanesign") || lower.contains("lanearrow")) &&
           v.getWidth()>5 && v.getHeight()>5)w.lanes=v;
        // MapKit's real map View, not the complete phone Activity or mirrored screen.
        if(w.map==null &&
           (cls.equals("com.yandex.mapkit.mapview.MapView") ||
           cls.equals("com.yandex.mapkit.MapView")))w.map=v;
        if(v instanceof ViewGroup){
            ViewGroup group=(ViewGroup)v;
            for(int i=0;i<group.getChildCount();i++)find(group.getChildAt(i),w,depth+1);
        }
    }
    private static void texts(View view, ArrayList<String> out,int depth){
        if(view==null || depth>35 || view.getVisibility()!=View.VISIBLE)return;
        if(view instanceof TextView){
            CharSequence text=((TextView)view).getText();
            if(text!=null){
                String clean=text.toString().trim().replace('\n',' ');
                if(!clean.isEmpty()&&clean.length()<=80)out.add(clean);
            }
        }
        if(view instanceof ViewGroup){
            ViewGroup v=(ViewGroup)view;
            for(int i=0;i<v.getChildCount() && out.size()<80;i++)
                texts(v.getChildAt(i),out,depth+1);
        }
    }
    private static String join(ArrayList<String> texts){
        StringBuilder s=new StringBuilder();
        for(String a:texts){
            if(s.length()>0)s.append("  ");
            if(s.length()+a.length()>120)break;
            s.append(a);
        }
        return s.toString();
    }
    private static String arrival(View eta){
        if(eta==null)return null;
        ArrayList<String> strings=new ArrayList<>();
        texts(eta,strings,0);
        // Return exactly what the Yandex ETA widget already displays.
        // Match a clock time, never infer it from the distance to the turn.
        for(String t:strings){
            java.util.regex.Matcher match=Pattern.compile(
                "(?<!\\d)(?:[01]?\\d|2[0-3]):[0-5]\\d(?!\\d)").matcher(t);
            if(match.find())return match.group();
        }
        return null;
    }
    private static String speed(View view){
        if(view==null)return null;
        // NaviKit's real current-speed TextView: don't pick a speed-limit label.
        try {
            java.lang.reflect.Method getter=view.getClass().getMethod("getTextSpeedValue");
            Object result=getter.invoke(view);
            if(result instanceof TextView){
                CharSequence raw=((TextView)result).getText();
                if(raw!=null){
                    String n=raw.toString().trim();
                    if(Pattern.matches("\\d{1,3}",n))return n;
                }
            }
        }catch(Throwable error){
            Log.d(TAG,"Current speed TextView unavailable",error);
        }
        return null; // A missing speed is not a reason to invent one.
    }
    private static boolean actualManeuver(View view,boolean requireWindowShown){
        if(view==null||view.getWidth()<1||view.getHeight()<1)
            return false;
        if(requireWindowShown && !view.isShown())return false;
        try {
            java.lang.reflect.Method getter=view.getClass().getMethod("getCanBeVisible");
            Object yes=getter.invoke(view);
            if(yes instanceof Boolean && !((Boolean)yes))return false;
        }catch(Throwable ignored) {
            // Some versions may remove the method; shown View is minimum evidence.
        }
        return true;
    }
    private static void txt(Canvas c,Paint p,String s,float x,float y,int size,int color){
        if(s==null||s.isEmpty())return;
        p.setColor(color);
        p.setStyle(Paint.Style.FILL);
        p.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));
        p.setTextSize(size);
        c.drawText(s,x,y,p);
    }
    /**
     * Preserve the *actual* native Yandex turn arrow and distance, without
     * drawing its opaque rounded phone panel on transparent optical glasses.
     * Capture ONLY this native widget, never the Yandex map/screen.
     *
     * Do not mutate backgrounds on live Android views: that would cause
     * layout/visual changes on the Huawei display. Instead remove the
     * dominant opaque panel paint on a private small ARGB_8888 bitmap.
     */
    private static void clearPanelBackground(Bitmap image) {
        int width=image.getWidth(), height=image.getHeight();
        if(width<5 || height<5)return;
        int[] pixels=new int[width*height];
        image.getPixels(pixels,0,width,0,0,width,height);
        int[] count=new int[32768],rs=new int[32768],gs=new int[32768],bs=new int[32768];
        int valid=0,best=0,bucket=-1;
        for(int color:pixels) {
            int alpha=Color.alpha(color);
            if(alpha<240)continue;
            valid++;
            int r=Color.red(color),g=Color.green(color),b=Color.blue(color);
            int key=((r>>3)<<10)|((g>>3)<<5)|(b>>3);
            count[key]++;rs[key]+=r;gs[key]+=g;bs[key]+=b;
            if(count[key]>best){best=count[key];bucket=key;}
        }
        // Never strip actual arrow glyphs from a transparent icon: require a
        // panel-size dominant color, not merely the most frequent icon stroke.
        if(bucket<0 || best<width*height*0.18f || best<valid*0.42f)return;
        int br=rs[bucket]/best,bg=gs[bucket]/best,bb=bs[bucket]/best;
        for(int i=0;i<pixels.length;i++) {
            int color=pixels[i],alpha=Color.alpha(color);
            if(alpha==0)continue;
            int dr=Math.abs(Color.red(color)-br);
            int dg=Math.abs(Color.green(color)-bg);
            int db=Math.abs(Color.blue(color)-bb);
            int diff=Math.max(dr,Math.max(dg,db));
            if(diff<=31) {
                pixels[i]=Color.TRANSPARENT;
            }else if(diff<55) {
                int adjusted=Math.round(alpha*(diff-31f)/24f);
                pixels[i]=(color&0x00FFFFFF)|(adjusted<<24);
            }
        }
        image.setPixels(pixels,0,width,0,0,width,height);
    }
    private static void drawNativeWidget(Canvas c,View source,Rect dest){
        if(source==null || source.getWidth()<1 || source.getHeight()<1)return;
        int dw=dest.width(),dh=dest.height();
        if(dw<=0||dh<=0)return;
        Bitmap isolated=Bitmap.createBitmap(dw,dh,Bitmap.Config.ARGB_8888);
        try {
            Canvas sub=new Canvas(isolated);
            sub.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR);
            float scale=Math.min(dw/(float)source.getWidth(),
                                 dh/(float)source.getHeight());
            sub.save();
            sub.scale(scale,scale);
            source.draw(sub);
            sub.restore();
            clearPanelBackground(isolated);
            Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
            c.drawBitmap(isolated,dest.left,dest.top,p);
        }finally{isolated.recycle();}
    }
    /**
     * Experimental read-only minimap. Sample an actual cropped portion of
     * Yandex's MapKit map view at ~1:7 scale. SurfaceView-based map renderers
     * may not provide pixels to View.draw(); in that case do not invent roads.
     */
    private static void drawRealMapThumbnail(Canvas out, View map, Rect dst) {
        if(map==null || map.getWidth()<20 || map.getHeight()<20)return;
        int width=dst.width(),height=dst.height();
        if(width<=0||height<=0)return;
        Bitmap thumbnail=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        try{
            Canvas sub=new Canvas(thumbnail);
            sub.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR);
            // Scale both map roads and live vehicle dot; not only the box.
            float contentWidth=Math.min(map.getWidth(),width*7f);
            float contentHeight=Math.min(map.getHeight(),height*7f);
            float scale=Math.min(width/contentWidth,height/contentHeight);
            float centerX=map.getWidth()*0.5f;
            float centerY=map.getHeight()*0.64f; // map's typical driving focus
            sub.save();
            sub.scale(scale,scale);
            sub.translate(-centerX+width/(2f*scale),
                          -centerY+height/(2f*scale));
            map.draw(sub);
            sub.restore();
            // SurfaceView-backed maps can render as a single blank color.
            // Reject those rather than presenting a fictitious mini-map.
            int varied=0,first=0;
            boolean hasFirst=false;
            for(int y=0;y<height;y+=7) {
                for(int x=0;x<width;x+=7) {
                    int pixel=thumbnail.getPixel(x,y);
                    if(Color.alpha(pixel)<10)continue;
                    int key=(pixel&0x00F0F0F0);
                    if(!hasFirst){first=key;hasFirst=true;}
                    else if(first!=key)varied++;
                }
            }
            if(varied<12)return;
            Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
            out.drawBitmap(thumbnail,dst.left,dst.top,p);
        }catch(Throwable problem){
            Log.d(TAG,"MapKit View does not support thumbnail drawing",problem);
        }finally{thumbnail.recycle();}
    }

    private Bitmap render(Widgets w){
        Bitmap bitmap=Bitmap.createBitmap(W,H,Bitmap.Config.ARGB_8888);
        Canvas c=new Canvas(bitmap);
        c.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR);
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        // Whole upper field is empty: no black background, no screen mirroring.
        txt(c,p,"СКОРОСТЬ",14,516,10,DIM);
        txt(c,p,speed(w.speed),14,548,26,WHITE);
        String eta=arrival(w.eta);
        // No "calculated" placeholders or guessed arrival.
        if(eta!=null)txt(c,p,"ETA "+eta,14,605,13,GREEN);
        // Transparent smaller native Yandex arrow + distance, from just
        // ContextManeuverView, never from the phone map or menu.
        drawNativeWidget(c,w.maneuver,new Rect(163,527,301,610));
        // Native actual lanes are separated, bigger and below the turn arrow.
        if(w.lanes!=null && w.lanes!=w.maneuver)
            drawNativeWidget(c,w.lanes,new Rect(157,580,341,625));
        // Real MapKit GPU pixels: PixelCopy/TextureView instead of View.draw.
        // Both roads and user marker pixels are resampled from a 7x crop.
        miniMap.draw(c,new Rect(347,468,472,579));
        // Neither geometry nor lane arrows are fabricated when unavailable.
        // System status and time remain exclusively on the glasses receiver.
        return bitmap;
    }
    private void sample(){
        Activity a=activity.get();
        if(a==null||a.isDestroyed()||a.isFinishing()){
            frame=backgroundFrame();return;
        }
        View root=a.getWindow()==null?null:a.getWindow().getDecorView();
        if(root==null){frame=backgroundFrame();return;}
        Widgets w=new Widgets();
        find(root,w,0);
        // Once a real route has been confirmed, retain *those same* native
        // guidance Views across Activity.onPause(). In Android the window may
        // be hidden while the underlying Navigation model continues updating.
        // Do not read any other app's Views or switch to a fake route.
        if(foreground){
            // Look specifically for native lane glyphs, including lane groups
            // embedded inside ContextManeuverView.
            w.lanes=MapsHudLanes.locate(w.maneuver,root);
        }
        if(!foreground && routeArmed && lastLiveWidgets!=null){
            if(w.maneuver==null)w.maneuver=lastLiveWidgets.maneuver;
            if(w.speed==null)w.speed=lastLiveWidgets.speed;
            if(w.eta==null)w.eta=lastLiveWidgets.eta;
            if(w.lanes==null)w.lanes=lastLiveWidgets.lanes;
            if(w.map==null)w.map=lastLiveWidgets.map;
        }
        if(!actualManeuver(w.maneuver,foreground)){
            if(foreground){
                // Explicit foreground return without a maneuver: route stopped
                // or navigation UI dismissed. Clear the cached maneuver.
                clearRoute();
            }else{
                frame=backgroundFrame();
                idleTicks++;
                if(idleTicks>35 && frame==null) stopKeepAlive();
            }
            return;
        }
        // Ported from TEST9: never arm on onboarding, stale view or route selection.
        if(w.maneuver!=mostRecentManeuver) {
            mostRecentManeuver=w.maneuver;
            navigationFrames=0;
            routeArmed=false;
        }
        if(!routeArmed){
            if(++navigationFrames<3)return;
            routeArmed=true;
            Log.i(TAG,"TEST9-style route gate armed for live Maps guidance widget");
        }
        lastActiveNavigationMs=System.currentTimeMillis();
        idleTicks=0;
        if(foreground) {
            lastLiveWidgets=w;
            ensureBluetooth(a);
            startKeepAlive(a); // Must be called BEFORE Activity.onPause().
            miniMap.sample(w.map);
        }
        Bitmap bmp=render(w);
        try{
            ByteArrayOutputStream stream=new ByteArrayOutputStream(25000);
            if(bmp.compress(Bitmap.CompressFormat.PNG,100,stream)){
                byte[] data=stream.toByteArray();
                if(data.length<=300000) {
                    // IMPORTANT: a paused Android window can keep a frozen
                    // View tree while the navigation session itself advances.
                    // Re-encoding the SAME pixels is not a fresh route update.
                    boolean updated=foreground ||
                        lastNativeFrame==null ||
                        !java.util.Arrays.equals(data,lastNativeFrame);
                    if(updated){
                        frame=data;
                        lastNativeFrame=data;
                        lastGoodFrame=data;
                        lastGoodFrameAt=System.currentTimeMillis();
                        if(!foreground)Log.i(TAG,"Background native guidance frame changed");
                    }else{
                        // If the view is frozen, a genuinely new navigation
                        // notification can still supply the next maneuver.
                        frame=backgroundFrame();
                    }
                }else frame=backgroundFrame();
            }else frame=backgroundFrame();
        }finally{bmp.recycle();}
        if(navigationFrames==3){
            navigationFrames=4;
            Log.i(TAG,"Navigation widgets: maneuver="+w.maneuver.getClass().getName()
              +" eta="+(w.eta!=null)+" speed="+(w.speed!=null)
              +" confirmedETA="+arrival(w.eta));
        }
    }
    private void drawNotificationArrow(Canvas c,Paint p,int turn,int ox,int oy){
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(GREEN);
        p.setStrokeWidth(3.5f);
        android.graphics.Path shape=new android.graphics.Path();
        c.save();
        c.translate(ox,oy);
        if(turn==-1){
            shape.moveTo(-15,23);shape.lineTo(-15,-5);shape.lineTo(16,-5);
            shape.moveTo(5,-16);shape.lineTo(16,-5);shape.lineTo(5,6);
        }else if(turn==1){
            shape.moveTo(15,23);shape.lineTo(15,-5);shape.lineTo(-16,-5);
            shape.moveTo(-5,-16);shape.lineTo(-16,-5);shape.lineTo(-5,6);
        }else if(turn==2){
            shape.moveTo(13,23);shape.lineTo(13,-2);
            shape.cubicTo(13,-24,-14,-24,-14,-2);
            shape.lineTo(-14,18);
            shape.moveTo(-24,8);shape.lineTo(-14,18);shape.lineTo(-4,8);
        }
        c.drawPath(shape,p);
        c.restore();
        p.setStyle(Paint.Style.FILL);
    }
    private byte[] drawActualNotification(MapsHudNotificationSource.Snapshot n) {
        Bitmap b=Bitmap.createBitmap(W,H,Bitmap.Config.ARGB_8888);
        try{
            Canvas c=new Canvas(b);
            c.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR);
            Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
            // No fake speed or lanes in notification mode. Real ETA only when
            // explicitly labelled by Yandex, real turn/distance from its text.
            if(n.arrival!=null)txt(c,p,"ETA "+n.arrival,14,605,13,GREEN);
            if(n.turn!=0)drawNotificationArrow(c,p,n.turn,192,548);
            if(n.turnDistance!=null)txt(c,p,n.turnDistance,221,554,17,WHITE);
            miniMap.draw(c,new Rect(347,468,472,579)); // real cached map only
            ByteArrayOutputStream stream=new ByteArrayOutputStream(18000);
            return b.compress(Bitmap.CompressFormat.PNG,100,stream)
                && stream.size()<=300000 ? stream.toByteArray():null;
        }finally{b.recycle();}
    }
    private byte[] backgroundFrame(){
        if(!routeArmed)return null;
        Context ctx=getContext();
        if(ctx!=null){
            MapsHudNotificationSource.Snapshot n=MapsHudNotificationSource.read(ctx);
            if(n!=null && n.hasManeuver()){
                // A posted notification can remain unchanged forever after
                // Yandex stopped publishing guidance. Its presence alone is
                // NOT evidence of a live route.
                String identity=n.originalTitle+"\u0001"+n.originalText+
                    "\u0001"+n.turn+"\u0001"+n.turnDistance+"\u0001"+n.arrival;
                if(!identity.equals(lastNotificationIdentity)){
                    lastNotificationIdentity=identity;
                    lastNotificationAt=System.currentTimeMillis();
                    byte[] result=drawActualNotification(n);
                    if(result!=null){
                        lastGoodFrame=result;
                        lastGoodFrameAt=lastNotificationAt;
                        Log.i(TAG,"Fresh Yandex guidance notification received");
                        return result;
                    }
                }
            }
        }
        return recentFrame();
    }

    /** Last confirmed navigation frame; cannot be reused indefinitely. */
    private byte[] recentFrame() {
        if(!routeArmed || lastGoodFrame==null)return null;
        if(System.currentTimeMillis()-lastGoodFrameAt>CACHED_FRAME_GRACE_MS)return null;
        return lastGoodFrame;
    }
    private void clearRoute(){
        routeArmed=false;
        navigationFrames=0;
        mostRecentManeuver=null;
        lastLiveWidgets=null;
        frame=null;
        lastGoodFrame=null;
        lastNativeFrame=null;
        lastGoodFrameAt=0;
        lastNotificationAt=0;
        lastNotificationIdentity=null;
        miniMap.clear();
        stopKeepAlive();
    }
    private void startKeepAlive(Activity a){
        if(keepAliveStarted||!foreground||!routeArmed)return;
        try{
            Intent intent=new Intent(a,MapsHudKeepAliveService.class);
            if(Build.VERSION.SDK_INT>=26)a.startForegroundService(intent);
            else a.startService(intent);
            keepAliveStarted=true;
            Log.i(TAG,"Maps31 foreground HUD keepalive started");
        }catch(Throwable ex){
            Log.w(TAG,"Foreground HUD keepalive unavailable: check injected service declaration",ex);
        }
    }
    private void stopKeepAlive(){
        if(!keepAliveStarted)return;
        Context ctx=getContext();
        if(ctx!=null){
            try{ctx.stopService(new Intent(ctx,MapsHudKeepAliveService.class));}
            catch(Throwable ex){Log.w(TAG,"stopKeepAlive",ex);}
        }
        keepAliveStarted=false;
        idleTicks=0;
    }

    private void ensureBluetooth(Activity a){
        Context ctx=getContext();
        if(ctx==null)return;
        if(android.os.Build.VERSION.SDK_INT>=31 &&
           ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
           !=PackageManager.PERMISSION_GRANTED){
            if(foreground && !permissionAsked){
                permissionAsked=true;
                a.requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT},993);
            }
            return;
        }
        if(serverThread==null || !serverThread.isAlive()){
            serverThread=new Thread(this::serve,"YMaps31-HUD-BT");
            serverThread.start();
        }
    }
    @SuppressWarnings("MissingPermission")
    private void serve(){
        Context ctx=getContext();
        if(ctx==null)return;
        BluetoothServerSocket server=null;
        try{
            BluetoothManager manager=ctx.getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
            if(adapter==null||!adapter.isEnabled())return;
            server=adapter.listenUsingRfcommWithServiceRecord("Yandex Maps 31 Rokid HUD",SPP);
            listener=server;
            while(running){
                BluetoothSocket s=null;
                try{
                    s=server.accept();
                    client=s;
                    Log.i(TAG,"Rokid HUD connected");
                    DataOutputStream output=new DataOutputStream(s.getOutputStream());
                    while(running && s.isConnected()){
                        byte[] packet=frame;
                        output.writeInt(0x48554431);
                        output.writeInt(packet==null?0:packet.length);
                        if(packet!=null)output.write(packet);
                        output.flush();
                        Thread.sleep(PERIOD);
                    }
                }catch(Exception problem){
                    if(running)Log.w(TAG,"Bluetooth receiver unavailable",problem);
                }finally{
                    client=null;
                    if(s!=null)try{s.close();}catch(IOException ignored){}
                }
            }
        }catch(Throwable e){Log.e(TAG,"Bluetooth server",e);}
        finally{
            listener=null;
            if(server!=null)try{server.close();}catch(IOException ignored){}
        }
    }

    @Override public Cursor query(Uri u,String[] p,String s,String[] a,String order){return null;}
    @Override public String getType(Uri u){return null;}
    @Override public Uri insert(Uri u,ContentValues v){return null;}
    @Override public int delete(Uri u,String s,String[] a){return 0;}
    @Override public int update(Uri u,ContentValues v,String s,String[] a){return 0;}
}
