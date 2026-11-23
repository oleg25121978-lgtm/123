package ru.rokidhud.glasses;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.ColorDrawable;
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
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
            WindowManager.LayoutParams.FLAG_FULLSCREEN);
        // Transparent window; on Rokid additive displays black/off pixels also let real world through.
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
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
            {setBackgroundColor(Color.TRANSPARENT);setOnLongClickListener(v->{disconnect();chooser();return true;});}
            @Override protected void onDraw(Canvas c){
                super.onDraw(c);
                c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
                Bitmap current=frame;
                int w=getWidth(),h=getHeight();
                // The source bitmap is 480x640 with its original alpha channel;
                // no opaque backdrop and no vertical stretching to make an extra toolbar.
                if(current!=null && live && System.currentTimeMillis()-lastPacket<4500){
                    p.setFilterBitmap(true);
                    p.setColor(Color.WHITE);
                    p.setStyle(Paint.Style.FILL);
                    c.drawBitmap(current,null,new Rect(0,0,w,h),p);
                }else{
                    p.setColor(Color.rgb(89,170,103));
                    p.setTextSize(Math.max(15f,w/26f));
                    c.drawText(status,Math.max(12f,w/30f),Math.max(35f,h/12f),p);
                }
                drawGlassesStatus(c,p,w,h);
            }
        };
        setContentView(display);
        reader=new Thread(()->receive(address),"test9-hud-glasses");
        reader.start();
    }
    /** Transparent, compact system icons in lower-right of the 480x640 HUD. */
    private void drawGlassesStatus(Canvas canvas, Paint p, int width, int height) {
        if (width<=0 || height<=0) return;
        canvas.save();
        canvas.scale(width/480f,height/640f);
        final int green=Color.rgb(106,247,133);
        final int dull=Color.rgb(99,139,110);
        p.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        p.setStyle(Paint.Style.FILL);
        p.setStrokeWidth(2f);
        p.setColor(green);
        p.setTextSize(16f);
        // Time is local to Rokid rather than the Huawei phone.
        String clock=new SimpleDateFormat("HH:mm",Locale.getDefault()).format(new Date());
        canvas.drawText(clock,14,619,p);
        // Bluetooth: green when a Rokid Bluetooth connection is enabled.
        float bx=353f,by=613f;
        p.setColor(btEnabled?green:dull);p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2.1f);p.setStrokeCap(Paint.Cap.ROUND);
        Path path=new Path();
        path.moveTo(bx,by-12);path.lineTo(bx,by+12);
        path.moveTo(bx-8,by-8);path.lineTo(bx+8,by+8);
        path.lineTo(bx,by+12);path.lineTo(bx,by-12);
        path.lineTo(bx+8,by-8);path.lineTo(bx-8,by+8);
        canvas.drawPath(path,p);
        p.setStyle(Paint.Style.FILL);
        if(live && active!=null && active.isConnected())canvas.drawCircle(365,602,3,p);
        // Wi-Fi: only actual connectivity. Unknown RSSI draws a small dot, not fake bars.
        p.setColor(wifiConnected?green:dull);p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2f);
        float wx=387,wy=615;
        int bars=wifiSignal<0?0:Math.min(4,Math.max(0,wifiSignal));
        if(bars==0 && wifiConnected){
            canvas.drawArc(wx-6,wy-6,wx+6,wy+6,220,100,false,p);
        } else for(int k=0;k<bars;k++){
            float radius=5+k*4;
            canvas.drawArc(wx-radius,wy-radius,wx+radius,wy+radius,220,100,false,p);
        }
        if(!wifiConnected) canvas.drawLine(wx-10,wy-13,wx+11,wy+9,p);
        p.setStyle(Paint.Style.FILL);canvas.drawCircle(wx,wy,2.4f,p);

        // Battery capacity is read from Rokid's battery broadcast.
        float left=409,top=602,right=445,bottom=622;
        p.setColor(glassesBattery>=0 && glassesBattery<=15?Color.rgb(245,100,89):green);
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.8f);
        canvas.drawRoundRect(left,top,right,bottom,3,3,p);
        canvas.drawRect(right+1,608,right+5,616,p);
        if(glassesBattery>=0) {
            p.setStyle(Paint.Style.FILL);
            float fraction=Math.max(0f,Math.min(1f,glassesBattery/100f));
            if(fraction>0) canvas.drawRect(left+3,top+3,left+3+fraction*30,bottom-3,p);
        }
        p.setStyle(Paint.Style.FILL);p.setTextSize(13f);
        p.setColor(green);
        String charge=glassesBattery<0?"—":String.valueOf(glassesBattery);
        canvas.drawText(charge,451,617,p);
        if(glassesCharging)canvas.drawText("+",421,598,p);
        canvas.restore();
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
