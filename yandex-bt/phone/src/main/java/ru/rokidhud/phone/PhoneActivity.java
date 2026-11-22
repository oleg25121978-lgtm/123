package ru.rokidhud.phone;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.widget.*;

public class PhoneActivity extends Activity {
    private TextView info;
    private ImageView preview;
    private TextView imageLabel;
    private int selectedImage=0;
    private Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        public void run() {
            if (info!=null) {
                NavData n=NavData.current;
                info.setText("Bluetooth: "+BluetoothHudService.status+"\n"+
                  "Яндекс: "+(n==null?"нет актуального маршрута":n.distance+"  "+n.maneuver)+"\n"+
                  "Скорость GPS: "+(BluetoothHudService.speedKmh==null?"нет":BluetoothHudService.speedKmh+" км/ч")+"\n"+
                  "Данные уведомления: "+BluetoothHudService.debugText+"\n"+
                  "Тестовая подсказка: "+(System.currentTimeMillis()<BluetoothHudService.demoUntil?"ВКЛ":"ВЫКЛ"));
            }
            if(preview!=null){
                java.util.List<YandexLayout.Sample> images=YandexListener.images;
                if(selectedImage>=images.size())selectedImage=Math.max(0,images.size()-1);
                YandexLayout.Sample sample=images.isEmpty()?null:images.get(selectedImage);
                preview.setImageBitmap(sample==null?null:sample.image);
                imageLabel.setText(sample==null?"Изображений нет":
                    "Изображение "+(selectedImage+1)+"/"+images.size()+
                    " · "+sample.id+" · "+ArrowLabels.labelFor(PhoneActivity.this,sample));
            }
            handler.postDelayed(this,1000);
        }
    };
    private void button(LinearLayout layout, String text, Runnable r) {
        Button b=new Button(this); b.setText(text); b.setAllCaps(false); b.setOnClickListener(v->r.run()); layout.addView(b);
    }
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setPadding(28,28,28,28);
        TextView title=new TextView(this); title.setText("Яндекс → Rokid HUD (Bluetooth)"); title.setTextSize(22); v.addView(title);
        button(v, "1. Разрешить Bluetooth и GPS", () -> requestPermissions(new String[]{
            Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.ACCESS_FINE_LOCATION}, 2));
        button(v, "2. Доступ к уведомлениям Яндекса", () ->
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        button(v, "3. Начать передачу на очки", () -> {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 1); return;
            }
            startForegroundService(new Intent(this,BluetoothHudService.class));
        });
        button(v, "ТЕСТ: показать 200 м → на очках (30 секунд)", () -> {
            BluetoothHudService.demoUntil=System.currentTimeMillis()+30000L;
            if (!BluetoothHudService.running &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED)
                startForegroundService(new Intent(this,BluetoothHudService.class));
        });
        button(v, "Открыть Яндекс Навигатор", () -> {
            Intent i=getPackageManager().getLaunchIntentForPackage(NavData.YANDEX_NAVI);
            if(i==null)i=getPackageManager().getLaunchIntentForPackage(NavData.YANDEX_MAPS);
            if(i!=null) startActivity(i); else Toast.makeText(this,"Яндекс не найден",Toast.LENGTH_SHORT).show();
        });
        button(v, "Остановить передачу", () -> stopService(new Intent(this,BluetoothHudService.class)));
        button(v, "Удалить это приложение", () -> startActivity(new Intent(Intent.ACTION_DELETE,Uri.parse("package:"+getPackageName()))));
        TextView hint=new TextView(this);
        hint.setText("Калибровка картинки поворота — ТОЛЬКО НА СТОЯНКЕ. Сначала проверь, что это именно стрелка, а не логотип.");
        v.addView(hint);
        LinearLayout controls=new LinearLayout(this);
        button(controls, "◀", () -> {selectedImage=Math.max(0,selectedImage-1);});
        button(controls, "▶", () -> {selectedImage++;});
        v.addView(controls);
        imageLabel=new TextView(this);v.addView(imageLabel);
        preview=new ImageView(this);preview.setMinimumHeight(120);preview.setAdjustViewBounds(true);v.addView(preview);
        for(String m:new String[]{"LEFT","RIGHT","STRAIGHT","SLIGHT_LEFT","SLIGHT_RIGHT","UTURN","ROUNDABOUT"}) {
            final String label=m;
            button(v,"Метка: "+m, () -> {
                java.util.List<YandexLayout.Sample> sampleList=YandexListener.images;
                ArrowLabels.label(this,sampleList,selectedImage,label);
                NavData data=NavData.current;
                if(data!=null)NavData.current=data.withGuidance(label,data.street);
            });
        }
        info=new TextView(this); info.setTextSize(15); info.setPadding(4,20,4,4); v.addView(info);
        ScrollView scroll=new ScrollView(this); scroll.addView(v); setContentView(scroll);
    }
    @Override public void onResume() { super.onResume(); handler.post(refresh); }
    @Override public void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
}
