package ru.rokidhud.phone;

import android.Manifest;
import android.app.*;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.*;
import ru.rokidhud.wire.HudMessage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

public class BluetoothHudService extends Service {
    public static volatile String status = "Остановлено";
    public static volatile String debugText = "Нет уведомления Яндекса";
    public static volatile boolean running = false;
    public static volatile long demoUntil = 0L;
    public static volatile Integer speedKmh = null;
    private static final String CHANNEL = "yandexhud.bt";
    private final AtomicBoolean live = new AtomicBoolean(false);
    private BluetoothServerSocket server;
    private BluetoothSocket client;
    private Thread loop;
    private LocationManager locationManager;
    private LocationListener locationListener;

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Yandex HUD Bluetooth", NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent != null && "stop".equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        startForeground(501, new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_directions)
                .setContentTitle("Yandex HUD").setContentText("Передача HUD на Rokid по Bluetooth")
                .setOngoing(true).build());
        if (live.compareAndSet(false, true)) {
            running=true; status="Запуск Bluetooth";
            beginSpeed();
            loop = new Thread(this::runServer, "hud-bt-spp");
            loop.start();
        }
        return START_STICKY;
    }
    @SuppressWarnings("MissingPermission")
    private void beginSpeed() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) return;
        locationManager = (LocationManager)getSystemService(LOCATION_SERVICE);
        locationListener = new LocationListener() {
            public void onLocationChanged(Location l) { speedKmh = l.hasSpeed() ? Math.round(l.getSpeed()*3.6f) : null; }
        };
        try { locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0f, locationListener, Looper.getMainLooper()); }
        catch (Exception e) { speedKmh=null; }
    }
    @SuppressWarnings("MissingPermission")
    private void runServer() {
        try {
            BluetoothManager bm = getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
            if (adapter == null || !adapter.isEnabled()) { status="Bluetooth выключен"; return; }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED) {
                status="Нет разрешения Bluetooth"; return;
            }
            server = adapter.listenUsingRfcommWithServiceRecord("Yandex Rokid HUD", HudMessage.SERVICE_UUID);
            status="Ожидание очков";
            while (live.get()) {
                try {
                    client = server.accept();
                    status="Очки подключены";
                    try (BufferedWriter out=new BufferedWriter(new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8))) {
                        while (live.get() && client.isConnected()) {
                            HudMessage msg = packet();
                            out.write(msg.encode()); out.write("\n"); out.flush();
                            Thread.sleep(1000);
                        }
                    } finally {
                        try { client.close(); } catch(Exception ignored) {}
                        client = null;
                        if (live.get()) status="Ожидание переподключения";
                    }
                } catch (IOException e) {
                    if (live.get()) status="Переподключение: " + e.getMessage();
                }
            }
        } catch (Exception e) { status="Ошибка Bluetooth: " + e.getMessage(); }
        finally { try { if (server!=null) server.close(); } catch(Exception ignored) {} }
    }
    private HudMessage packet() {
        if (System.currentTimeMillis() < demoUntil) {
            HudMessage m = new HudMessage(); m.state="demo"; m.maneuver="RIGHT";
            m.distance="200 м"; m.street="Проверка связи"; m.speedKmh=42; m.limitKmh=60; m.source="demo"; return m;
        }
        NavData data = NavData.current;
        if (data != null && System.currentTimeMillis()-data.receivedMs < 35000) return data.toWire(speedKmh);
        HudMessage idle = new HudMessage();
        idle.speedKmh=speedKmh;
        return idle;
    }
    @Override public void onDestroy() {
        running=false; live.set(false); demoUntil=0; speedKmh=null; status="Остановлено";
        if (locationManager!=null && locationListener!=null) locationManager.removeUpdates(locationListener);
        try { if(client!=null)client.close(); }catch(Exception ignored){}
        try { if(server!=null)server.close(); }catch(Exception ignored){}
        if(loop!=null)loop.interrupt();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
