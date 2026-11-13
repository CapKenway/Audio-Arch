package com.audioarch;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Looper;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class AudioReceiver {
    public static final String VERSION = "2.0";
    private static final int DEFAULT_AUDIO_PORT = 12345;
    private static final int DEFAULT_CTRL_PORT = 12346;
    private static final int DEFAULT_SAMPLE_RATE = 44100;
    private static volatile boolean running = true;
    private static volatile Process currentGeteventProc = null;

    // Direct HFP RFCOMM TWS Battery Manager (realtime, zero caching)
    private static volatile TwsBatteryManager batteryManager = null;
    private static volatile boolean lastChargingState = false;

    // Active control clients connected from host
    private static final Set<PrintWriter> controlClients = Collections.synchronizedSet(new HashSet<PrintWriter>());
    private static volatile int lastKnownBtBattery = -1;
    private static volatile String lastKnownBtDevice = "Disconnected";
    private static volatile String btBatterySource = "disconnected";
    private static volatile String btBatteryTime = "";
    private static volatile String btBatteryDetails = "";
    private static volatile int lastKnownPhoneBattery = -1;

    public static class BatteryInfo {
        public int battery;
        public boolean isCharging;
        public String details = "";
    }

    public static void broadcast(String msg) {
        synchronized (controlClients) {
            for (PrintWriter out : controlClients) {
                try {
                    out.println(msg);
                    out.flush();
                } catch (Exception ignored) {}
            }
        }
    }

    public static synchronized void updateBatteryState(int batteryLevel, boolean isCharging, String details) {
        if (batteryLevel < 0 || batteryLevel > 100) return;
        lastKnownBtBattery = batteryLevel;
        lastChargingState = isCharging;
        btBatterySource = "live";
        btBatteryTime = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
        btBatteryDetails = (details != null && !details.isEmpty()) ? details : (isCharging ? "⚡" : (batteryLevel + "%"));
        System.out.printf("[TwsBattery] Live battery: %d%% (%s) from %s%n", batteryLevel, btBatteryDetails, lastKnownBtDevice);
        broadcast(String.format("BATTERY|%d|%s|live|%s|%s", batteryLevel, lastKnownBtDevice, btBatteryTime, btBatteryDetails));
    }

    public static void clearBluetoothState() {
        lastKnownBtDevice = "Disconnected";
        lastKnownBtBattery = -1;
        lastChargingState = false;
        btBatterySource = "disconnected";
        btBatteryTime = "";
        btBatteryDetails = "";
        try {
            File f = new File("/data/local/tmp/audioarch_bt_battery");
            if (f.exists()) {
                f.delete();
            }
        } catch (Exception ignored) {}
        if (batteryManager != null) {
            batteryManager.stopActiveWorker();
        }
        broadcast("DISCONNECTED");
        broadcast("BATTERY|-1|Disconnected|disconnected||");
    }

    public static BatteryInfo parseVendorAtLine(String line) {
        if (line == null) return null;
        int cmdIdx = line.indexOf("command:");
        if (cmdIdx >= 0) {
            line = line.substring(cmdIdx + "command:".length()).trim();
        }
        line = line.trim();
        if (line.startsWith("+")) line = line.substring(1);
        else if (line.startsWith("AT+")) line = line.substring(3);

        // 1. Xiaomi Dynamic TLV Hex: +XIAOMI=FF... (e.g. +XIAOMI=FF0102010115000401027F14...)
        if (line.contains("XIAOMI=") && line.matches(".*XIAOMI=[0-9A-Fa-f]{10,}.*")) {
            int eq = line.indexOf("XIAOMI=");
            String hex = line.substring(eq + "XIAOMI=".length()).trim();
            BatteryInfo info = parseXiaomiHex(hex);
            if (info != null) return info;
        }

        // 2. Apple Battery: IPHONEACCEV=...
        if (line.startsWith("IPHONEACCEV=")) {
            return parseAppleAccev(line.substring("IPHONEACCEV=".length()));
        }

        // 3. HFP 1.7 BIEV: BIEV=2,XX
        if (line.startsWith("BIEV=")) {
            String payload = line.substring("BIEV=".length()).trim();
            int c = payload.indexOf(',');
            if (c > 0) {
                try {
                    int id = Integer.parseInt(payload.substring(0, c).trim());
                    int val = Integer.parseInt(payload.substring(c + 1).trim());
                    if (id == 2 && val >= 0 && val <= 100) {
                        BatteryInfo info = new BatteryInfo();
                        info.battery = val;
                        info.isCharging = false;
                        info.details = val + "%";
                        return info;
                    }
                } catch (Exception ignored) {}
            }
        }

        // 4. Plantronics XEVENT=BATTERY,...
        if (line.startsWith("XEVENT=BATTERY,")) {
            String payload = line.substring("XEVENT=BATTERY,".length()).trim();
            String[] parts = payload.split(",");
            if (parts.length >= 2) {
                try {
                    int level = Integer.parseInt(parts[0].trim());
                    int scale = Integer.parseInt(parts[1].trim());
                    boolean charging = parts.length >= 4 && Integer.parseInt(parts[3].trim()) == 1;
                    if (scale > 0) {
                        int pct = ((level + 1) * 100) / scale;
                        BatteryInfo info = new BatteryInfo();
                        info.battery = Math.max(0, Math.min(100, pct));
                        info.isCharging = charging;
                        info.details = info.battery + (charging ? "%⚡" : "%");
                        return info;
                    }
                } catch (Exception ignored) {}
            }
        }

        // 5. Standard AT+CBC=charging,pct
        if (line.startsWith("CBC=")) {
            String payload = line.substring("CBC=".length()).trim();
            String[] parts = payload.split(",");
            if (parts.length >= 2) {
                try {
                    int charging = Integer.parseInt(parts[0].trim());
                    int pct = Integer.parseInt(parts[1].trim());
                    if (pct >= 0 && pct <= 100) {
                        BatteryInfo info = new BatteryInfo();
                        info.battery = pct;
                        info.isCharging = (charging == 1);
                        info.details = pct + (charging == 1 ? "%⚡" : "%");
                        return info;
                    }
                } catch (Exception ignored) {}
            }
        }

        return null;
    }

    public static BatteryInfo parseXiaomiHex(String hex) {
        if (hex == null || hex.length() < 8) return null;
        hex = hex.toUpperCase();
        try {
            BatteryInfo info = null;
            int idx = hex.indexOf("040102");
            if (idx >= 0 && idx + 10 <= hex.length()) {
                info = extractXiaomiBudsBattery(hex.substring(idx + 6, idx + 10));
            }
            if (info == null) {
                idx = hex.indexOf("040002");
                if (idx >= 0 && idx + 10 <= hex.length()) {
                    info = extractXiaomiBudsBattery(hex.substring(idx + 6, idx + 10));
                }
            }
            if (info == null) {
                idx = hex.indexOf("0402");
                if (idx >= 0 && idx + 8 <= hex.length()) {
                    info = extractXiaomiBudsBattery(hex.substring(idx + 4, idx + 8));
                }
            }

            if (info != null) {
                // Check optional Case battery: Tag 05 Sub 01 Len 01 (e.g. 0501011E)
                int cIdx = hex.indexOf("050101");
                if (cIdx >= 0 && cIdx + 8 <= hex.length()) {
                    try {
                        int cRaw = Integer.parseInt(hex.substring(cIdx + 6, cIdx + 8), 16);
                        int cPct = cRaw & 0x7F;
                        boolean cChg = (cRaw & 0x80) != 0;
                        if (cPct >= 0 && cPct <= 100) {
                            info.details += " Case:" + cPct + (cChg ? "%⚡" : "%");
                        }
                    } catch (Exception ignored) {}
                }
                return info;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static BatteryInfo extractXiaomiBudsBattery(String fourHex) {
        int b1Raw = Integer.parseInt(fourHex.substring(0, 2), 16);
        int b2Raw = Integer.parseInt(fourHex.substring(2, 4), 16);
        int b1 = b1Raw & 0x7F;
        boolean b1Chg = (b1Raw & 0x80) != 0;
        int b2 = b2Raw & 0x7F;
        boolean b2Chg = (b2Raw & 0x80) != 0;

        boolean v1 = (b1 >= 0 && b1 <= 100);
        boolean v2 = (b2 >= 0 && b2 <= 100);

        BatteryInfo info = new BatteryInfo();
        if (v1 && v2) {
            info.battery = Math.min(b1, b2);
            info.isCharging = b1Chg || b2Chg;
            info.details = "L:" + b1 + (b1Chg ? "%⚡" : "%") + " R:" + b2 + (b2Chg ? "%⚡" : "%");
            return info;
        } else if (v1) {
            info.battery = b1;
            info.isCharging = b1Chg;
            info.details = b1 + (b1Chg ? "%⚡" : "%");
            return info;
        } else if (v2) {
            info.battery = b2;
            info.isCharging = b2Chg;
            info.details = b2 + (b2Chg ? "%⚡" : "%");
            return info;
        }
        return null;
    }

    private static BatteryInfo parseAppleAccev(String payload) {
        try {
            String[] parts = payload.split(",");
            int numPairs = Integer.parseInt(parts[0].trim());
            int pct = -1;
            boolean charging = false;
            for (int i = 0; i < numPairs; i++) {
                int kIdx = 1 + (i * 2);
                int vIdx = 2 + (i * 2);
                if (vIdx >= parts.length) break;
                int key = Integer.parseInt(parts[kIdx].trim());
                int val = Integer.parseInt(parts[vIdx].trim());
                if (key == 1) pct = (val + 1) * 10;
                else if (key == 2) charging = (val == 1);
            }
            if (pct >= 0 && pct <= 100) {
                BatteryInfo info = new BatteryInfo();
                info.battery = pct;
                info.isCharging = charging;
                info.details = pct + (charging ? "%⚡" : "%");
                return info;
            }
        } catch (Exception ignored) {}
        return null;
    }

    public static BatteryInfo queryVendorAtFromLogcat() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-v", "raw", "-s", "HeadsetStateMachine:E"});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            java.util.List<String> lines = new java.util.ArrayList<String>();
            while ((line = r.readLine()) != null) {
                if (line.contains("processVendorSpecificAt")) {
                    lines.add(line);
                }
            }
            r.close();
            p.destroy();
            for (int i = lines.size() - 1; i >= 0; i--) {
                BatteryInfo info = parseVendorAtLine(lines.get(i));
                if (info != null && info.battery >= 0) {
                    return info;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static void startVendorAtLogcatListener() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                Process proc = null;
                while (running) {
                    try {
                        proc = Runtime.getRuntime().exec(new String[]{"logcat", "-v", "raw", "-s", "HeadsetStateMachine:E"});
                        BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                        String line;
                        while (running && (line = r.readLine()) != null) {
                            if (!line.contains("processVendorSpecificAt")) continue;
                            BatteryInfo info = parseVendorAtLine(line);
                            if (info != null && info.battery >= 0) {
                                String curDev = queryBluetoothDeviceName();
                                if (curDev != null && !curDev.isEmpty() && !"null".equalsIgnoreCase(curDev)) {
                                    lastKnownBtDevice = curDev;
                                }
                                updateBatteryState(info.battery, info.isCharging, info.details);
                            }
                        }
                    } catch (Exception e) {
                        try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                    } finally {
                        if (proc != null) {
                            try { proc.destroy(); } catch (Exception ignored) {}
                        }
                    }
                }
            }
        });
        t.setDaemon(true);
        t.setName("VendorAtLogcatListener");
        t.start();
    }

    private static void doForceRecheck() {
        String curDev = queryBluetoothDeviceName();
        if (curDev == null) {
            clearBluetoothState();
            int pBat = readPhoneBattery();
            if (pBat >= 0) lastKnownPhoneBattery = pBat;
            return;
        }

        lastKnownBtDevice = curDev;
        if (batteryManager != null) {
            batteryManager.attachToDeviceByName(curDev);
            batteryManager.checkConnectedDevices();
        }

        BatteryInfo logcatInfo = queryVendorAtFromLogcat();
        if (logcatInfo != null && logcatInfo.battery >= 0) {
            updateBatteryState(logcatInfo.battery, logcatInfo.isCharging, logcatInfo.details);
        }

        int pBat = readPhoneBattery();
        if (pBat >= 0) {
            lastKnownPhoneBattery = pBat;
        }

        if (lastKnownBtBattery >= 0 && !"Disconnected".equalsIgnoreCase(lastKnownBtDevice)) {
            String ts = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
            btBatteryTime = ts;
            broadcast(String.format("BATTERY|%d|%s|%s|%s|%s", lastKnownBtBattery, lastKnownBtDevice, btBatterySource, ts, btBatteryDetails));
        }
    }

    private static String queryBluetoothDeviceName() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"dumpsys", "audio"});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.contains("mBluetoothName=")) {
                    int idx = line.indexOf("mBluetoothName=");
                    String name = line.substring(idx + "mBluetoothName=".length()).trim();
                    r.close();
                    p.destroy();
                    if (!name.isEmpty() && !name.equalsIgnoreCase("null") && !name.equalsIgnoreCase("none")) {
                        return name;
                    }
                    return null;
                }
            }
            r.close();
            p.destroy();
        } catch (Exception ignored) {}
        return null;
    }

    private static int readPhoneBattery() {
        try {
            File f = new File("/sys/class/power_supply/battery/capacity");
            if (f.exists()) {
                BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f)));
                String line = br.readLine();
                br.close();
                if (line != null) {
                    return Integer.parseInt(line.trim());
                }
            }
        } catch (Exception ignored) {}
        return -1;
    }

    private static String findAvrcpDevice() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"getevent", "-p"});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line, curDev = null;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("add device")) {
                    int colon = line.indexOf(':');
                    if (colon > 0) curDev = line.substring(colon + 1).trim();
                } else if (line.contains("\"AVRCP\"") && curDev != null) {
                    r.close();
                    p.destroy();
                    return curDev;
                }
            }
            r.close();
            p.destroy();
        } catch (Exception ignored) {}
        return null;
    }

    private static void startControlServer(final int ctrlPort) {
        Thread serverThread = new Thread(new Runnable() {
            @Override
            public void run() {
                ServerSocket serverSocket = null;
                for (int retry = 0; retry < 5 && running; retry++) {
                    try {
                        serverSocket = new ServerSocket();
                        serverSocket.setReuseAddress(true);
                        serverSocket.bind(new InetSocketAddress(ctrlPort));
                        break;
                    } catch (Exception e) {
                        if (serverSocket != null) {
                            try { serverSocket.close(); } catch (Exception ignored) {}
                            serverSocket = null;
                        }
                        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                    }
                }

                if (serverSocket == null) {
                    System.err.println("[Control] Failed to bind control server on port " + ctrlPort);
                    return;
                }

                System.out.printf("[Control] Telemetry & Remote Control server listening on port %d%n", ctrlPort);

                while (running) {
                    try {
                        final Socket client = serverSocket.accept();
                        client.setTcpNoDelay(true);

                        Thread clientThread = new Thread(new Runnable() {
                            @Override
                            public void run() {
                                PrintWriter out = null;
                                BufferedReader in = null;
                                try {
                                    out = new PrintWriter(client.getOutputStream(), true);
                                    in = new BufferedReader(new InputStreamReader(client.getInputStream()));
                                    controlClients.add(out);

                                    // Send initial handshake and real-time state
                                    out.println("READY:AudioArch Receiver v" + VERSION);
                                    out.println("DEVICE:" + lastKnownBtDevice);
                                    if (lastKnownBtBattery >= 0 && !"Disconnected".equalsIgnoreCase(lastKnownBtDevice)) {
                                        out.printf("BATTERY|%d|%s|%s|%s|%s%n", lastKnownBtBattery, lastKnownBtDevice, btBatterySource, btBatteryTime, btBatteryDetails);
                                    } else if (!"Disconnected".equalsIgnoreCase(lastKnownBtDevice)) {
                                        out.printf("BATTERY|-1|%s|querying||%s%n", lastKnownBtDevice, btBatteryDetails);
                                    } else {
                                        out.println("BATTERY|-1|Disconnected|disconnected||");
                                    }
                                    if (lastKnownPhoneBattery >= 0) {
                                        out.printf("PHONE_BATTERY:%d%n", lastKnownPhoneBattery);
                                    }
                                    out.flush();

                                    String line;
                                    while (running && (line = in.readLine()) != null) {
                                        line = line.trim();
                                        if (line.equalsIgnoreCase("GET_BATTERY")) {
                                            String curDev = queryBluetoothDeviceName();
                                            if (curDev == null) {
                                                clearBluetoothState();
                                            } else {
                                                lastKnownBtDevice = curDev;
                                                if (lastKnownBtBattery < 0) {
                                                    BatteryInfo bi = queryVendorAtFromLogcat();
                                                    if (bi != null && bi.battery >= 0) {
                                                        lastKnownBtBattery = bi.battery;
                                                        lastChargingState = bi.isCharging;
                                                        btBatterySource = "live";
                                                        btBatteryTime = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
                                                        btBatteryDetails = bi.details;
                                                    }
                                                }
                                                if (batteryManager != null && (lastKnownBtBattery < 0 || "Disconnected".equalsIgnoreCase(lastKnownBtDevice))) {
                                                    batteryManager.attachToDeviceByName(curDev);
                                                    batteryManager.checkConnectedDevices();
                                                }
                                            }
                                            out.println("DEVICE:" + lastKnownBtDevice);
                                            if (lastKnownBtBattery >= 0 && !"Disconnected".equalsIgnoreCase(lastKnownBtDevice)) {
                                                out.printf("BATTERY|%d|%s|%s|%s|%s%n", lastKnownBtBattery, lastKnownBtDevice, btBatterySource, btBatteryTime, btBatteryDetails);
                                            } else if (!"Disconnected".equalsIgnoreCase(lastKnownBtDevice)) {
                                                out.printf("BATTERY|-1|%s|querying||%s%n", lastKnownBtDevice, btBatteryDetails);
                                            } else {
                                                out.println("BATTERY|-1|Disconnected|disconnected||");
                                            }
                                            int pBat = readPhoneBattery();
                                            if (pBat >= 0) {
                                                lastKnownPhoneBattery = pBat;
                                                out.printf("PHONE_BATTERY:%d%n", pBat);
                                            }
                                            out.flush();
                                        } else if (line.equalsIgnoreCase("RECHECK") || line.equalsIgnoreCase("FORCE_RECHECK")) {
                                            doForceRecheck();
                                            out.println("DEVICE:" + lastKnownBtDevice);
                                            if (lastKnownBtBattery >= 0 && !"Disconnected".equalsIgnoreCase(lastKnownBtDevice)) {
                                                out.printf("BATTERY|%d|%s|%s|%s|%s%n", lastKnownBtBattery, lastKnownBtDevice, btBatterySource, btBatteryTime, btBatteryDetails);
                                            } else if (!"Disconnected".equalsIgnoreCase(lastKnownBtDevice)) {
                                                out.printf("BATTERY|-1|%s|querying||%s%n", lastKnownBtDevice, btBatteryDetails);
                                            } else {
                                                out.println("BATTERY|-1|Disconnected|disconnected||");
                                            }
                                            if (lastKnownPhoneBattery >= 0) {
                                                out.printf("PHONE_BATTERY:%d%n", lastKnownPhoneBattery);
                                            }
                                            out.println("RECHECK_COMPLETE");
                                            out.flush();
                                        } else if (line.equalsIgnoreCase("CLEAR_CACHE") || line.equalsIgnoreCase("CLEAN_CACHE")) {
                                            clearBluetoothState();
                                            out.println("CACHE_CLEARED");
                                            out.flush();
                                        } else if (line.toUpperCase().startsWith("SET_BATTERY")) {
                                            String[] parts = line.split("\\s+");
                                            if (parts.length >= 2) {
                                                try {
                                                    int sBat = Integer.parseInt(parts[1]);
                                                    if (sBat >= 0 && sBat <= 100) {
                                                        lastKnownBtBattery = sBat;
                                                        btBatterySource = "live";
                                                        btBatteryTime = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
                                                        broadcast(String.format("BATTERY|%d|%s|live|%s|%s", sBat, lastKnownBtDevice, btBatteryTime, btBatteryDetails));
                                                        out.println("BATTERY_SET:" + sBat);
                                                        out.flush();
                                                    }
                                                } catch (Exception ignored) {}
                                            }
                                        } else if (line.equalsIgnoreCase("PING")) {
                                            out.println("PONG");
                                            out.flush();
                                        }
                                    }
                                } catch (Exception ignored) {
                                } finally {
                                    if (out != null) controlClients.remove(out);
                                    try { client.close(); } catch (Exception ignored) {}
                                }
                            }
                        });
                        clientThread.setDaemon(true);
                        clientThread.start();
                    } catch (Exception e) {
                        if (!running) break;
                    }
                }
            }
        });
        serverThread.setDaemon(true);
        serverThread.setName("ControlServerThread");
        serverThread.start();
    }

    private static void startInputEventThread() {
        Thread inputThread = new Thread(new Runnable() {
            @Override
            public void run() {
                Process geteventProc = null;
                while (running) {
                    try {
                        String avrcpDev = findAvrcpDevice();
                        if (avrcpDev == null) {
                            try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
                            continue;
                        }
                        // Target exclusively the AVRCP Bluetooth input device (no accelerometer / touchscreen overhead)
                        geteventProc = Runtime.getRuntime().exec(new String[]{"getevent", "-l", avrcpDev});
                        currentGeteventProc = geteventProc;
                        BufferedReader reader = new BufferedReader(new InputStreamReader(geteventProc.getInputStream()));

                        String line;
                        long lastEventTime = 0;
                        String lastEventCmd = "";

                        while (running && (line = reader.readLine()) != null) {
                            if (!line.contains("EV_KEY")) continue;
                            if (!line.contains("DOWN") && !line.contains("00000001")) continue;

                            String cmd = null;
                            if (line.contains("KEY_PLAYPAUSE") || line.contains("KEY_PLAY") || line.contains("KEY_PAUSE")) {
                                cmd = "MEDIA:PLAY_PAUSE";
                            } else if (line.contains("KEY_NEXTSONG")) {
                                cmd = "MEDIA:NEXT";
                            } else if (line.contains("KEY_PREVIOUSSONG")) {
                                cmd = "MEDIA:PREV";
                            } else if (line.contains("KEY_VOLUMEUP")) {
                                cmd = "MEDIA:VOL_UP";
                            } else if (line.contains("KEY_VOLUMEDOWN")) {
                                cmd = "MEDIA:VOL_DOWN";
                            }

                            if (cmd != null) {
                                long now = System.currentTimeMillis();
                                if (cmd.equals(lastEventCmd) && (now - lastEventTime) < 250) {
                                    continue;
                                }
                                lastEventTime = now;
                                lastEventCmd = cmd;
                                System.out.printf("[RemoteControl] Earbud / Headset Event: %s%n", cmd);
                                broadcast(cmd);
                            }
                        }
                        try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                    } catch (Exception e) {
                        try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                    } finally {
                        if (geteventProc != null) {
                            try { geteventProc.destroy(); } catch (Exception ignored) {}
                            if (currentGeteventProc == geteventProc) currentGeteventProc = null;
                        }
                    }
                }
            }
        });
        inputThread.setDaemon(true);
        inputThread.setName("InputEventThread");
        inputThread.start();
    }

    public static void main(String[] args) {
        try {
            if (Looper.getMainLooper() == null) {
                Looper.prepareMainLooper();
            }
        } catch (Throwable ignored) {}

        if (args.length > 0 && args[0].equals("--version")) {
            System.out.println("AudioArch AudioReceiver v" + VERSION);
            return;
        }

        int audioPort = DEFAULT_AUDIO_PORT;
        int ctrlPort = DEFAULT_CTRL_PORT;
        int sampleRate = DEFAULT_SAMPLE_RATE;

        if (args.length > 0) {
            try { audioPort = Integer.parseInt(args[0]); } catch (Exception ignored) {}
        }
        if (args.length > 1) {
            try { sampleRate = Integer.parseInt(args[1]); } catch (Exception ignored) {}
        }
        if (args.length > 2) {
            try { ctrlPort = Integer.parseInt(args[2]); } catch (Exception ignored) {}
        }

        System.out.printf("[Daemon] AudioArch Receiver v%s (Bluetooth & Speaker) starting on audioPort=%d, ctrlPort=%d, rate=%dHz%n",
            VERSION, audioPort, ctrlPort, sampleRate);

        try {
            File f = new File("/data/local/tmp/audioarch_bt_battery");
            if (f.exists()) f.delete();
        } catch (Exception ignored) {}

        // Query initial hardware states
        lastKnownBtDevice = queryBluetoothDeviceName();
        if (lastKnownBtDevice == null) lastKnownBtDevice = "Disconnected";
        lastKnownBtBattery = -1;
        lastKnownPhoneBattery = readPhoneBattery();

        // Check recent vendor AT battery command from logcat if device connected
        if (!"Disconnected".equalsIgnoreCase(lastKnownBtDevice)) {
            BatteryInfo bi = queryVendorAtFromLogcat();
            if (bi != null && bi.battery >= 0) {
                lastKnownBtBattery = bi.battery;
                lastChargingState = bi.isCharging;
                btBatterySource = "live";
                btBatteryTime = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
                btBatteryDetails = bi.details;
                System.out.printf("[TwsBattery] Initial vendor battery: %d%% (%s) from %s%n", bi.battery, bi.details, lastKnownBtDevice);
            }
        }

        // Start integrated Telemetry, Bluetooth Battery & Media Control servers
        startControlServer(ctrlPort);
        startInputEventThread();
        startVendorAtLogcatListener();

        // Start Direct Classic Bluetooth HFP RFCOMM TWS Battery Manager
        batteryManager = new TwsBatteryManager(null, new TwsBatteryManager.BatteryListener() {
            @Override
            public void onBatteryChanged(BluetoothDevice device, int batteryLevel, boolean isCharging, String details) {
                String devName = device.getName();
                if (devName == null || devName.isEmpty()) devName = "Bluetooth Headset";
                lastKnownBtDevice = devName;
                updateBatteryState(batteryLevel, isCharging, details);
            }
        });
        batteryManager.start();

        if (lastKnownBtDevice != null && !lastKnownBtDevice.isEmpty() && !"Disconnected".equalsIgnoreCase(lastKnownBtDevice)) {
            batteryManager.attachToDeviceByName(lastKnownBtDevice);
        }

        int channelConfig = AudioFormat.CHANNEL_OUT_STEREO;
        int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
        int minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat);
        int trackBufSize = Math.max(minBuf, 8192);

        System.out.printf("[Daemon] AudioTrack buffer size: %d bytes (%.1f ms)%n",
            trackBufSize, (trackBufSize * 1000.0) / (sampleRate * 4));

        AudioTrack track = new AudioTrack(
            AudioManager.STREAM_MUSIC,
            sampleRate,
            channelConfig,
            audioFormat,
            trackBufSize,
            AudioTrack.MODE_STREAM
        );

        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            System.err.println("[Daemon] ERROR: Failed to initialize AudioTrack.");
            System.exit(1);
        }

        final AudioTrack hookTrack = track;
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                running = false;
                try {
                    hookTrack.stop();
                    hookTrack.release();
                } catch (Exception ignored) {}
                if (batteryManager != null) {
                    try { batteryManager.stop(); } catch (Exception ignored) {}
                }
                if (currentGeteventProc != null) {
                    try { currentGeteventProc.destroy(); } catch (Exception ignored) {}
                }
                System.out.println("[Daemon] Shutdown complete.");
            }
        }));

        ServerSocket serverSocket = null;
        for (int retry = 0; retry < 5 && running; retry++) {
            try {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress(audioPort));
                break;
            } catch (Exception e) {
                if (serverSocket != null) {
                    try { serverSocket.close(); } catch (Exception ignored) {}
                    serverSocket = null;
                }
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            }
        }

        if (serverSocket == null) {
            System.err.println("[Daemon] ERROR: Failed to bind audio port " + audioPort);
            System.exit(1);
        }

        try {
            System.out.printf("[Daemon] Listening for PCM audio stream on 127.0.0.1:%d%n", audioPort);

            final int CHUNK_SIZE = 2048;
            final int MAX_BACKLOG = 16384;

            byte[] chunk = new byte[CHUNK_SIZE];

            while (running) {
                try {
                    Socket client = serverSocket.accept();
                    client.setTcpNoDelay(true);
                    client.setReceiveBufferSize(16384);
                    System.out.println("[Daemon] Host connected! Starting audio playback...");

                    InputStream in = client.getInputStream();

                    int initAvail = in.available();
                    if (initAvail > 0) {
                        in.skip(initAvail);
                    }

                    try {
                        track.flush();
                        track.play();
                    } catch (Exception e) {
                        System.out.println("[Daemon] AudioTrack play warning: " + e.getMessage());
                    }

                    int read;
                    while (running && (read = in.read(chunk)) != -1) {
                        int avail = in.available();
                        if (avail > MAX_BACKLOG) {
                            long toSkip = avail - CHUNK_SIZE;
                            long skipped = 0;
                            while (skipped < toSkip) {
                                long s = in.skip(toSkip - skipped);
                                if (s <= 0) break;
                                skipped += s;
                            }
                        }

                        int written = 0;
                        while (written < read) {
                            int ret = track.write(chunk, written, read - written);
                            if (ret < 0) {
                                System.err.println("[Daemon] AudioTrack write error " + ret + ", re-initializing...");
                                try {
                                    track.stop();
                                    track.release();
                                } catch (Exception ignored) {}
                                try {
                                    track = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, channelConfig, audioFormat, trackBufSize, AudioTrack.MODE_STREAM);
                                    track.play();
                                } catch (Exception ignored) {}
                                break;
                            }
                            if (ret == 0) break;
                            written += ret;
                        }
                    }

                    System.out.println("[Daemon] Host disconnected. Pausing audio...");
                    try {
                        track.pause();
                        track.flush();
                    } catch (Exception ignored) {}

                    try { client.close(); } catch (Exception ignored) {}
                } catch (Exception e) {
                    if (running) {
                        System.out.println("[Daemon] Connection reset/exception: " + e.getMessage());
                    }
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            if (serverSocket != null) {
                try { serverSocket.close(); } catch (Exception ignored) {}
            }
            try {
                track.stop();
                track.release();
            } catch (Exception ignored) {}
        }
    }
}
