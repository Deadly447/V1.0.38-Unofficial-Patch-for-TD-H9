package net.kg7lbj.h9monitor;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kg7lbj.h9monitor.aprs.Kiss;

/**
 * Keeps a Bluetooth LE connection to the radio's KISS service and feeds every
 * received packet to {@link Repo}. The only thing it ever sends the radio is
 * APRS messages and their acks, which the radio transmits. It never tries to
 * pair (the TD-H9 cannot bond, and asking it to just drops the link).
 *
 * Before each connection attempt it scans for the radio for a few seconds, so
 * Android knows the radio's address type. Without that, a radio running older
 * firmware can only be reached after another app (such as nRF Connect) has
 * seen it in a scan.
 */
@SuppressLint("MissingPermission")   // MainActivity asks for the permissions before starting us
public class RadioService extends Service implements Repo.Listener {
    private static final String TAG = "H9Monitor";
    public static final String ACTION_START = "net.kg7lbj.h9monitor.START";
    public static final String ACTION_STOP = "net.kg7lbj.h9monitor.STOP";

    static final UUID KISS_SERVICE = UUID.fromString("00000001-ba2a-46c9-ae49-01b0961f68bb");
    static final UUID KISS_TX = UUID.fromString("00000002-ba2a-46c9-ae49-01b0961f68bb");
    static final UUID KISS_RX = UUID.fromString("00000003-ba2a-46c9-ae49-01b0961f68bb");
    private static final int CHUNK = 20;   // default ATT MTU 23 - 3; the radio joins the pieces
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final String CHANNEL = "radio";
    private static final int NOTIFY_ID = 1;
    private static final long SCAN_MS = 6000;
    private static final long FIRST_RETRY_MS = 3000, MAX_RETRY_MS = 30000;
    private static final long SETUP_TIMEOUT_MS = 20000;

    private final Handler main = new Handler(Looper.getMainLooper());     // Bluetooth callbacks
    private final Handler timers = new Handler(Looper.getMainLooper());   // retry and timeout timers
    private Repo repo;
    private BluetoothAdapter adapter;
    private BluetoothGatt gatt;
    private BluetoothLeScanner scanner;
    private boolean scanning;
    private boolean wanted;
    private long retryMs = FIRST_RETRY_MS;
    private final Kiss kiss = new Kiss();
    private int attempt;          // ignores callbacks from a connection we already gave up on
    private long lastNotifyUpdate;
    private BluetoothGattCharacteristic tx;
    private final ArrayDeque<byte[]> txQueue = new ArrayDeque<>();
    private boolean writing;

    public static void start(Context c) {
        Intent i = new Intent(c, RadioService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    public static void stop(Context c) {
        c.startService(new Intent(c, RadioService.class).setAction(ACTION_STOP));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        repo = Repo.get(this);
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm == null ? null : bm.getAdapter();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Radio connection",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        repo.addListener(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            shutDown("Stopped");
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        Notification n = notification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFY_ID, n);
        }
        if (!wanted) {
            wanted = true;
            retryMs = FIRST_RETRY_MS;
            begin();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        shutDown("Stopped");
        repo.removeListener(this);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---- connection steps: scan -> connect -> discover -> subscribe ----

    private void begin() {
        timers.removeCallbacksAndMessages(null);
        if (!wanted) return;
        String mac = repo.radioAddress();
        if (mac == null || !BluetoothAdapter.checkBluetoothAddress(mac)) {
            repo.setState(Repo.State.STOPPED, "Choose your radio first");
            wanted = false;
            stopForeground(true);
            stopSelf();
            return;
        }
        if (adapter == null || !adapter.isEnabled()) {
            waitAndRetry("Bluetooth is off");
            return;
        }
        attempt++;
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            connect(adapter.getRemoteDevice(mac));
            return;
        }
        repo.setState(Repo.State.SCANNING, "Looking for " + label());
        final int myAttempt = attempt;
        // Match by address or by advertised name (either one is enough). The name
        // filter still works if the phone's address filter assumes the wrong type.
        List<ScanFilter> filters = new ArrayList<>();
        filters.add(new ScanFilter.Builder().setDeviceAddress(mac).build());
        String name = repo.radioName();
        if (name != null) filters.add(new ScanFilter.Builder().setDeviceName(name).build());
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        try {
            scanning = true;
            scanner.startScan(filters, settings, scanCallback);
        } catch (RuntimeException e) {
            scanning = false;
            Log.w(TAG, "scan failed to start", e);
        }
        // If the scan never sees it (some phones filter oddly), try connecting anyway.
        timers.postDelayed(() -> {
            if (attempt != myAttempt || gatt != null) return;
            stopScan();
            connect(adapter.getRemoteDevice(mac));
        }, SCAN_MS);
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            main.post(() -> found(result.getDevice()));
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            if (!results.isEmpty()) main.post(() -> found(results.get(0).getDevice()));
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.w(TAG, "scan failed " + errorCode);
        }
    };

