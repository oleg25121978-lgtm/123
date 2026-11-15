package com.rokid.watchcursor;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.UUID;

/**
 * Experimental Android BLE peripheral with RokidKeyboard-compatible commands.
 * The glasses-side RokidKey Receiver acts as central and subscribes to notifications.
 * No Bluetooth HID is needed. Works only if the watch firmware allows BLE advertising
 * and a GATT server. Neither is guaranteed on Huawei Watch 4 Pro.
 */
@SuppressWarnings("deprecation")
public final class MainActivity extends Activity {
    private static final UUID SERVICE = UUID.fromString("6e400001-b5a3-f393-e0a9-77656b657962");
    private static final UUID TX = UUID.fromString("6e400003-b5a3-f393-e0a9-77656b657962");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final int PERMISSION_CODE = 151;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<byte[]> out = new ArrayDeque<>();
    private BluetoothGattServer gatt;
    private BluetoothGattCharacteristic txChar;
    private BluetoothDevice connectedDevice;
    private BluetoothLeAdvertiser advertiser;
    private AdvertiseCallback adCallback;
    private boolean started;
    private boolean subscribed;
    private boolean sending;
    private TextView status;
    private TextView detail;
    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (!started || !subscribed) return;
            send(new byte[]{0}, "PING");
            handler.postDelayed(this, 5000);
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(12), dp(8), dp(12), dp(20));
        column.setBackgroundColor(Color.rgb(20, 25, 30));
        scroll.addView(column);
        column.addView(label("ROKID CURSOR · Watch 4 Pro", 17, Color.WHITE));
        status = label("BLE: не запущен", 15, Color.YELLOW);
        column.addView(status);
        column.addView(button("Включить соединение", this::checkAndStart));
        column.addView(button("Остановить", this::stopBle));
        column.addView(label("Веди пальцем по площадке для движения курсора. Тап — клик.", 12, Color.LTGRAY));
        column.addView(new Touchpad());
        column.addView(button("Тест: курсор вправо", () -> move(35, 0)));
        column.addView(button("Выбрать", () -> send(new byte[]{4, 0}, "Клик")));
        column.addView(button("Назад", () -> send(new byte[]{8, 0}, "Назад")));
        column.addView(button("Home на очках", () -> send(new byte[]{8, 1}, "Home → Rokid (08 01)")));
        column.addView(label("Физическая Home может перехватываться системой часов. Если приложение не видит нажатия, передать его по BLE нельзя.", 12, Color.LTGRAY));
        detail = label("На очках нужен RokidKey Receiver с включённой службой Accessibility.", 12, Color.LTGRAY);
        column.addView(detail);
        setContentView(scroll);
        log("Модель: " + Build.MANUFACTURER + " / " + Build.MODEL);
        log("Android SDK: " + Build.VERSION.SDK_INT);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            log("Часы передали кнопку: code=" + event.getKeyCode()
                    + " (" + KeyEvent.keyCodeToString(event.getKeyCode()) + ")");
            // No interception: the watch retains its normal system button behavior.
        }
        return super.dispatchKeyEvent(event);
    }

    private int dp(int px) { return Math.round(px * getResources().getDisplayMetrics().density); }

    private TextView label(String value, int size, int color) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextColor(color);
        tv.setTextSize(size);
        tv.setPadding(0, dp(5), 0, dp(5));
        return tv;
    }

    private Button button(String title, Runnable action) {
        Button b = new Button(this);
        b.setText(title);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private void state(String message, int color) {
        handler.post(() -> { if (status != null) {status.setText(message); status.setTextColor(color);} });
    }

    private void log(String message) {
        handler.post(() -> { if (detail != null) detail.setText(message); });
    }

    private void checkAndStart() {
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_ADVERTISE,
                        Manifest.permission.BLUETOOTH_CONNECT}, PERMISSION_CODE);
                return;
            }
        }
        startBle();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PERMISSION_CODE) return;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                state("Нужно разрешить Bluetooth", Color.RED);
                return;
            }
        }
        if (grantResults.length >= 2) startBle();
    }

    @SuppressWarnings("MissingPermission")
    private void startBle() {
        stopBle();
        try {
            BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                state("Включи Bluetooth на часах", Color.RED);
                return;
            }
            if (!adapter.isMultipleAdvertisementSupported()) {
                state("BLE Peripheral не поддерживается", Color.RED);
                log("Android API: isMultipleAdvertisementSupported() = false");
                return;
            }
            advertiser = adapter.getBluetoothLeAdvertiser();
            if (advertiser == null) {
                state("BLE Advertiser отсутствует", Color.RED);
                return;
            }
            gatt = manager.openGattServer(this, callbacks);
            if (gatt == null) {
                state("GATT Server недоступен", Color.RED);
                return;
            }
            BluetoothGattService service = new BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY);
            txChar = new BluetoothGattCharacteristic(TX, BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                    BluetoothGattCharacteristic.PERMISSION_READ);
            BluetoothGattDescriptor descriptor = new BluetoothGattDescriptor(CCCD,
                    BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE);
            txChar.addDescriptor(descriptor);
            service.addCharacteristic(txChar);
            started = true;
            state("Создаю BLE-сервис...", Color.YELLOW);
            if (!gatt.addService(service)) {
                state("Ошибка регистрации GATT", Color.RED);
                stopBle();
            }
        } catch (Exception ex) {
            state("BLE ошибка: " + ex.getClass().getSimpleName(), Color.RED);
            log(ex.toString());
        }
    }

    private final BluetoothGattServerCallback callbacks = new BluetoothGattServerCallback() {
        @Override public void onServiceAdded(int result, BluetoothGattService service) {
            if (result == BluetoothGatt.GATT_SUCCESS) handler.post(() -> startAdvertising());
            else state("GATT error: " + result, Color.RED);
        }

        @Override public void onConnectionStateChange(BluetoothDevice device, int statusCode, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED && statusCode == BluetoothGatt.GATT_SUCCESS) {
                connectedDevice = device;
                state("BLE соединено. Жду подписку...", Color.YELLOW);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                subscribed = false;
                handler.removeCallbacks(heartbeat);
                sending = false;
                connectedDevice = null;
                out.clear();
                state("Ожидаю Rokid...", Color.YELLOW);
            }
        }

        @Override public void onDescriptorReadRequest(BluetoothDevice device, int requestId, int offset,
                BluetoothGattDescriptor descriptor) {
            if (gatt == null) return;
            byte[] value = subscribed ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE :
                    BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
            if (offset > value.length) {
                gatt.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null);
            } else {
                gatt.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                        Arrays.copyOfRange(value, offset, value.length));
            }
        }

        @Override public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                BluetoothGattDescriptor descriptor, boolean preparedWrite, boolean responseNeeded,
                int offset, byte[] value) {
            boolean valid = CCCD.equals(descriptor.getUuid()) && offset == 0 && !preparedWrite &&
                    (Arrays.equals(value, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ||
                     Arrays.equals(value, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE));
            if (responseNeeded && gatt != null)
                gatt.sendResponse(device, requestId,
                        valid ? BluetoothGatt.GATT_SUCCESS : BluetoothGatt.GATT_FAILURE, offset, null);
            if (!valid) return;
            handler.post(() -> {
                connectedDevice = device;
                subscribed = Arrays.equals(value, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                if (subscribed) {
                    state("ROKID ПОДКЛЮЧЁН ✓", Color.GREEN);
                    log("Проведи пальцем по тачпаду часов.");
                    send(new byte[]{0}, "PING");
                    handler.removeCallbacks(heartbeat);
                    handler.postDelayed(heartbeat, 5000);
                } else {
                    state("Подписка BLE отключена", Color.YELLOW);
                }
            });
        }

        @Override public void onNotificationSent(BluetoothDevice device, int result) {
            handler.post(() -> {
                sending = false;
                if (result != BluetoothGatt.GATT_SUCCESS) log("Notify error: " + result);
                flush();
            });
        }
    };

    @SuppressWarnings("MissingPermission")
    private void startAdvertising() {
        if (!started || advertiser == null) return;
        try {
            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                    .setConnectable(true).build();
            AdvertiseData data = new AdvertiseData.Builder()
                    .addServiceUuid(new ParcelUuid(SERVICE))
                    .setIncludeDeviceName(false).build();
            adCallback = new AdvertiseCallback() {
                @Override public void onStartSuccess(AdvertiseSettings s) {
                    state("Ожидаю подключение очков...", Color.YELLOW);
                }
                @Override public void onStartFailure(int code) {
                    state("BLE ADV ошибка: " + code, Color.RED);
                    log("Advertiser error code: " + code);
                }
            };
            advertiser.startAdvertising(settings, data, adCallback);
        } catch (Exception ex) {
            state("Ошибка BLE рекламы", Color.RED);
            log(ex.toString());
        }
    }

    @SuppressWarnings("MissingPermission")
    private void stopBle() {
        started = false;
        handler.removeCallbacks(heartbeat);
        subscribed = false;
        sending = false;
        connectedDevice = null;
        out.clear();
        if (advertiser != null && adCallback != null) {
            try { advertiser.stopAdvertising(adCallback); } catch (Exception ignored) {}
        }
        adCallback = null;
        if (gatt != null) {
            try { gatt.close(); } catch (Exception ignored) {}
        }
        gatt = null;
        txChar = null;
        state("BLE: остановлен", Color.LTGRAY);
    }

    private void move(int dx, int dy) {
        send(new byte[]{3, (byte)(dx >>> 8), (byte)dx,
                (byte)(dy >>> 8), (byte)dy}, null);
    }

    private void send(byte[] packet, String message) {
        if (!subscribed || connectedDevice == null || gatt == null || txChar == null) {
            if (message != null) log(message + ": сначала дождись подключения.");
            return;
        }
        if (out.size() >= 40) {
            if (message != null) log("Очередь BLE заполнена: " + message);
            return;
        }
        out.add(Arrays.copyOf(packet, packet.length));
        if (message != null && !"PING".equals(message)) log("BLE команда в очереди: " + message);
        flush();
    }

    @SuppressWarnings("MissingPermission")
    private void flush() {
        if (sending || out.isEmpty() || !subscribed || gatt == null ||
                connectedDevice == null || txChar == null) return;
        byte[] payload = out.removeFirst();
        txChar.setValue(payload);
        try {
            sending = gatt.notifyCharacteristicChanged(connectedDevice, txChar, false);
            if (!sending) log("Не удалось отправить BLE notify");
        } catch (Exception ex) {
            sending = false;
            log(ex.toString());
        }
    }

    private final class Touchpad extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float lastX, lastY, downX, downY;
        private long downTime, lastSend;
        private boolean moved;
        Touchpad() { super(MainActivity.this); setMinimumHeight(dp(175)); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            setMeasuredDimension(MeasureSpec.getSize(widthSpec), dp(175));
        }
        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            paint.setColor(Color.rgb(39, 57, 73));
            canvas.drawRoundRect(dp(2), dp(2), w - dp(2), h - dp(2), dp(15), dp(15), paint);
            paint.setColor(Color.WHITE);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(dp(17));
            canvas.drawText("ТАЧПАД", w / 2, h / 2, paint);
            paint.setTextSize(dp(11));
            canvas.drawText("Веди — курсор · Тап — клик", w / 2, h / 2 + dp(22), paint);
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    lastX = downX = e.getX();
                    lastY = downY = e.getY();
                    downTime = lastSend = SystemClock.elapsedRealtime();
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (Math.hypot(e.getX() - downX, e.getY() - downY) >= dp(7)) moved = true;
                    long now = SystemClock.elapsedRealtime();
                    if (moved && now - lastSend >= 35) {
                        int dx = Math.max(-160, Math.min(160, Math.round((e.getX() - lastX) * 1.8f)));
                        int dy = Math.max(-160, Math.min(160, Math.round((e.getY() - lastY) * 1.8f)));
                        if (dx != 0 || dy != 0) move(dx, dy);
                        lastX = e.getX(); lastY = e.getY(); lastSend = now;
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!moved && SystemClock.elapsedRealtime() - downTime <= 350)
                        send(new byte[]{4, 0}, "Клик");
                    else if (moved) {
                        int dx = Math.round((e.getX() - lastX) * 1.8f);
                        int dy = Math.round((e.getY() - lastY) * 1.8f);
                        if (dx != 0 || dy != 0) move(dx, dy);
                    }
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
            }
            return true;
        }
    }

    @Override protected void onDestroy() {
        stopBle();
        super.onDestroy();
    }
}
