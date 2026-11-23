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
    private static final UUID SPP=UUID.fromString("5a6d41c7-8aa2-4cc4-bf87-d6c362182404");
    private static final long PERIOD=1000L;
    private static final int W=480,H=640;
    private static final int WHITE=Color.rgb(230,255,232);
    private static final int GREEN=Color.rgb(105,247,133);
    private static final int DIM=Color.rgb(95,158,114);
    private final Handler handler=new Handler(Looper.getMainLooper());
    private volatile byte[] frame;
    private volatile boolean running=true;
    private WeakReference<Activity> activity=new WeakReference<>(null);
    private boolean foreground=false, permissionAsked=false;
    private int detected=0;
    private volatile BluetoothSocket client;
    private Thread serverThread;
    private volatile BluetoothServerSocket listener;

    private final Application.ActivityLifecycleCallbacks callbacks=new Application.ActivityLifecycleCallbacks(){
        @Override public void onActivityResumed(Activity a){
            activity=new WeakReference<>(a);
            foreground=true;
            detected=0;
        }
        @Override public void onActivityPaused(Activity a){
            if(activity.get()==a)foreground=false;
        }
        @Override public void onActivityDestroyed(Activity a){
            if(activity.get()==a){
                activity=new WeakReference<>(null);
                frame=null; detected=0; foreground=false;
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
            catch(Throwable e){ Log.w(TAG,"sample: skip frame",e); frame=null; }
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
        View eta,maneuver,speed;
    }
    private static void find(View v,Widgets w,int depth){
        if(v==null || depth>70 || v.getVisibility()!=View.VISIBLE)return;
        String cls=v.getClass().getName();
        if(cls.endsWith(".ContextEtaView"))w.eta=v;
        if(cls.endsWith(".ContextManeuverView"))w.maneuver=v;
        if(cls.endsWith(".SpeedViewImpl"))w.speed=v;
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
            if(Pattern.matches(".*\\b(?:[01]?\\d|2[0-3]):[0-5]\\d\\b.*",t))
                return t;
        }
        return null;
    }
    private static String speed(View view){
        if(view==null)return null;
        ArrayList<String> s=new ArrayList<>();
        texts(view,s,0);
        for(String t:s){
            if(Pattern.matches("\\d{1,3}",t))return t;
        }
        return null;
    }
    private static void txt(Canvas c,Paint p,String s,float x,float y,int size,int color){
        if(s==null||s.isEmpty())return;
        p.setColor(color);
        p.setStyle(Paint.Style.FILL);
        p.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));
        p.setTextSize(size);
        c.drawText(s,x,y,p);
    }
    private static void drawNativeWidget(Canvas c,View source,Rect dest){
        if(source==null || source.getWidth()<1 || source.getHeight()<1)return;
        c.save();
        c.clipRect(dest);
        c.translate(dest.left,dest.top);
        float scale=Math.min(dest.width()/(float)source.getWidth(),
                             dest.height()/(float)source.getHeight());
        c.scale(scale,scale);
        source.draw(c);
        c.restore();
    }
    private static Bitmap render(Widgets w){
        Bitmap bitmap=Bitmap.createBitmap(W,H,Bitmap.Config.ARGB_8888);
        Canvas c=new Canvas(bitmap);
        c.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR);
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        // Whole upper field is empty: no black background, no screen mirroring.
        txt(c,p,"СКОРОСТЬ",16,526,11,DIM);
        txt(c,p,speed(w.speed),16,556,28,WHITE);
        String eta=arrival(w.eta);
        // No "calculated" placeholders or guessed arrival.
        if(eta!=null)txt(c,p,"ETA "+eta,16,604,13,GREEN);
        // Native Yandex arrow, distance and lanes are captured from just
        // ContextManeuverView, never from the phone map or menu.
        drawNativeWidget(c,w.maneuver,new Rect(145,493,342,609));
        // The true map route geometry is not exposed by these Views; no fake map.
        // System status and time remain exclusively on the glasses receiver.
        return bitmap;
    }
    private void sample(){
        Activity a=activity.get();
        if(a==null||a.isDestroyed()||a.isFinishing()){
            frame=null;detected=0;return;
        }
        View root=a.getWindow()==null?null:a.getWindow().getDecorView();
        if(root==null){frame=null;detected=0;return;}
        Widgets w=new Widgets();
        find(root,w,0);
        // Do nothing on license, onboarding, login or route selection.
        if(w.maneuver==null || w.maneuver.getWidth()==0 || w.maneuver.getHeight()==0
           || !w.maneuver.isShown()){
            frame=null;detected=0;return;
        }
        if(++detected<3)return;
        if(foreground)ensureBluetooth(a);
        Bitmap bmp=render(w);
        try{
            ByteArrayOutputStream stream=new ByteArrayOutputStream(25000);
            if(bmp.compress(Bitmap.CompressFormat.PNG,100,stream)){
                byte[] data=stream.toByteArray();
                frame=data.length<=300000?data:null;
            }else frame=null;
        }finally{bmp.recycle();}
        if(detected==3){
            Log.i(TAG,"Navigation widgets: maneuver="+w.maneuver.getClass().getName()
              +" eta="+(w.eta!=null)+" speed="+(w.speed!=null)
              +" confirmedETA="+arrival(w.eta));
        }
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
