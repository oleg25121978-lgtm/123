package ru.rokidhud.glasses;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.os.*;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import ru.rokidhud.wire.HudMessage;
import java.io.*;
import java.util.Set;

/** Displays only the TEST9 HUD bitmap stream, never phone-screen mirroring. */
public final class GlassesActivity extends Activity {
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile boolean reconnect=false;
    private BluetoothSocket active;
    private Thread reader;
    private View display;
    private Bitmap frame;
    private long lastPacket=0;
    private String status="Выбери телефон";
    private String phone;
    private boolean live=false;
    private boolean closing=false;
    private int glassesBattery=-1;
    private boolean glassesCharging=false;
    private boolean wifiConnected=false;
    private int wifiSignal=-1;
    private boolean btEnabled=false;
    private final BroadcastReceiver systemReceiver=new BroadcastReceiver(){
        @Override public void onReceive(Context context, Intent intent) {
            if(intent!=null && Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
                int level=intent.getIntExtra(BatteryManager.EXTRA_LEVEL,-1);
                int scale=intent.getIntExtra(BatteryManager.EXTRA_SCALE,-1);
                glassesBattery=level>=0 && scale>0 ? Math.round(level*100f/scale) : -1;
                int status=intent.getIntExtra(BatteryManager.EXTRA_STATUS,0);
                glassesCharging=status==BatteryManager.BATTERY_STATUS_CHARGING ||
                    status==BatteryManager.BATTERY_STATUS_FULL;
            }
            refreshStatus();
        }
    };
    private final Runnable statusTicker=new Runnable(){
        @Override public void run(){
            if(closing || isFinishing() || isDestroyed())return;
            refreshStatus();
            main.postDelayed(this,10000L);
        }
    };

