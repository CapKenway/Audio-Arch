package com.audioarch;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Lightweight, universal Classic Bluetooth HFP RFCOMM battery monitor for Android 4.4.4 (API 19).
 * Supports Xiaomi Buds 6 Play, Vivo Buds, and generic TWS earbuds via HFP 1.7 (AT+BIEV),
 * Apple Accessory Protocol (AT+XAPL / AT+IPHONEACCEV), and Plantronics XEVENT.
 * Zero polling, zero BLE, zero idle CPU (blocking readLine on Linux RFCOMM socket).
 */
public class TwsBatteryManager {
    private static final String TAG = "TwsBatteryManager";

    // Standard Hands-Free (HFP) and Headset (HSP) UUIDs
    private static final UUID HFP_UUID = UUID.fromString("0000111e-0000-1000-8000-00805f9b34fb");
    private static final UUID HSP_UUID = UUID.fromString("00001108-0000-1000-8000-00805f9b34fb");

    public interface BatteryListener {
        /**
         * Invoked whenever the earbuds report an updated battery level.
         *
         * @param device       The connected BluetoothDevice
         * @param batteryLevel Battery percentage from 0 to 100
         * @param isCharging   True if the earbuds report charging state, false otherwise
         * @param details      Optional details string (e.g., L:80% R:80% or ⚡)
         */
        void onBatteryChanged(BluetoothDevice device, int batteryLevel, boolean isCharging, String details);
    }

    private final Context context;
    private final BatteryListener listener;
    private final BluetoothAdapter btAdapter;

    private volatile HfpSocketWorker activeWorker;
    private volatile boolean isRunning = false;
    private volatile int lastBatteryLevel = -1;
    private volatile boolean lastChargingState = false;
    private volatile String lastDetails = "";