    private void found(BluetoothDevice d) {
        if (!scanning || gatt != null) return;
        String mac = repo.radioAddress();
        if (mac == null || !mac.equalsIgnoreCase(d.getAddress())) return;
        stopScan();
        connect(d);   // the scanned device carries the right address type
    }

    private void stopScan() {
        if (!scanning) return;
        scanning = false;
        try {
            if (scanner != null && adapter.isEnabled()) scanner.stopScan(scanCallback);
        } catch (RuntimeException ignored) {
            // Bluetooth turned off mid-scan
        }
    }

    private void connect(BluetoothDevice d) {
        if (!wanted) return;
        repo.setState(Repo.State.CONNECTING, "Connecting to " + label());
        kiss.reset();
        final int myAttempt = attempt;
        if (Build.VERSION.SDK_INT >= 23) {
            gatt = d.connectGatt(this, false, callback(myAttempt), BluetoothDevice.TRANSPORT_LE);
        } else {
            gatt = d.connectGatt(this, false, callback(myAttempt));
        }
        if (gatt == null) {
            waitAndRetry("Could not start a connection");
            return;
        }
        // Give the whole setup a time limit, then start over.
        timers.postDelayed(() -> {
            if (attempt == myAttempt && repo.state() != Repo.State.CONNECTED) {
                dropGatt();
                waitAndRetry("Radio did not answer");
            }
        }, SETUP_TIMEOUT_MS);
    }