    @Override public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        phone=getPreferences(MODE_PRIVATE).getString("phone",null);
        // This receiver runs on Rokid: all indicators come from the glasses themselves.
        IntentFilter battery=new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent sticky=registerReceiver(systemReceiver,battery);
        if(sticky!=null) systemReceiver.onReceive(this,sticky);
        main.post(statusTicker);
        if(!allowed()) requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 9);
        else begin();
    }
    private boolean allowed() {return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(request,permissions,grants);
        begin();
    }
    @SuppressWarnings("MissingPermission")
    private void refreshStatus(){
        try {
            BluetoothManager bm=getSystemService(BluetoothManager.class);
            BluetoothAdapter ba=bm==null?null:bm.getAdapter();
            btEnabled=ba!=null && ba.isEnabled();
        }catch(Throwable ignored){btEnabled=false;}
        wifiConnected=false;
        wifiSignal=-1;
        try{
            ConnectivityManager cm=getSystemService(ConnectivityManager.class);
            Network net=cm==null?null:cm.getActiveNetwork();
            NetworkCapabilities caps=net==null?null:cm.getNetworkCapabilities(net);
            wifiConnected=caps!=null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            // This may be unavailable without location permission; in that case show Wi-Fi
            // connectivity but do not invent signal strength.
            if(wifiConnected){
                WifiManager wifi=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
                int rssi=wifi==null?-127:wifi.getConnectionInfo().getRssi();
                if(rssi>=-100 && rssi<=-30) {
                    wifiSignal=rssi>=-55?4:rssi>=-67?3:rssi>=-75?2:rssi>=-85?1:0;
                }
            }
        }catch(Throwable ignored) {}
        if(display!=null) display.invalidate();
    }
    private void begin(){if(phone!=null && allowed()) connect(phone);else chooser();}
    @SuppressWarnings("MissingPermission")
    private void chooser(){
        disconnect();
        LinearLayout root=new LinearLayout(this);
        root.setPadding(12,15,12,15);root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        TextView heading=new TextView(this);
        heading.setText("TEST9 HUD → Rokid\nВыбери сопряжённый телефон");
        heading.setTextColor(Color.GREEN);heading.setTextSize(20);root.addView(heading);
        Button bt=new Button(this);bt.setText("Настройки Bluetooth");
        bt.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));root.addView(bt);
        Button refresh=new Button(this);refresh.setText("Обновить");refresh.setOnClickListener(v->chooser());root.addView(refresh);
        if(!allowed()){
            Button perm=new Button(this);perm.setText("Разрешить Bluetooth");
            perm.setOnClickListener(v->requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT},9));
            root.addView(perm);
        }else{
            BluetoothManager manager=getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
            if(adapter!=null && adapter.isEnabled()){
                Set<BluetoothDevice> devices=adapter.getBondedDevices();
                for(BluetoothDevice device:devices){
                    String address=device.getAddress();
                    Button item=new Button(this);
                    item.setText((device.getName()==null?"Телефон":device.getName())+"\n"+address);
                    item.setOnClickListener(v->connect(address));
                    root.addView(item);
                }
            }else{
                TextView hint=new TextView(this);hint.setText("Включи Bluetooth");root.addView(hint);
            }
        }
        ScrollView scroll=new ScrollView(this);scroll.addView(root);setContentView(scroll);
    }
    private synchronized void connect(String address){
        disconnect();
        phone=address;
        getPreferences(MODE_PRIVATE).edit().putString("phone",address).apply();
        reconnect=true;status="Соединение…";live=false;
        display=new View(this){
            private final Paint p=new Paint(3);
            {setBackgroundColor(Color.BLACK);setOnLongClickListener(v->{disconnect();chooser();return true;});}
            @Override protected void onDraw(Canvas c){
                super.onDraw(c);c.drawColor(Color.BLACK);
                Bitmap current=frame;
                int w=getWidth(),h=getHeight();
                float ratio=w/480f;
                int statusH=Math.round(58*ratio);
                // Preserve every original Test9 HUD pixel, while reserving one thin
                // dedicated system strip along the bottom for glasses indicators.
                if(current!=null && live && System.currentTimeMillis()-lastPacket<4500){
                    p.setFilterBitmap(true);
                    p.setColor(Color.WHITE);
                    p.setStyle(Paint.Style.FILL);
                    c.drawBitmap(current,null,new Rect(0,0,w,Math.max(1,h-statusH)),p);
                }else{
                    p.setColor(Color.rgb(89,170,103));
                    p.setTextSize(Math.max(15f,w/26f));
                    c.drawText(status,Math.max(12f,w/30f),Math.max(35f,h/12f),p);
                }
                drawGlassesStatus(c,p,w,h,statusH);
            }
        };
        setContentView(display);
        reader=new Thread(()->receive(address),"test9-hud-glasses");
        reader.start();
    }
    /** On-glasses indicators; not Huawei or Yandex Navigator values. */
    private void drawGlassesStatus(Canvas c, Paint p,int width,int height,int stripHeight) {
        c.save();
        float sx=width/480f;
        float y=height-stripHeight;
        p.setStyle(Paint.Style.FILL);p.setColor(Color.rgb(5,12,8));
        c.drawRect(0,y,width,height,p);
        p.setColor(Color.rgb(36,73,53));
        p.setStrokeWidth(Math.max(1f,sx));
        c.drawLine(0,y,width,y,p);
        c.translate(0,y);c.scale(sx,sx);
        int green=Color.rgb(106,247,133);
        int dull=Color.rgb(89,120,99);
        p.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        p.setTextSize(17f);p.setStyle(Paint.Style.FILL);
        p.setColor(green);
        String now=new SimpleDateFormat("HH:mm",Locale.getDefault()).format(new Date());
        c.drawText(now,17,37,p);

        // Bluetooth: connected icon is green, disconnected state is subdued.
        int btColor=btEnabled?green:dull;
        p.setColor(btColor);p.setStrokeWidth(2.4f);p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        Path bt=new Path();bt.moveTo(288,18);bt.lineTo(288,43);
        bt.moveTo(278,22);bt.lineTo(298,39);bt.lineTo(288,45);bt.lineTo(288,17);
        bt.lineTo(298,24);bt.lineTo(278,39);
        c.drawPath(bt,p);
        p.setStyle(Paint.Style.FILL);
        if(active!=null && active.isConnected()) c.drawCircle(305,21,3,p);
        // Wi-Fi icon: network presence and real RSSI, when accessible.
        p.setColor(wifiConnected?green:dull);
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2.3f);
        int bars=wifiSignal<0?(wifiConnected?3:0):wifiSignal;
        for(int k=0;k<bars;k++){
            float r=5+k*6;
            c.drawArc(318-r,37-r,318+r,37+r,225,90,false,p);
        }
        if(!wifiConnected) {
            c.drawLine(307,18,329,42,p);
        }
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(318,39,2.5f,p);
        // Battery outline + level from ACTION_BATTERY_CHANGED.
        p.setColor(green);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2f);
        c.drawRoundRect(353,20,391,41,4,4,p);
        c.drawRect(392,26,396,35,p);
        if(glassesBattery>=0){
            p.setStyle(Paint.Style.FILL);
            p.setColor(glassesBattery<=15?Color.rgb(244,97,79):green);
            float fill=Math.max(0f,glassesBattery/100f)*32f;
            c.drawRoundRect(356,23,356+fill,38,2,2,p);
        }
        p.setColor(green);p.setTextSize(17f);p.setStyle(Paint.Style.FILL);
        String percent=glassesBattery<0?"—":glassesBattery+"%";
        c.drawText(percent,402,37,p);
        if(glassesCharging) {
            p.setStrokeWidth(2f);p.setColor(Color.WHITE);
            c.drawText("+",365,18,p);
        }
        c.restore();
    }

    @SuppressWarnings("MissingPermission")
    private void receive(String address){
        while(reconnect){
            BluetoothSocket socket=null;
            try{
                BluetoothManager manager=getSystemService(BluetoothManager.class);
                BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
                if(adapter==null || !adapter.isEnabled() || !allowed())throw new IOException("Bluetooth unavailable");
                socket=adapter.getRemoteDevice(address).createRfcommSocketToServiceRecord(HudMessage.SERVICE_UUID);
                active=socket;socket.connect();
                update("Связь установлена");
                DataInputStream input=new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                while(reconnect){
                    int magic=input.readInt();
                    if(magic!=0x48554431)throw new IOException("Неверный формат Test9 HUD");
                    int length=input.readInt();
                    if(length<0 || length>300000)throw new IOException("Недопустимый размер кадра");
                    byte[] bytes=new byte[length];
                    input.readFully(bytes);
                    final Bitmap bitmap=length==0?null:BitmapFactory.decodeByteArray(bytes,0,bytes.length);
                    main.post(()->{
                        if(closing || isFinishing() || isDestroyed()) {
                            if(bitmap!=null && !bitmap.isRecycled())bitmap.recycle();
                            return;
                        }
                        if(frame!=null && frame!=bitmap && !frame.isRecycled())frame.recycle();
                        frame=bitmap;
                        lastPacket=System.currentTimeMillis();
                        live=bitmap!=null;
                        status=live?"HUD Test9":"Маршрут не запущен";
                        if(display!=null)display.invalidate();
                    });
                }
            }catch(Exception ex){update("Нет связи · подключаюсь…");}
            finally{
                active=null;
                if(socket!=null)try{socket.close();}catch(Exception ignored){}
            }
            if(reconnect)try{Thread.sleep(3000);}catch(InterruptedException ignored){break;}
        }
    }
    private void update(String message){
        main.post(()->{
            if(closing || isFinishing() || isDestroyed()) return;
            status=message;live=false;
            if(display!=null)display.invalidate();
        });
    }
    private synchronized void disconnect(){
        reconnect=false;
        if(active!=null)try{active.close();}catch(Exception ignored){}
        if(reader!=null)reader.interrupt();
        active=null;reader=null;
    }
    // Rokid's right-temple double tap is delivered to Activity as KEYCODE_BACK.
    // The previous onBackPressed() called chooser(), so the app never exited.
    private void exitHud() {
        if (closing) return;
        closing=true;
        disconnect(); // close RFCOMM immediately and prevent automatic reconnect
        finishAndRemoveTask();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode()==KeyEvent.KEYCODE_BACK) {
            if (event.getAction()==KeyEvent.ACTION_UP && !event.isCanceled()) exitHud();
            return true; // consume both key down and key up
        }
        return super.dispatchKeyEvent(event);
    }

    @Override public void onBackPressed() { exitHud(); }
    @Override public void onDestroy(){
        closing=true;
        main.removeCallbacks(statusTicker);
        try{unregisterReceiver(systemReceiver);}catch(Exception ignored){}
        disconnect();
        if(frame!=null && !frame.isRecycled())frame.recycle();
        frame=null;
        super.onDestroy();
    }
}
