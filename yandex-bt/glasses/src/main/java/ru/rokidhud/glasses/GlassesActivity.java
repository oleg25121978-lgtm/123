package ru.rokidhud.glasses;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.os.*;
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

    @Override public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        phone=getPreferences(MODE_PRIVATE).getString("phone",null);
        if(!allowed()) requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 9);
        else begin();
    }
    private boolean allowed() {return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(request,permissions,grants);
        begin();
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
                if(current!=null && live && System.currentTimeMillis()-lastPacket<4500){
                    p.setFilterBitmap(true);
                    c.drawBitmap(current,null,new Rect(0,0,getWidth(),getHeight()),p);
                }else{
                    p.setColor(Color.rgb(89,170,103));p.setTextSize(Math.max(15f,getWidth()/26f));
                    c.drawText(status,Math.max(12f,getWidth()/30f),Math.max(35f,getHeight()/12f),p);
                }
            }
        };
        setContentView(display);
        reader=new Thread(()->receive(address),"test9-hud-glasses");
        reader.start();
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
        disconnect();
        if(frame!=null && !frame.isRecycled())frame.recycle();
        frame=null;
        super.onDestroy();
    }
}