    public TwsBatteryManager(Context context, BatteryListener listener) {
        try {
            if (android.os.Looper.getMainLooper() == null) {
                android.os.Looper.prepareMainLooper();
            }
        } catch (Throwable ignored) {}

        if (context != null) {
            this.context = context.getApplicationContext();
        } else {
            this.context = getSystemContextQuietly();
        }
        this.listener = listener;
        this.btAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    private static Context getSystemContextQuietly() {
        // Standalone app_process runs without a system Context; direct RFCOMM and
        // BluetoothAdapter.getBondedDevices() work independently without Context.
        return null;
    }

    /**
     * Starts listening for A2DP connections and attaches the HFP battery reader to any
     * currently connected A2DP earbuds.
     */
    public synchronized void start() {
        if (isRunning || btAdapter == null) return;
        isRunning = true;

        if (context != null) {
            try {
                IntentFilter filter = new IntentFilter();
                filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
                filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
                context.registerReceiver(btReceiver, filter);
            } catch (Throwable t) {
                Log.w(TAG, "Failed to register broadcast receiver: " + t.getMessage());
            }

            // Check if an A2DP device is already connected when the daemon starts
            try {
                btAdapter.getProfileProxy(context, new BluetoothProfile.ServiceListener() {
                    @Override
                    public void onServiceConnected(int profile, BluetoothProfile proxy) {
                        if (profile == BluetoothProfile.A2DP) {
                            try {
                                List<BluetoothDevice> devices = proxy.getConnectedDevices();
                                if (devices != null && !devices.isEmpty()) {
                                    attachToDevice(devices.get(0));
                                }
                            } finally {
                                btAdapter.closeProfileProxy(BluetoothProfile.A2DP, proxy);
                            }
                        }
                    }

                    @Override
                    public void onServiceDisconnected(int profile) {}
                }, BluetoothProfile.A2DP);
            } catch (Throwable t) {
                Log.w(TAG, "getProfileProxy A2DP failed: " + t.getMessage());
            }
        }

        // Direct fallback: check bonded devices for connected headset
        checkConnectedDevices();
    }

    /**
     * Checks bonded devices and attaches to the first connected device if not already attached.
     */
    public synchronized void checkConnectedDevices() {
        if (!isRunning || btAdapter == null || !btAdapter.isEnabled()) return;
        if (activeWorker != null && activeWorker.isAlive()) return;

        try {
            Set<BluetoothDevice> bonded = btAdapter.getBondedDevices();
            if (bonded != null) {
                for (BluetoothDevice dev : bonded) {
                    try {
                        Method m = dev.getClass().getMethod("isConnected");
                        Boolean connected = (Boolean) m.invoke(dev);
                        if (connected != null && connected) {
                            attachToDevice(dev);
                            return;
                        }
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Finds a bonded device by name and attaches to it.
     */
    public synchronized void attachToDeviceByName(String deviceName) {
        if (!isRunning || btAdapter == null || deviceName == null || deviceName.isEmpty()) return;
        try {
            Set<BluetoothDevice> bonded = btAdapter.getBondedDevices();
            if (bonded != null) {
                for (BluetoothDevice dev : bonded) {
                    if (deviceName.equalsIgnoreCase(dev.getName())) {
                        attachToDevice(dev);
                        return;
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Stops the battery monitor and closes any open RFCOMM socket.
     */
    public synchronized void stop() {
        if (!isRunning) return;
        isRunning = false;
        if (context != null) {
            try {
                context.unregisterReceiver(btReceiver);
            } catch (Throwable ignored) {}
        }
        stopActiveWorker();
    }

    public int getLastBatteryLevel() {
        return lastBatteryLevel;
    }

    public boolean isLastChargingState() {
        return lastChargingState;
    }

    public String getLastDetails() {
        return lastDetails;
    }

    private final BroadcastReceiver btReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (device == null) return;

            if (BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED);
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    attachToDevice(device);
                } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                    stopActiveWorker();
                }
            } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                if (activeWorker != null && device.equals(activeWorker.device)) {
                    stopActiveWorker();
                }
            }
        }
    };

    public synchronized void attachToDevice(final BluetoothDevice device) {
        if (!isRunning || device == null) return;
        if (activeWorker != null && activeWorker.isAlive() && device.equals(activeWorker.device)) {
            return; // Already monitoring this device
        }
        stopActiveWorker();

        // Detach Android 4.4.4's built-in HFP service first (if Call Audio wasn't unchecked)
        // so Bluedroid releases the RFCOMM lock while keeping A2DP Media Audio active.
        if (context != null && btAdapter != null) {
            try {
                btAdapter.getProfileProxy(context, new BluetoothProfile.ServiceListener() {
                    @Override
                    public void onServiceConnected(int profile, BluetoothProfile proxy) {
                        if (profile == BluetoothProfile.HEADSET) {
                            try {
                                BluetoothHeadset headset = (BluetoothHeadset) proxy;
                                // PRIORITY_OFF = 0 prevents system HFP from fighting our socket
                                Method setPriority = BluetoothHeadset.class.getMethod(
                                        "setPriority", BluetoothDevice.class, int.class);
                                setPriority.invoke(headset, device, 0);

                                Method disconnect = BluetoothHeadset.class.getMethod(
                                        "disconnect", BluetoothDevice.class);
                                disconnect.invoke(headset, device);
                            } catch (Throwable t) {
                                Log.w(TAG, "System HFP detach skipped: " + t.getMessage());
                            } finally {
                                btAdapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy);
                            }
                            startWorkerForDevice(device);
                        }
                    }

                    @Override
                    public void onServiceDisconnected(int profile) {}
                }, BluetoothProfile.HEADSET);
                return;
            } catch (Throwable t) {
                Log.w(TAG, "getProfileProxy HEADSET failed: " + t.getMessage());
            }
        }

        // Direct fallback: start worker directly
        startWorkerForDevice(device);
    }

    private synchronized void startWorkerForDevice(BluetoothDevice device) {
        if (!isRunning) return;
        stopActiveWorker();
        activeWorker = new HfpSocketWorker(device);
        activeWorker.start();
    }

    public synchronized void stopActiveWorker() {
        if (activeWorker != null) {
            activeWorker.shutdown();
            activeWorker = null;
        }
        lastBatteryLevel = -1;
        lastChargingState = false;
        lastDetails = "";
    }

    private void notifyBatteryUpdate(BluetoothDevice device, int percent, boolean isCharging, String details) {
        int clamped = Math.max(0, Math.min(100, percent));
        this.lastBatteryLevel = clamped;
        this.lastChargingState = isCharging;
        this.lastDetails = (details != null) ? details : (isCharging ? "⚡" : "");
        Log.i(TAG, "TWS Battery [" + device.getName() + "]: " + clamped + "% (" + lastDetails + ")");
        if (listener != null) {
            listener.onBatteryChanged(device, clamped, isCharging, lastDetails);
        }
    }

    /**
     * Background worker thread that opens the RFCOMM socket, performs the HFP 1.7 + Apple
     * SLC handshake, and blocks waiting for battery AT updates.
     */
    private final class HfpSocketWorker extends Thread {
        final BluetoothDevice device;
        private volatile boolean running = true;
        private volatile BluetoothSocket socket;

        HfpSocketWorker(BluetoothDevice device) {
            super("TwsBattery-" + device.getAddress());
            this.device = device;
            setDaemon(true);
        }

        void shutdown() {
            running = false;
            closeQuietly(socket);
            interrupt();
        }

        @Override
        public void run() {
            // Brief initial delay so Bluedroid finishes releasing HFP if it was attached
            sleepQuietly(700);

            int attempt = 0;
            while (running && isRunning && attempt < 4) {
                attempt++;
                BufferedReader reader = null;
                OutputStream out = null;
                try {
                    socket = openRfcommSocketWithFallback(device);
                    if (socket == null) {
                        sleepQuietly(2000);
                        continue;
                    }

                    Log.i(TAG, "Connected HFP battery socket to " + device.getName());
                    // Small 256-byte buffer to minimize RAM overhead on 512MB device
                    reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), "ASCII"), 256);
                    out = socket.getOutputStream();

                    String line;
                    while (running && (line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty()) continue;
                        Log.i(TAG, "AT RX: " + line);
                        handleAtCommand(line, out);
                    }
                } catch (IOException e) {
                    if (running) {
                        Log.d(TAG, "HFP socket closed or read error (attempt " + attempt + "): " + e.getMessage());
                        sleepQuietly(2500);
                    }
                } finally {
                    closeQuietly(reader);
                    closeQuietly(out);
                    closeQuietly(socket);
                }
            }
        }

        private BluetoothSocket openRfcommSocketWithFallback(BluetoothDevice dev) {
            // 1. Try standard HFP UUID (Insecure RFCOMM)
            BluetoothSocket s = tryUuidSocket(dev, HFP_UUID);
            if (s != null) return s;

            // 2. Try HSP UUID
            s = tryUuidSocket(dev, HSP_UUID);
            if (s != null) return s;

            // 3. Fallback to direct RFCOMM channels 1, 2, 3 via reflection
            for (int channel = 1; channel <= 3; channel++) {
                if (!running) return null;
                try {
                    Method m = dev.getClass().getMethod("createInsecureRfcommSocket", int.class);
                    BluetoothSocket rawSock = (BluetoothSocket) m.invoke(dev, channel);
                    rawSock.connect();
                    return rawSock;
                } catch (Throwable t) {
                    // Try next channel
                }
            }
            return null;
        }

        private BluetoothSocket tryUuidSocket(BluetoothDevice dev, UUID uuid) {
            if (!running) return null;
            BluetoothSocket s = null;
            try {
                s = dev.createInsecureRfcommSocketToServiceRecord(uuid);
                s.connect();
                return s;
            } catch (IOException e) {
                closeQuietly(s);
                return null;
            }
        }

        private void handleAtCommand(String line, OutputStream out) throws IOException {
            // 1. HFP Service Level Connection (SLC) Feature Exchange
            // 1791 (0x06FF) includes Bit 8 (256 = HF Indicators for HFP 1.7)
            if (line.startsWith("AT+BRSF=")) {
                sendAt(out, "+BRSF: 1791\r\n\r\nOK");
            }
            // 2. Standard HFP Indicator negotiation
            else if (line.startsWith("AT+CIND=?")) {
                sendAt(out, "+CIND: (\"service\",(0-1)),(\"call\",(0-1)),(\"callsetup\",(0-3)),(\"callheld\",(0-2)),(\"signal\",(0-5)),(\"roam\",(0-1)),(\"battchg\",(0-5))\r\n\r\nOK");
            } else if (line.startsWith("AT+CIND?")) {
                sendAt(out, "+CIND: 1,0,0,0,5,0,5\r\n\r\nOK");
            } else if (line.startsWith("AT+CMER=")) {
                sendAt(out, "OK");
            } else if (line.startsWith("AT+CHLD=?")) {
                sendAt(out, "+CHLD: (0,1,2,3)\r\n\r\nOK");
            }
            // 3. HFP 1.7 HF Indicators Handshake (Xiaomi Buds 6 Play & Vivo Buds primary)
            else if (line.startsWith("AT+BIND=?")) {
                // Support Indicator 1 (Enhanced Safety) and Indicator 2 (Battery Level)
                sendAt(out, "+BIND: (1,2)\r\n\r\nOK");
            } else if (line.startsWith("AT+BIND?")) {
                // Enable Indicator 2 (Battery Level = 1 enabled)
                sendAt(out, "+BIND: 1,1\r\n\r\n+BIND: 2,1\r\n\r\nOK");
            } else if (line.startsWith("AT+BIND=")) {
                sendAt(out, "OK");
            }
            // 4. Apple Accessory Protocol Handshake (Universal TWS fallback)
            else if (line.startsWith("AT+XAPL=")) {
                sendAt(out, "+XAPL=iPhone,7\r\n\r\nOK");
            }
            // 5. Parse Battery Commands & Acknowledge all other AT commands
            else if (line.startsWith("AT") || line.startsWith("+")) {
                sendAt(out, "OK");
                parseBatteryPayload(line);
            }
        }

        private void parseBatteryPayload(String line) {
            try {
                AudioReceiver.BatteryInfo info = AudioReceiver.parseVendorAtLine(line);
                if (info != null && info.battery >= 0) {
                    notifyBatteryUpdate(device, info.battery, info.isCharging, info.details);
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to parse AT battery command: " + line, e);
            }
        }

        private void sendAt(OutputStream out, String response) throws IOException {
            out.write(("\r\n" + response + "\r\n").getBytes("ASCII"));
            out.flush();
        }

        private void sleepQuietly(long ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        private void closeQuietly(Closeable c) {
            if (c != null) {
                try {
                    c.close();
                } catch (IOException ignored) {}
            }
        }
    }
}
