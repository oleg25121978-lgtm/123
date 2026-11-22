package ru.yandex.yandexnavi.rokidhud;

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
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import java.io.DataOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.UUID;

/**
 * Same-process bridge for the pre-existing TEST9 HUD.
 * Declared as an additional independent provider. Original HudProvider stays installed,
 * keeping original navigation and onboarding lifecycle unchanged.
 */
public final class HudRelayProvider extends ContentProvider {
    private static final String LOG = "Test9HUDRelay";
    private static final String VIEW_CLASS = "ru.yandex.yandexnavi.rokidhud.HudProvider$NavigationView";
    private static final UUID UUID_SPP = UUID.fromString("5a6d41c7-8aa2-4cc4-bf87-d6c362182404");
    private static final long PERIOD_MS = 850;
    private static final int W = 480;
    private static final int H = 640;

    private volatile byte[] encoded = null;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WeakReference<Activity> activity = new WeakReference<>(null);
    private volatile boolean running = true;
    private volatile BluetoothServerSocket serverSocket;
    private volatile BluetoothSocket socket;
    private Thread serverThread;
    private boolean requestedPermission = false;
    private View mostRecentHud;

    private final Application.ActivityLifecycleCallbacks callbacks = new Application.ActivityLifecycleCallbacks() {
        @Override public void onActivityResumed(Activity a) {
            // Do nothing to the license screen and onboarding activities.
            // The Bluetooth receiver only starts after the original TEST9 HUD exists.
            activity = new WeakReference<>(a);
        }
        @Override public void onActivityPaused(Activity a) {
            if (activity.get() == a) activity = new WeakReference<>(null);
        }
        @Override public void onActivityDestroyed(Activity a) {
            if (activity.get() == a) activity = new WeakReference<>(null);
        }
        @Override public void onActivityCreated(Activity a, Bundle saved) {}
        @Override public void onActivityStarted(Activity a) {}
        @Override public void onActivityStopped(Activity a) {}
        @Override public void onActivitySaveInstanceState(Activity a, Bundle out) {}
    };
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            try { capture(); } catch (Throwable error) { Log.w(LOG,"HUD capture", error); encoded=null; }
            main.postDelayed(this, PERIOD_MS);
        }
    };

    @Override public boolean onCreate() {
        Context c=getContext();
        if(c==null || !(c.getApplicationContext() instanceof Application)) return false;
        // Independent, non-intrusive observer; TEST9's original HudProvider is untouched.
        ((Application)c.getApplicationContext()).registerActivityLifecycleCallbacks(callbacks);
        main.postDelayed(tick, 1500L);
        return true;
    }

    private void ensureBluetooth(Activity a) {
        Context c=getContext();
        if (c==null || !running) return;
        if (c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED) {
            if (!requestedPermission && android.os.Build.VERSION.SDK_INT>=31) {
                requestedPermission=true;
                a.requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 905);
            }
            return;
        }
        if (serverThread==null || !serverThread.isAlive()) {
            serverThread=new Thread(this::serve,"test9-hud-spp");
            serverThread.start();
        }
    }

    private static View locate(View candidate) {
        if (candidate==null) return null;
        if (VIEW_CLASS.equals(candidate.getClass().getName())) return candidate;
        if (candidate instanceof ViewGroup) {
            ViewGroup p=(ViewGroup)candidate;
            for(int i=0;i<p.getChildCount();i++) {
                View found=locate(p.getChildAt(i));
                if(found!=null) return found;
            }
        }
        return null;
    }

    private void capture() {
        Activity a=activity.get();
        if(a==null) { encoded=null; return; }
        Window w=a.getWindow();
        if(w==null) {encoded=null;return;}
        View decor=w.getDecorView();
        View hud=locate(decor);
        if (hud==null || hud.getWidth()<=0 || hud.getHeight()<=0) {encoded=null;return;}
        // Bluetooth permission is requested ONLY once the real navigation HUD exists.
        ensureBluetooth(a);
        if (hud != mostRecentHud) { mostRecentHud=hud;Log.i(LOG,"TEST9 NavigationView found"); }
        // Keep HUD active and receiving original Yandex route updates,
        // but move only this overlay beyond phone's viewable area.
        hud.setTranslationX(Math.max(decor.getWidth()*2f,2000f));
        if (hud.getVisibility()!=View.VISIBLE) {encoded=null;return;}
        Bitmap bitmap=Bitmap.createBitmap(W,H,Bitmap.Config.ARGB_8888);
        try {
            Canvas c=new Canvas(bitmap);
            c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            c.save();
            c.scale(W/(float)hud.getWidth(),H/(float)hud.getHeight());
            hud.draw(c); // redraw ONLY this HUD view, not the Yandex phone map
            c.restore();
            ByteArrayOutputStream bytes=new ByteArrayOutputStream(28000);
            if(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)) {
                byte[] frame=bytes.toByteArray();
                encoded=frame.length<=300000?frame:null;
            } else encoded=null;
        } finally { bitmap.recycle(); }
    }

    @SuppressWarnings("MissingPermission")
    private void serve() {
        Context c=getContext();
        if(c==null) return;
        BluetoothServerSocket listener=null;
        try {
            BluetoothManager manager=c.getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
            if(adapter==null || !adapter.isEnabled()) {Log.w(LOG,"Bluetooth unavailable");return;}
            listener=adapter.listenUsingRfcommWithServiceRecord("TEST9 Rokid HUD",UUID_SPP);
            serverSocket=listener;
            while(running) {
                BluetoothSocket s=null;
                try {
                    s=listener.accept();
                    socket=s;
                    Log.i(LOG,"Rokid connected");
                    DataOutputStream out=new DataOutputStream(s.getOutputStream());
                    while(running && s.isConnected()) {
                        byte[] data=encoded;
                        out.writeInt(0x48554431); // HUD1
                        out.writeInt(data==null?0:data.length);
                        if(data!=null)out.write(data);
                        out.flush();
                        Thread.sleep(PERIOD_MS);
                    }
                } catch (Exception problem) {
                    if(running)Log.w(LOG,"Client disconnected",problem);
                } finally {
                    socket=null;
                    if(s!=null)try{s.close();}catch(Exception ignored){}
                }
            }
        } catch(Throwable problem) {Log.e(LOG,"Bluetooth server failed",problem);}
        finally {
            serverSocket=null;
            if(listener!=null)try{listener.close();}catch(Exception ignored){}
        }
    }

    // This is a separate private provider. The original TEST9 provider keeps its own
    // ContentProvider contract and ActivityLifecycleCallbacks unchanged.
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order) {return null;}
    @Override public String getType(Uri uri) {return null;}
    @Override public Uri insert(Uri uri,ContentValues vals) {return null;}
    @Override public int delete(Uri uri,String where,String[] args) {return 0;}
    @Override public int update(Uri uri,ContentValues vals,String where,String[] args) {return 0;}
}