    private BluetoothGattCallback callback(final int myAttempt) {
        return new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
                main.post(() -> {
                    if (attempt != myAttempt) { g.close(); return; }
                    if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                        repo.setState(Repo.State.CONNECTING, "Connected, reading services");
                        // a short pause helps some phones finish link setup first
                        main.postDelayed(() -> { if (attempt == myAttempt) g.discoverServices(); }, 300);
                    } else {
                        dropGatt();
                        waitAndRetry(newState == BluetoothProfile.STATE_DISCONNECTED && status == 19
                                ? "Radio closed the connection" : "Connection lost (" + status + ")");
                    }
                });
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt g, int status) {
                main.post(() -> {
                    if (attempt != myAttempt) return;
                    BluetoothGattService s = g.getService(KISS_SERVICE);
                    BluetoothGattCharacteristic rx = s == null ? null : s.getCharacteristic(KISS_RX);
                    if (rx == null) {
                        dropGatt();
                        repo.setState(Repo.State.STOPPED,
                                "This radio has no KISS service. Is the custom firmware installed?");
                        wanted = false;
                        stopForeground(true);
                        stopSelf();
                        return;
                    }
                    tx = s.getCharacteristic(KISS_TX);
                    g.setCharacteristicNotification(rx, true);
                    BluetoothGattDescriptor cccd = rx.getDescriptor(CCCD);
                    if (cccd == null) { subscribed(); return; }
                    byte[] on = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
                    if (Build.VERSION.SDK_INT >= 33) {
                        g.writeDescriptor(cccd, on);
                    } else {
                        cccd.setValue(on);
                        g.writeDescriptor(cccd);
                    }
                });
            }

            @Override
            public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
                main.post(() -> {
                    if (attempt != myAttempt) return;
                    if (status == BluetoothGatt.GATT_SUCCESS) subscribed();
                    else { dropGatt(); waitAndRetry("Radio refused notifications (" + status + ")"); }
                });
            }

            @Override
            public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
                main.post(() -> {
                    if (attempt != myAttempt) return;
                    writing = false;
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        Log.w(TAG, "write failed " + status);
                        txQueue.clear();   // a partial frame is useless; the message will be retried
                    }
                    writeNext();
                });
            }

            // Android 13 and later
            @Override
            public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
                if (KISS_RX.equals(c.getUuid())) received(myAttempt, value.clone());
            }

            // Android 12 and earlier
            @Override
            @SuppressWarnings("deprecation")
            public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
                if (Build.VERSION.SDK_INT >= 33) return;   // handled above
                byte[] v = c.getValue();
                if (v != null && KISS_RX.equals(c.getUuid())) received(myAttempt, v.clone());
            }
        };
    }

    private void subscribed() {
        retryMs = FIRST_RETRY_MS;
        repo.setState(Repo.State.CONNECTED, "Listening on " + label());
        updateNotification(true);
        if (tx != null) repo.setTransmitter(this::transmit);
    }

    /** Queues one AX.25 frame for the radio to transmit (KISS-wrapped, in 20-byte writes). */
    private boolean transmit(byte[] ax25) {
        if (gatt == null || tx == null || repo.state() != Repo.State.CONNECTED) return false;
        byte[] k = Kiss.encode(ax25);
        for (int i = 0; i < k.length; i += CHUNK) {
            txQueue.add(java.util.Arrays.copyOfRange(k, i, Math.min(k.length, i + CHUNK)));
        }
        writeNext();
        return true;
    }

    @SuppressWarnings("deprecation")
    private void writeNext() {
        if (writing || gatt == null || tx == null) return;
        byte[] piece = txQueue.poll();
        if (piece == null) return;
        boolean ok;
        if (Build.VERSION.SDK_INT >= 33) {
            ok = gatt.writeCharacteristic(tx, piece, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                    == android.bluetooth.BluetoothStatusCodes.SUCCESS;
        } else {
            tx.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            tx.setValue(piece);
            ok = gatt.writeCharacteristic(tx);
        }
        if (ok) {
            writing = true;
        } else {
            // the stack is busy; try this piece again shortly
            txQueue.addFirst(piece);
            timers.postDelayed(this::writeNext, 50);
        }
    }

    private void received(int myAttempt, byte[] value) {
        main.post(() -> {
            if (attempt != myAttempt) return;
            long now = System.currentTimeMillis();
            for (byte[] frame : kiss.feed(value)) repo.addFrame(now, frame);
        });
    }

    private void dropGatt() {
        repo.setTransmitter(null);
        txQueue.clear();
        writing = false;
        tx = null;
        if (gatt != null) {
            try {
                gatt.disconnect();
                gatt.close();
            } catch (RuntimeException ignored) {
                // already closed
            }
            gatt = null;
        }
    }

    private void waitAndRetry(String why) {
        stopScan();
        if (!wanted) return;
        attempt++;
        repo.setState(Repo.State.WAITING, why + ". Trying again in " + (retryMs / 1000) + " s");
        timers.removeCallbacksAndMessages(null);
        timers.postDelayed(this::begin, retryMs);
        retryMs = Math.min(MAX_RETRY_MS, retryMs * 2);
    }

    private void shutDown(String why) {
        wanted = false;
        attempt++;
        timers.removeCallbacksAndMessages(null);
        stopScan();
        dropGatt();
        if (repo != null) repo.setState(Repo.State.STOPPED, why);
    }

    private String label() {
        String n = repo.radioName();
        return n != null ? n : repo.radioAddress();
    }

    // ---- notification ----

    @Override
    public void onRepoChanged() {
        updateNotification(false);
    }

    private void updateNotification(boolean now) {
        if (!wanted) return;
        long t = System.currentTimeMillis();
        if (!now && t - lastNotifyUpdate < 5000) return;
        lastNotifyUpdate = t;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFY_ID, notification());
    }

    private Notification notification() {
        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, RadioService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        int n = repo.store.size();
        return b.setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(repo.stateText())
                .setContentText(n + (n == 1 ? " station" : " stations") + " heard")
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .build();
    }
}
