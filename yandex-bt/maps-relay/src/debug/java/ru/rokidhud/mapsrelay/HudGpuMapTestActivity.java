package ru.rokidhud.mapsrelay;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DEBUG-ONLY emulator integration test of PixelCopy on a real SurfaceView.
 * Its streets are deliberately SYNTHETIC; no actual Yandex MapKit is involved.
 */
public final class HudGpuMapTestActivity extends Activity {
    private static final String TAG="MapGPUProbe";
    private final Handler main=new Handler(Looper.getMainLooper());
    private final MapsHudMiniMap sampler=new MapsHudMiniMap();
    private final AtomicBoolean finished=new AtomicBoolean(false);
    private SurfaceView surface;
    private int tick=0;
    private void drawRoads(SurfaceHolder holder) {
        Canvas c=null;
        try {
            c=holder.lockCanvas();
            if(c==null)return;
            c.drawColor(Color.rgb(20,34,26));
            Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE);
            p.setColor(Color.rgb(90,150,116));
            p.setStrokeWidth(9);
            for(int i=18;i<480;i+=42){
                c.drawLine(i,0,480-i,640,p);
                c.drawLine(0,i,480,i+120,p);
            }
            p.setColor(Color.WHITE);
            p.setStrokeWidth(25);
            c.drawLine(200,600,200,240,p);
            p.setColor(Color.rgb(105,247,133));
            p.setStrokeWidth(17);
            c.drawLine(200,600,200,240,p);
            p.setColor(Color.rgb(242,255,250));
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(200,390,15,p);
        }finally {
            if(c!=null)holder.unlockCanvasAndPost(c);
        }
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        FrameLayout root=new FrameLayout(this);
        surface=new SurfaceView(this);
        root.addView(surface,new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        // Start an unexported foreground service from THIS OWN visible app.
        // An adb shell UID is correctly prohibited from launching this service.
        startForegroundService(new Intent(this,MapsHudKeepAliveService.class));
        Log.i(TAG,"Local foreground keepalive started from Activity");
        surface.getHolder().addCallback(new SurfaceHolder.Callback(){
            public void surfaceCreated(SurfaceHolder h){
                try{drawRoads(h);main.postDelayed(HudGpuMapTestActivity.this::testCopy,450L);}
                catch(Throwable ex){Log.e(TAG,"DRAW FAILED",ex);}
            }
            public void surfaceChanged(SurfaceHolder h,int format,int w,int height){
                try{drawRoads(h);}catch(Throwable ex){Log.e(TAG,"REDRAW FAILED",ex);}
            }
            public void surfaceDestroyed(SurfaceHolder h){}
        });
        main.postDelayed(()->{
            if(!finished.get())Log.e(TAG,"FAIL: PixelCopy timeout; no real map sample in 10s");
        },10000L);
    }
    private void testCopy(){
        if(finished.get())return;
        try{
            sampler.sample(surface);
            Bitmap pixel=Bitmap.createBitmap(125,111,Bitmap.Config.ARGB_8888);
            Canvas c=new Canvas(pixel);
            boolean ok=sampler.draw(c,new android.graphics.Rect(0,0,125,111));
            if(ok){
                int unique=0;
                boolean[] colors=new boolean[64];
                for(int y=0;y<111;y+=5)for(int x=0;x<125;x+=5){
                    int v=pixel.getPixel(x,y);
                    int id=((Color.red(v)>>6)<<4)|((Color.green(v)>>6)<<2)|(Color.blue(v)>>6);
                    if(!colors[id]){colors[id]=true;unique++;}
                }
                if(unique>=4){
                    if(finished.compareAndSet(false,true))
                        Log.i(TAG,"PASS PixelCopy SurfaceView 125x111, distinct colors="+unique);
                }else Log.e(TAG,"FAIL: map thumbnail has insufficient colors: "+unique);
            }
            pixel.recycle();
        }catch(Throwable ex){Log.e(TAG,"PixelCopy integration test failed",ex);}
        if(!finished.get()&&++tick<10)main.postDelayed(this::testCopy,800L);
    }
}
