package ru.rokidhud.phone;

import android.app.Notification;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.*;

/** Extracts labelled text and bounded image samples FROM NOTIFICATION RemoteViews only.
 * No screen capture, accessibility or screenshot upload. */
final class YandexLayout {
    static final class Sample {
        final String id, digest;
        final Bitmap image;
        Sample(String id,String digest,Bitmap image){this.id=id;this.digest=digest;this.image=image;}
    }
    final Map<String,String> texts;
    final List<Sample> images;
    private YandexLayout(Map<String,String> texts,List<Sample> images){
        this.texts=texts;this.images=images;
    }
    @SuppressWarnings("deprecation")
    static YandexLayout inspect(Context ctx,Notification n) {
        android.widget.RemoteViews views=n.bigContentView!=null?n.bigContentView:n.contentView;
        if (views==null) return new YandexLayout(Collections.emptyMap(),Collections.emptyList());
        View root;
        try { root=views.apply(ctx,new FrameLayout(ctx)); }
        catch(Exception ignored){return new YandexLayout(Collections.emptyMap(),Collections.emptyList());}
        Map<String,String> texts=new LinkedHashMap<>();
        List<Sample> images=new ArrayList<>();
        walk(root,texts,images);
        return new YandexLayout(texts,images);
    }
    private static void walk(View v,Map<String,String> texts,List<Sample> images) {
        if(v.getVisibility()!=View.VISIBLE) return;
        String id="unknown_image";
        if(v.getId()!=View.NO_ID)try{id=v.getResources().getResourceEntryName(v.getId());}
        catch(Exception ignored){}
        if(v instanceof TextView){
            String s=((TextView)v).getText().toString().trim();
            if(!s.isEmpty())texts.putIfAbsent(id,s);
        }
        if(v instanceof ImageView && images.size()<8){
            Sample item=sample((ImageView)v,id);
            if(item!=null)images.add(item);
        }
        if(v instanceof ViewGroup){
            ViewGroup g=(ViewGroup)v;
            for(int i=0;i<g.getChildCount();i++)walk(g.getChildAt(i),texts,images);
        }
    }
    private static Sample sample(ImageView view,String id) {
        Drawable d=view.getDrawable();
        if(d==null)return null;
        int w=d.getIntrinsicWidth(),h=d.getIntrinsicHeight();
        if(w<=0)w=view.getWidth();
        if(h<=0)h=view.getHeight();
        if(w<1||h<1||w>4096||h>4096)return null;
        float scale=Math.min(1f,Math.min(128f/w,128f/h));
        int sw=Math.max(1,Math.round(w*scale)),sh=Math.max(1,Math.round(h*scale));
        Bitmap bm=Bitmap.createBitmap(sw,sh,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(bm);canvas.drawColor(Color.TRANSPARENT);
        Rect bounds=new Rect(d.getBounds());
        try {d.setBounds(0,0,sw,sh);d.draw(canvas);}
        catch(Exception e){bm.recycle();return null;}
        finally{d.setBounds(bounds);}
        int[] px=new int[sw*sh];bm.getPixels(px,0,sw,0,0,sw,sh);
        ByteBuffer buffer=ByteBuffer.allocate(8+px.length*4);
        buffer.putInt(sw).putInt(sh);
        for(int pixel:px)buffer.putInt(pixel);
        byte[] bytes=MessageDigestSafe.sha256(buffer.array());
        StringBuilder key=new StringBuilder();
        for(byte b:bytes)key.append(String.format(Locale.ROOT,"%02x",b&255));
        return new Sample(id,key.toString(),bm);
    }
    static final class MessageDigestSafe {
        static byte[] sha256(byte[] input){
            try{return MessageDigest.getInstance("SHA-256").digest(input);}
            catch(Exception e){throw new IllegalStateException(e);}
        }
    }
}
