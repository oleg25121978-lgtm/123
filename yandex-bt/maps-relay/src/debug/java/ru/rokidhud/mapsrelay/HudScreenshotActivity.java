package ru.rokidhud.mapsrelay;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * DEBUG-ONLY screenshot test: exercises exactly MapsHudProvider.render(Widgets)
 * on the Android emulator with labelled SAMPLE values.
 *
 * It does not show live Yandex navigation or prove MapKit PixelCopy/foreground
 * updates. Never ship a test fixture as an actual Yandex Maps screenshot.
 */
public final class HudScreenshotActivity extends Activity {
    private static final int GREEN=Color.rgb(105,247,133);
    private static final int WHITE=Color.rgb(230,255,232);
    private static final int BACKGROUND=Color.rgb(25,38,29);
    private Bitmap generated;

    public static final class FakeSpeed extends LinearLayout {
        private final TextView actualSpeed;
        public FakeSpeed(Activity activity){
            super(activity);
            actualSpeed=new TextView(activity);
            actualSpeed.setText("62");
            addView(actualSpeed);
            layout(0,0,150,55);
        }
        public TextView getTextSpeedValue(){return actualSpeed;}
    }
    private static final class FakeTurn extends View {
        private final Paint p=new Paint(3);
        FakeTurn(Activity a){super(a);layout(0,0,330,172);}
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);
            p.setColor(BACKGROUND);p.setStyle(Paint.Style.FILL);
            c.drawRoundRect(0,0,330,172,22,22,p);
            p.setColor(GREEN);p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(10);p.setStrokeCap(Paint.Cap.ROUND);
            Path path=new Path();
            path.moveTo(45,130);path.lineTo(45,65);path.lineTo(114,65);
            path.moveTo(90,42);path.lineTo(114,65);path.lineTo(90,88);
            c.drawPath(path,p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(WHITE);p.setTextSize(52);
            c.drawText("180 м",141,98,p);
        }
    }
    private static final class FakeLanes extends View {
        private final Paint p=new Paint(3);
        FakeLanes(Activity a){super(a);layout(0,0,330,76);}
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);
            c.save();c.scale(1.0f,1.0f);
            for(int i=0;i<3;i++){
                float x=50+i*100;
                p.setColor(i==0?Color.rgb(89,145,107):GREEN);
                p.setStrokeWidth(5);p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeJoin(Paint.Join.ROUND);
                p.setStyle(Paint.Style.STROKE);
                Path b=new Path();
                b.moveTo(x,64);b.lineTo(x,17);
                if(i==2){
                    b.lineTo(x+24,17);
                    b.moveTo(x+14,8);b.lineTo(x+24,17);b.lineTo(x+14,26);
                }else{
                    b.moveTo(x-11,29);b.lineTo(x,17);b.lineTo(x+11,29);
                }
                c.drawPath(b,p);
            }
            c.restore();
            p.setStyle(Paint.Style.FILL);
        }
    }
    private Bitmap fakeMap(){
        Bitmap bitmap=Bitmap.createBitmap(125,111,Bitmap.Config.ARGB_8888);
        Canvas c=new Canvas(bitmap);
        Paint p=new Paint(3);
        p.setColor(Color.rgb(18,28,22));
        c.drawColor(Color.rgb(18,28,22));
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.0f);
        p.setColor(Color.rgb(102,126,110));
        for(int i=9;i<125;i+=20){
            Path road=new Path();
            road.moveTo(i,0);road.cubicTo(i-7,25,i+8,75,i-3,111);
            c.drawPath(road,p);
        }
        for(int j=18;j<111;j+=23){
            Path road=new Path();
            road.moveTo(0,j);road.cubicTo(40,j-16,83,j+14,125,j+4);
            c.drawPath(road,p);
        }
        p.setColor(GREEN);p.setStrokeWidth(2.3f);
        Path route=new Path();
        route.moveTo(53,111);route.lineTo(53,82);route.lineTo(78,62);
        route.lineTo(90,62);route.lineTo(90,13);
        c.drawPath(route,p);
        p.setColor(WHITE);p.setStyle(Paint.Style.FILL);
        c.drawCircle(53,82,4.0f,p);
        p.setColor(GREEN);c.drawCircle(53,82,2.1f,p);
        return bitmap;
    }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        try{
            MapsHudProvider provider=new MapsHudProvider();
            Class<?> widgetsClass=Class.forName(
                "ru.rokidhud.mapsrelay.MapsHudProvider$Widgets");
            Constructor<?> ctor=widgetsClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object widgets=ctor.newInstance();

            LinearLayout eta=new LinearLayout(this);
            TextView arrival=new TextView(this);
            arrival.setText("Прибытие 14:50");
            eta.addView(arrival);
            eta.layout(0,0,230,50);

            set(widgetsClass,widgets,"eta",eta);
            set(widgetsClass,widgets,"speed",new FakeSpeed(this));
            set(widgetsClass,widgets,"maneuver",new FakeTurn(this));
            set(widgetsClass,widgets,"lanes",new FakeLanes(this));

            Field mf=MapsHudProvider.class.getDeclaredField("miniMap");
            mf.setAccessible(true);
            MapsHudMiniMap map=(MapsHudMiniMap)mf.get(provider);
            Field current=MapsHudMiniMap.class.getDeclaredField("current");
            current.setAccessible(true);
            current.set(map,fakeMap());
            Field capturedAt=MapsHudMiniMap.class.getDeclaredField("lastCapturedAt");
            capturedAt.setAccessible(true);
            capturedAt.setLong(map,System.currentTimeMillis());

            Method render=MapsHudProvider.class.getDeclaredMethod(
                "render",widgetsClass);
            render.setAccessible(true);
            generated=(Bitmap)render.invoke(provider,widgets);
            // Actual pixel-level smoke checks, including native background
            // chroma removal, and independent lanes and mini map regions.
            if(Color.alpha(generated.getPixel(3,3))!=0)
                throw new AssertionError("Top HUD must remain transparent");
            if(Color.alpha(generated.getPixel(385,505))==0)
                throw new AssertionError("Expected real mini-map sample pixels");
            int visible=0;
            for(int y=480;y<625;y+=4)for(int x=12;x<335;x+=4)
                if(Color.alpha(generated.getPixel(x,y))>20)visible++;
            if(visible<15)throw new AssertionError("HUD glyphs absent: "+visible);
            Log.i("Maps31PreviewTest","PASS 480x640 layout alpha, sample map, glyphs: "+visible);
            ImageView image=new ImageView(this);
            image.setBackgroundColor(Color.BLACK);
            image.setScaleType(ImageView.ScaleType.FIT_XY);
            image.setImageBitmap(generated);
            setContentView(image);
        }catch(Throwable error){
            Log.e("Maps31PreviewTest","Render test failed",error);
            TextView failure=new TextView(this);
            failure.setText("HUD preview failed: "+error.toString());
            failure.setTextColor(Color.RED);
            setContentView(failure);
        }
    }

    private static void set(Class<?> type,Object obj,String field,Object value)
        throws Exception{
        Field f=type.getDeclaredField(field);
        f.setAccessible(true);f.set(obj,value);
    }
    @Override public void onDestroy(){
        if(generated!=null&&!generated.isRecycled())generated.recycle();
        super.onDestroy();
    }
}
