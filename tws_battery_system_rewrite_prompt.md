# Task: Complete Rewrite of the Android TWS Battery Subsystem (Direct HFP RFCOMM for Android 4.4.4)

## 1. Objective
Completely remove the existing battery monitoring implementation in this Android Java audio-streaming receiver/daemon and replace it with a **self-contained, low-memory Classic Bluetooth HFP RFCOMM AT-Command Battery Subsystem** (`TwsBatteryManager`).

### Target Hardware & OS Constraints
- **Target Device:** Samsung Galaxy Star GT-S5282 (Spreadtrum SC8810, 1 GHz Cortex-A5, 512 MB RAM) and similar legacy devices.
- **OS & Kernel:** Android 4.4.4 (KitKat, API Level 19), Linux Kernel 3.0, rooted.
- **Language Level:** Java 7 compatible (no Java 8 Streams or `java.time`; anonymous inner classes or simple lambdas if retrolambda/D8 is configured, prefer standard Java 7 syntax for maximum compatibility).
- **Zero BLE Usage:** Do **NOT** use `BluetoothLeScanner`, `startLeScan`, or GATT (`connectGatt`). The GT-S5282 lacks functional BLE support in its Bluedroid HAL, and BLE scanning degrades 2.4 GHz Wi-Fi + A2DP audio streaming.
- **Zero Polling / Zero Idle CPU:** Use a single blocking `BufferedReader.readLine()` over an `RfcommSocket` with a small 256-byte buffer so the thread sleeps inside the Linux kernel (`0%` CPU usage) until the earbuds transmit an AT command.

---

## 2. Refactoring Steps for the Agent

1. **Locate and Remove Legacy Battery Code:**
   - Search the codebase for any existing Bluetooth battery receivers, GATT callbacks, reflection calls to `BluetoothDevice.getBatteryLevel()` (which does not exist on API 19), or periodic polling timers.
   - Delete or deprecate those classes and remove their registrations from the main Service/Daemon and `AndroidManifest.xml`.

2. **Verify Permissions in `AndroidManifest.xml`:**
   - Ensure both permissions are present:
     ~~~xml
     <uses-permission android:name="android.permission.BLUETOOTH" />
     <uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
     ~~~

3. **Create `TwsBatteryManager.java`:**
   - Implement the complete `TwsBatteryManager` class provided in Section 3 below inside the project's package.
   - It automatically handles:
     - **System HFP Detachment:** Uses reflection on `BluetoothHeadset` (`setPriority(device, 0)` and `disconnect(device)`) so Android 4.4.4's built-in `com.android.bluetooth` does not lock the HFP RFCOMM channel or reply `ERROR` to `AT+XAPL` / `AT+BIND`. Note: This leaves `BluetoothA2dp` (Media Audio) completely untouched so PC-to-phone-to-earbuds audio streaming continues uninterrupted.
     - **Socket Fallback Chain:** Attempts `createInsecureRfcommSocketToServiceRecord` with HFP UUID (`0000111e-0000-1000-8000-00805f9b34fb`), falls back to HSP UUID (`00001108-0000-1000-8000-00805f9b34fb`), and finally falls back to raw RFCOMM channels `1`, `2`, and `3` via reflection.
     - **Universal AT Handshake & Battery Parsing:**
       - **HFP 1.7 HF Indicators (`AT+BIND` / `AT+BIEV=2,XX`):** Used by Xiaomi Redmi Buds 6 Play, Vivo Buds, and modern Bluetooth 5.x earbuds for exact `0-100%` battery reporting.
       - **Apple Accessory Protocol (`AT+XAPL` / `AT+IPHONEACCEV`):** Universal fallback used by over 95% of TWS earbuds (reports `0-9` battery level and `0-1` charging status).
       - **Plantronics / Qualcomm XEvent (`AT+XEVENT=BATTERY,...`):** Legacy fallback.

