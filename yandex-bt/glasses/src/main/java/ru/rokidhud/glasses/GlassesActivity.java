package ru.rokidhud.glasses;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import ru.rokidhud.wire.HudMessage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class GlassesActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private BluetoothSocket socket;
    private Thread worker;
    private volatile boolean connectedWanted = false;
    private String selectedAddress;
    private HudView hud;
    private String status = "Нет связи";
    private HudMessage message = new HudMessage();

    private boolean permission() {
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;
    }
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
            WindowManager.LayoutParams.FLAG_FULLSCREEN);
        selectedAddress = getPreferences(MODE_PRIVATE).getString("phone_mac", null);
        if (!permission()) requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT},1);
        else begin();
    }
    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(req,perms,grants);
        begin();
    }
    private void begin() {
        if (selectedAddress != null && permission()) connect(selectedAddress);
        else chooser();
    }
    @SuppressWarnings("MissingPermission")
    private void chooser() {
        connectedWanted = false;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(18,18,18,18);
        root.setBackgroundColor(Color.BLACK);
        TextView header = new TextView(this);
        header.setText("Yandex HUD · выбери телефон\nСначала включи передачу на телефоне");
        header.setTextColor(Color.GREEN); header.setTextSize(20); root.addView(header);
        Button settings = new Button(this); settings.setText("Настройки Bluetooth");
        settings.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
        root.addView(settings);
        Button refresh = new Button(this); refresh.setText("Обновить список"); refresh.setOnClickListener(v->chooser());
        root.addView(refresh);
        if (!permission()) {
            TextView t=new TextView(this); t.setText("Разреши доступ Bluetooth"); root.addView(t);
            Button perm=new Button(this); perm.setText("Разрешить"); perm.setOnClickListener(v->
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT},1)); root.addView(perm);
        } else {
            BluetoothManager bm=getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter=bm==null?null:bm.getAdapter();
            if (adapter==null || !adapter.isEnabled()) {
                TextView t=new TextView(this); t.setText("Bluetooth выключен"); root.addView(t);
            } else {
                Set<BluetoothDevice> devices=adapter.getBondedDevices();
                if (devices.isEmpty()) {
                    TextView t=new TextView(this); t.setText("Нет сопряжённых устройств"); root.addView(t);
                }
                for (BluetoothDevice d:devices) {
                    String addr=d.getAddress(), name=d.getName();
                    Button item=new Button(this); item.setAllCaps(false);
                    item.setText((name==null?"Устройство":name)+"\n"+addr);
                    item.setOnClickListener(v->connect(addr)); root.addView(item);
                }
            }
        }
        ScrollView scroll = new ScrollView(this); scroll.addView(root); setContentView(scroll);
    }
    private synchronized void connect(String address) {
        disconnect();
        selectedAddress=address;
        getPreferences(MODE_PRIVATE).edit().putString("phone_mac",address).apply();
        connectedWanted=true;
        status="Подключение…"; message=new HudMessage();
        hud=new HudView(this); setContentView(hud);
        worker = new Thread(() -> connectionLoop(address),"yandex-hud-client");
        worker.start();
    }
    @SuppressWarnings("MissingPermission")
    private void connectionLoop(String address) {
        while(connectedWanted) {
            try {
                BluetoothManager bm=getSystemService(BluetoothManager.class);
                BluetoothAdapter adapter=bm==null?null:bm.getAdapter();
                if (adapter==null || !adapter.isEnabled() || !permission()) throw new IOException("Bluetooth недоступен");
                BluetoothDevice phone=adapter.getRemoteDevice(address);
                BluetoothSocket candidate=phone.createRfcommSocketToServiceRecord(HudMessage.SERVICE_UUID);
                socket=candidate;
                candidate.connect();
                updateStatus("Связь установлена");
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(candidate.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while(connectedWanted && (line=reader.readLine())!=null) {
                        HudMessage next=HudMessage.decode(line);
                        if (next.timestampMs>0 && Math.abs(System.currentTimeMillis()-next.timestampMs)>20000L)
                            continue; // never show stale guidance
                        main.post(() -> { message=next; status="Связь установлена"; if(hud!=null)hud.invalidate(); });
                    }
                }
            } catch(Exception e) {
                updateStatus("Нет связи · повторное подключение");
            } finally {
                try { if(socket!=null)socket.close(); } catch(Exception ignored) {}
                socket=null;
            }
            if(connectedWanted) {
                try { Thread.sleep(2500); } catch(InterruptedException ignored) { break; }
            }
        }
    }
    private void updateStatus(String s) {
        main.post(() -> {status=s; if(hud!=null)hud.invalidate();});
    }
    private synchronized void disconnect() {
        connectedWanted=false;
        try { if(socket!=null) socket.close(); } catch(Exception ignored) {}
        if(worker!=null)worker.interrupt();
        worker=null; socket=null;
    }
    @Override public void onBackPressed() { disconnect(); chooser(); }
    @Override protected void onDestroy() { disconnect(); super.onDestroy(); }

    private final class HudView extends View {
        private final android.graphics.Paint p = new android.graphics.Paint(3);
        HudView(Context ctx) { super(ctx); setBackgroundColor(Color.BLACK);
            setOnLongClickListener(v->{ disconnect(); chooser(); return true; });
        }
        private void text(android.graphics.Canvas c,String v,float x,float y,int size,int color,boolean bold) {
            p.setColor(color); p.setStyle(android.graphics.Paint.Style.FILL); p.setStrokeWidth(1);
            p.setTypeface(bold?android.graphics.Typeface.DEFAULT_BOLD:android.graphics.Typeface.DEFAULT);
            p.setTextSize(size); c.drawText(v,x,y,p);
        }
        private void line(android.graphics.Canvas c,float x1,float y1,float x2,float y2) {
            c.drawLine(x1,y1,x2,y2,p);
        }
        private void arrow(android.graphics.Canvas c, String m) {
            c.save(); c.translate(416,73);
            p.setColor(Color.rgb(95,255,125)); p.setStrokeWidth(7); p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeCap(android.graphics.Paint.Cap.ROUND); p.setStrokeJoin(android.graphics.Paint.Join.ROUND);
            switch(m) {
                case "RIGHT": line(c,-20,28,-20,-8); line(c,-20,-8,24,-8);
                    line(c,9,-24,24,-8);line(c,9,8,24,-8);break;
                case "LEFT": line(c,20,28,20,-8); line(c,20,-8,-24,-8);
                    line(c,-9,-24,-24,-8);line(c,-9,8,-24,-8);break;
                case "SLIGHT_LEFT": line(c,8,28,-18,-18);line(c,-19,-1,-18,-18);
                    line(c,-1,-17,-18,-18);break;
                case "SLIGHT_RIGHT":line(c,-8,28,18,-18);line(c,19,-1,18,-18);
                    line(c,1,-17,18,-18);break;
                case "STRAIGHT":line(c,0,28,0,-27);line(c,-15,-10,0,-27);line(c,15,-10,0,-27);break;
                case "UTURN":line(c,23,27,23,-12); c.drawArc(-23,-25,23,15,180,180,false,p);
                    line(c,-23,-8,-23,24);line(c,-37,8,-23,24);line(c,-9,8,-23,24);break;
                case "ROUNDABOUT":c.drawArc(-24,-24,24,24,25,290,false,p);
                    line(c,19,-14,25,-25);line(c,19,-14,5,-15);break;
                case "ARRIVE":c.drawCircle(0,0,14,p);break;
                default:text(c,"?",-12,10,33,Color.GRAY,true);
            }
            c.restore();
        }
        @Override protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawColor(Color.BLACK);
            canvas.save(); canvas.scale(getWidth()/480f, getHeight()/640f);
            HudMessage h=message;
            boolean live="active".equals(h.state)||"demo".equals(h.state);
            int green=Color.rgb(95,255,125), gray=Color.rgb(105,130,110);
            if(live) {
                text(canvas,h.distance.isEmpty()?"—":h.distance,27,78,43,green,true);
                arrow(canvas,h.maneuver);
                if(!h.street.isEmpty())text(canvas,h.street,28,112,16,gray,false);
                if("demo".equals(h.state))text(canvas,"ТЕСТ",28,145,18,gray,true);
            } else {
                text(canvas,"МАРШРУТ НЕ ПОЛУЧЕН",25,65,17,gray,false);
            }
            if(h.speedKmh!=null) {
                text(canvas,String.valueOf(h.speedKmh),28,588,50,green,true);
                text(canvas,"км/ч",29,615,16,gray,false);
            }
            // A blank limit is shown as unknown, never invented.
            p.setColor(h.limitKmh==null?gray:Color.RED);
            p.setStyle(android.graphics.Paint.Style.STROKE); p.setStrokeWidth(5);
            canvas.drawCircle(421,573,37,p);
            text(canvas,h.limitKmh==null?"—":String.valueOf(h.limitKmh),
                h.limitKmh==null?411:(h.limitKmh<100?400:392),584,31,Color.WHITE,true);
            if(!"Связь установлена".equals(status)) text(canvas,status,25,625,13,gray,false);
            canvas.restore();
        }
    }
}
