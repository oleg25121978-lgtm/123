package ru.rokidhud.mapsrelay;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;

/**
 * Real small MapKit viewport. No invented roads, markers or map coordinates.
 * Copies ONLY MapKit's own graphical SurfaceView/TextureView, never the phone UI.
 * Using a 7x wider/taller source rectangle makes both road widths and vehicle
 * glyphs smaller in the optical viewport, unlike merely shrinking its border.
 *
 * A SurfaceView generally paints nothing with View.draw(Canvas); PixelCopy is
 * necessary when the map's actual GL pixels are held by a Surface/texture.
 *
 * Android APIs are accessed on the main thread; the PixelCopy completion is
 * asynchronous and never blocks the ongoing TEST9-style Bluetooth sender.
 */
public final class MapsHudMiniMap {
    private static final String TAG="Maps31HUDMiniMap";
    private static final int DW=125,DH=111;
    private static final int SHRINK=7;
    private static final long SAMPLE_MS=1300L;
    private final Handler main=new Handler(Looper.getMainLooper());
    private Bitmap current;
    private long lastRequestAt=0;
    private long lastCapturedAt=0;
    private boolean inFlight=false;
    private int sourceType=0; // 1 SurfaceView, 2 TextureView

    private static int clamp(int x,int min,int max){return Math.max(min,Math.min(x,max));}

    private static Rect crop(int width,int height) {
        int spanW=Math.min(width,DW*SHRINK);
        int spanH=Math.min(height,DH*SHRINK);
        int x=clamp(Math.round(width*0.5f-spanW/2f),0,width-spanW);
        int y=clamp(Math.round(height*0.66f-spanH/2f),0,height-spanH);
        return new Rect(x,y,x+spanW,y+spanH);
    }
    private static boolean valid(Bitmap bitmap) {
        if(bitmap==null || bitmap.isRecycled())return false;
        int w=bitmap.getWidth(),h=bitmap.getHeight();
        if(w<DW/2||h<DH/2)return false;
        int[] seen=new int[64];
        int varied=0;
        for(int y=4;y<h;y+=9)for(int x=4;x<w;x+=9) {
            int col=bitmap.getPixel(x,y);
            if(Color.alpha(col)<30)continue;
            int key=((Color.red(col)>>6)<<4)|
                    ((Color.green(col)>>6)<<2)|
                    (Color.blue(col)>>6);
            if(seen[key]++==0)varied++;
        }
        return varied>=4;
    }
    private static View findGraphic(View root,int level) {
        if(root==null||level>18)return null;
        if(root instanceof SurfaceView) {
            SurfaceView s=(SurfaceView)root;
            if(s.getWidth()>DW && s.getHeight()>DH && s.getHolder().getSurface()!=null
                 && s.getHolder().getSurface().isValid())return s;
        }
        if(root instanceof TextureView) {
            TextureView v=(TextureView)root;
            if(v.isAvailable() && v.getWidth()>DW && v.getHeight()>DH)return v;
        }
        if(root instanceof ViewGroup) {
            ViewGroup g=(ViewGroup)root;
            for(int i=0;i<g.getChildCount();i++){
                View c=findGraphic(g.getChildAt(i),level+1);
                if(c!=null)return c;
            }
        }
        return null;
    }
    public void sample(View map) {
        long now=System.currentTimeMillis();
        if(inFlight||now-lastRequestAt<SAMPLE_MS)return;
        lastRequestAt=now;
        if(map==null||map.getWidth()<=DW||map.getHeight()<=DH)return;
        View image=findGraphic(map,0);
        if(image instanceof SurfaceView) {
            // PixelCopy was introduced in Android 8.0; stay installable on
            // older phones without trying an unavailable framework class.
            if(android.os.Build.VERSION.SDK_INT>=26)copySurface((SurfaceView)image);
        } else if(image instanceof TextureView)copyTexture((TextureView)image);
    }
    private void copySurface(SurfaceView surface) {
        if(!surface.getHolder().getSurface().isValid())return;
        int w=surface.getWidth(),h=surface.getHeight();
        if(w<DW||h<DH)return;
        Rect src=crop(w,h);
        Bitmap dst=Bitmap.createBitmap(DW,DH,Bitmap.Config.ARGB_8888);
        inFlight=true;
        try {
            PixelCopy.request(surface,src,dst,result->{
                inFlight=false;
                if(result==PixelCopy.SUCCESS && valid(dst)){
                    Log.i(TAG,"PixelCopy SurfaceView succeeded, 125x111");
                    replace(dst,1);
                }else{
                    Log.w(TAG,"PixelCopy rejected frame, result="+result+
                        " colorful="+(result==PixelCopy.SUCCESS && valid(dst)));
                    dst.recycle();
                }
            },main);
        }catch(Throwable ex){
            inFlight=false;dst.recycle();
            Log.d(TAG,"MapKit SurfaceView PixelCopy not available",ex);
        }
    }
    private void copyTexture(TextureView texture) {
        if(!texture.isAvailable())return;
        // Avoid allocating an entire full-resolution phone framebuffer.
        int w=texture.getWidth(),h=texture.getHeight();
        int smallW=clamp(w/3,DW,700),smallH=clamp(h/3,DH,700);
        Bitmap sampling=null;
        Bitmap dst=null;
        try {
            sampling=texture.getBitmap(smallW,smallH);
            if(sampling==null)return;
            Rect cropRaw=crop(w,h);
            Rect src=new Rect(
                Math.round(cropRaw.left*smallW/(float)w),
                Math.round(cropRaw.top*smallH/(float)h),
                Math.round(cropRaw.right*smallW/(float)w),
                Math.round(cropRaw.bottom*smallH/(float)h));
            src.intersect(0,0,smallW,smallH);
            dst=Bitmap.createBitmap(DW,DH,Bitmap.Config.ARGB_8888);
            Canvas c=new Canvas(dst);
            Paint p=new Paint(Paint.FILTER_BITMAP_FLAG|Paint.ANTI_ALIAS_FLAG);
            c.drawBitmap(sampling,src,new Rect(0,0,DW,DH),p);
            if(valid(dst)){replace(dst,2);dst=null;}
        }catch(Throwable ex){
            Log.d(TAG,"MapKit TextureView copy not available",ex);
        }finally{
            if(sampling!=null&&!sampling.isRecycled())sampling.recycle();
            if(dst!=null&&!dst.isRecycled())dst.recycle();
        }
    }
    private void replace(Bitmap b,int type){
        if(current!=null&&!current.isRecycled())current.recycle();
        current=b;sourceType=type;
        lastCapturedAt=System.currentTimeMillis();
    }
    /** Only a verified, real map sample is ever drawn. */
    public boolean draw(Canvas out, Rect dst) {
        if(current==null||current.isRecycled())return false;
        // If map rendering is paused while navigation is in the background,
        // allow a recent cached map but never pretend it's a live location.
        if(System.currentTimeMillis()-lastCapturedAt>30000L)return false;
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        out.drawBitmap(current,null,dst,p);
        return true;
    }
    public void clear(){
        if(current!=null && !current.isRecycled())current.recycle();
        current=null;lastCapturedAt=0;
    }
}