4. **Wire `TwsBatteryManager` into the Existing PC Telemetry / Audio Daemon:**
   - Instantiate `TwsBatteryManager` inside the main streaming Service/Daemon.
   - Pass a `BatteryListener` callback that formats and sends the battery percentage (`0-100`), charging status (`true`/`false`), and device name/MAC directly over the existing PC network socket / stats channel.
   - Call `twsBatteryManager.start()` when the daemon starts and `twsBatteryManager.stop()` when the daemon shuts down.

---

## 3. Reference Implementation (`TwsBatteryManager.java`)

Adapt the package declaration below to match the existing project structure:

~~~java
package com.example.audiostreamer.bluetooth;

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
import java.util.UUID;

/**
 * Lightweight, universal Classic Bluetooth HFP RFCOMM battery monitor for Android 4.4.4 (API 19).
 * Supports Xiaomi Buds 6 Play, Vivo Buds, and generic TWS earbuds via HFP 1.7 (AT+BIEV),
 * Apple Accessory Protocol (AT+XAPL / AT+IPHONEACCEV), and Plantronics XEVENT.
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
         */
        void onBatteryChanged(BluetoothDevice device, int batteryLevel, boolean isCharging);
    }

    private final Context context;
    private final BatteryListener listener;
    private final BluetoothAdapter btAdapter;

    private volatile HfpSocketWorker activeWorker;
    private volatile boolean isRunning = false;
    private volatile int lastBatteryLevel = -1;
    private volatile boolean lastChargingState = false;

    public TwsBatteryManager(Context context, BatteryListener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.btAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    /**
     * Starts listening for A2DP connections and attaches the HFP battery reader to any
     * currently connected A2DP earbuds.
     */
    public synchronized void start() {
        if (isRunning || btAdapter == null) return;
        isRunning = true;

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        context.registerReceiver(btReceiver, filter);

        // Check if an A2DP device is already connected when the daemon starts
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
    }

    /**
     * Stops the battery monitor and closes any open RFCOMM socket.
     */
    public synchronized void stop() {
        if (!isRunning) return;
        isRunning = false;
        try {
            context.unregisterReceiver(btReceiver);
        } catch (IllegalArgumentException ignored) {}
        stopActiveWorker();
    }

    public int getLastBatteryLevel() {
        return lastBatteryLevel;
    }

    public boolean isLastChargingState() {
        return lastChargingState;
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
                        Log.w(TAG, "System HFP detach skipped (Call Audio likely already unchecked): " + t.getMessage());
                    } finally {
                        btAdapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy);
                    }
                    startWorkerForDevice(device);
                }
            }

            @Override
            public void onServiceDisconnected(int profile) {}
        }, BluetoothProfile.HEADSET);
    }

    private synchronized void startWorkerForDevice(BluetoothDevice device) {
        if (!isRunning) return;
        stopActiveWorker();
        activeWorker = new HfpSocketWorker(device);
        activeWorker.start();
    }

    private synchronized void stopActiveWorker() {
        if (activeWorker != null) {
            activeWorker.shutdown();
            activeWorker = null;
        }
        lastBatteryLevel = -1;
        lastChargingState = false;
    }

    private void notifyBatteryUpdate(BluetoothDevice device, int percent, boolean isCharging) {
        int clamped = Math.max(0, Math.min(100, percent));
        this.lastBatteryLevel = clamped;
        this.lastChargingState = isCharging;
        Log.i(TAG, "TWS Battery [" + device.getName() + "]: " + clamped + "% (charging=" + isCharging + ")");
        if (listener != null) {
            listener.onBatteryChanged(device, clamped, isCharging);
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
            else if (line.startsWith("AT")) {
                sendAt(out, "OK");
                parseBatteryPayload(line);
            }
        }

        private void parseBatteryPayload(String line) {
            try {
                // Protocol A: HFP 1.7 HF Indicator (AT+BIEV=2,85 -> 85%)
                if (line.startsWith("AT+BIEV=")) {
                    String payload = line.substring(8).trim();
                    int commaIdx = payload.indexOf(',');
                    if (commaIdx > 0) {
                        int indicatorId = Integer.parseInt(payload.substring(0, commaIdx).trim());
                        int value = Integer.parseInt(payload.substring(commaIdx + 1).trim());
                        if (indicatorId == 2) {
                            notifyBatteryUpdate(device, value, lastChargingState);
                        }
                    }
                }
                // Protocol B: Apple Accessory Battery (AT+IPHONEACCEV=numPairs,key1,val1,...)
                else if (line.startsWith("AT+IPHONEACCEV=")) {
                    String[] parts = line.substring(15).split(",");
                    int numPairs = Integer.parseInt(parts[0].trim());
                    int pct = lastBatteryLevel;
                    boolean charging = lastChargingState;

                    for (int i = 0; i < numPairs; i++) {
                        int kIdx = 1 + (i * 2);
                        int vIdx = 2 + (i * 2);
                        if (vIdx >= parts.length) break;

                        int key = Integer.parseInt(parts[kIdx].trim());
                        int val = Integer.parseInt(parts[vIdx].trim());
                        if (key == 1) {
                            // Key 1: Battery level 0..9 -> 10%..100%
                            pct = (val + 1) * 10;
                        } else if (key == 2) {
                            // Key 2: Dock/Charging state 0..1
                            charging = (val == 1);
                        }
                    }
                    if (pct >= 0) {
                        notifyBatteryUpdate(device, pct, charging);
                    }
                }
                // Protocol C: Plantronics / Qualcomm XEVENT (AT+XEVENT=BATTERY,level,scale,talkTime,isCharging)
                else if (line.startsWith("AT+XEVENT=BATTERY,")) {
                    String[] parts = line.substring(18).split(",");
                    if (parts.length >= 2) {
                        int level = Integer.parseInt(parts[0].trim());
                        int scale = Integer.parseInt(parts[1].trim());
                        boolean charging = parts.length >= 4 && Integer.parseInt(parts[3].trim()) == 1;
                        if (scale > 0) {
                            int pct = ((level + 1) * 100) / scale;
                            notifyBatteryUpdate(device, pct, charging);
                        }
                    }
                }
                // Protocol D: Standard AT+CBC=0,85 fallback
                else if (line.startsWith("AT+CBC=")) {
                    String[] parts = line.substring(7).split(",");
                    if (parts.length >= 2) {
                        int charging = Integer.parseInt(parts[0].trim());
                        int pct = Integer.parseInt(parts[1].trim());
                        notifyBatteryUpdate(device, pct, charging == 1);
                    }
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
~~~

---

## 4. Integration Example for the Daemon / Service

In the main audio-streaming Service or Daemon class that communicates with the PC:

~~~java
private TwsBatteryManager batteryManager;

@Override
public void onCreate() {
    super.onCreate();

    batteryManager = new TwsBatteryManager(this, new TwsBatteryManager.BatteryListener() {
        @Override
        public void onBatteryChanged(BluetoothDevice device, int batteryLevel, boolean isCharging) {
            // Replace with the project's existing PC telemetry/stats sender method:
            sendBatteryStatsToPc(device.getName(), device.getAddress(), batteryLevel, isCharging);
        }
    });
    batteryManager.start();
}

@Override
public void onDestroy() {
    if (batteryManager != null) {
        batteryManager.stop();
    }
    super.onDestroy();
}
~~~

---

## 5. Verification Checklist for the Agent
- [ ] All legacy battery polling, GATT, or reflection-based `getBatteryLevel()` code has been removed.
- [ ] `TwsBatteryManager` is instantiated and started with the daemon lifecycle and stopped cleanly on teardown.
- [ ] `onBatteryChanged` immediately forwards the updated battery level (`0-100%`) and charging state to the PC stats stream.
- [ ] Code compiles cleanly against Android API 19 (Java 7 compatibility) with zero external dependencies.