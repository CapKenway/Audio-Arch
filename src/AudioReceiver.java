package com.audioarch;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AudioReceiver {
    public static final String VERSION = "1.3.0";
    private static final int DEFAULT_AUDIO_PORT = 12345;
    private static final int DEFAULT_CTRL_PORT = 12346;
    private static final int DEFAULT_SAMPLE_RATE = 44100;
    private static volatile boolean running = true;

    // Active control clients connected from host
    private static final Set<PrintWriter> controlClients = Collections.synchronizedSet(new HashSet<PrintWriter>());
    private static volatile int lastKnownBtBattery = -1;
    private static volatile String lastKnownBtDevice = "Bluetooth Device";
    private static volatile int lastKnownPhoneBattery = -1;

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

    private static void saveCachedBattery(int btBat, String device) {
        try {
            FileOutputStream fos = new FileOutputStream("/data/local/tmp/audioarch_bt_battery");
            String data = btBat + "\n" + (device != null ? device : "") + "\n";
            fos.write(data.getBytes());
            fos.close();
        } catch (Exception ignored) {}
    }

    private static String queryBluetoothDeviceName() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"dumpsys", "audio"});
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("mBluetoothName=")) {
                    int idx = line.indexOf("mBluetoothName=");
                    String name = line.substring(idx + 15).trim();
                    if (!name.isEmpty()) {
                        reader.close();
                        p.destroy();
                        return name;
                    }
                }
            }
            reader.close();
            p.destroy();
        } catch (Exception ignored) {}
        return "Bluetooth Headset";
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

    private static void startControlServer(final int ctrlPort) {
        Thread serverThread = new Thread(new Runnable() {
            @Override
            public void run() {
                ServerSocket serverSocket = null;
                try {
                    serverSocket = new ServerSocket();
                    serverSocket.setReuseAddress(true);
                    serverSocket.bind(new InetSocketAddress(ctrlPort));
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

                                        // Send initial handshake and cached state
                                        out.println("READY:AudioArch Receiver v" + VERSION);
                                        if (lastKnownBtBattery >= 0) {
                                            out.printf("BATTERY:%d:%s%n", lastKnownBtBattery, lastKnownBtDevice);
                                        }
                                        if (lastKnownPhoneBattery >= 0) {
                                            out.printf("PHONE_BATTERY:%d%n", lastKnownPhoneBattery);
                                        }
                                        out.flush();

                                        String line;
                                        while (running && (line = in.readLine()) != null) {
                                            line = line.trim();
                                            if (line.equalsIgnoreCase("GET_BATTERY")) {
                                                if (lastKnownBtBattery >= 0) {
                                                    out.printf("BATTERY:%d:%s%n", lastKnownBtBattery, lastKnownBtDevice);
                                                }
                                                int pBat = readPhoneBattery();
                                                if (pBat >= 0) {
                                                    lastKnownPhoneBattery = pBat;
                                                    out.printf("PHONE_BATTERY:%d%n", pBat);
                                                }
                                                out.flush();
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
                } catch (Exception e) {
                    System.err.println("[Control] Failed to bind control server: " + e.getMessage());
                } finally {
                    if (serverSocket != null) {
                        try { serverSocket.close(); } catch (Exception ignored) {}
                    }
                }
            }
        });
        serverThread.setDaemon(true);
        serverThread.setName("ControlServerThread");
        serverThread.start();
    }

    private static void startTelemetryThread() {
        Thread telemetryThread = new Thread(new Runnable() {
            @Override
            public void run() {
                // Regex patterns for broad TWS and Bluetooth headphone battery support:
                // 1. Xiaomi / Redmi proprietary AT battery command: +XIAOMI=1,1,<bat>,...
                Pattern xiaomiPattern = Pattern.compile("\\+XIAOMI\\s*=\\s*1\\s*,\\s*1\\s*,\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
                // 2. Apple / Beats / Anker / Sony / JBL / Galaxy Buds battery indicator: +IPHONEACCEV=...,1,<0-9>
                Pattern applePattern = Pattern.compile("\\+IPHONEACCEV\\s*=\\s*\\d+\\s*,\\s*1\\s*,\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
                // 3. Bluetooth SIG HFP 1.7+ Battery Indicator: +BIEV=2,<0-100>
                Pattern bievPattern = Pattern.compile("\\+BIEV\\s*=\\s*2\\s*,\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
                // 4. Generic vendor AT battery format: +BAT:<0-100> or +BATTERY=<0-100>
                Pattern genericPattern = Pattern.compile("\\+BAT(?:TERY)?\\s*[:=]\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

                // Initial device name query
                lastKnownBtDevice = queryBluetoothDeviceName();
                lastKnownPhoneBattery = readPhoneBattery();

                Process logcatProc = null;
                while (running) {
                    try {
                        // Clear old logcat buffer and tail for Bluetooth HeadsetStateMachine & BTA logs
                        String[] cmd = new String[]{"logcat", "-v", "time", "-s", "HeadsetStateMachine:E", "HeadsetStateMachine:D", "bt-btif:I", "bta_ag_at:I"};
                        logcatProc = Runtime.getRuntime().exec(cmd);
                        BufferedReader reader = new BufferedReader(new InputStreamReader(logcatProc.getInputStream()));

                        String line;
                        long lastQueryTime = 0;
                        while (running && (line = reader.readLine()) != null) {
                            long now = System.currentTimeMillis();
                            if (now - lastQueryTime > 30000) {
                                lastQueryTime = now;
                                lastKnownBtDevice = queryBluetoothDeviceName();
                                int pBat = readPhoneBattery();
                                if (pBat >= 0 && pBat != lastKnownPhoneBattery) {
                                    lastKnownPhoneBattery = pBat;
                                    broadcast("PHONE_BATTERY:" + pBat);
                                }
                            }

                            int detectedBattery = -1;
                            Matcher m = xiaomiPattern.matcher(line);
                            if (m.find()) {
                                detectedBattery = Integer.parseInt(m.group(1));
                            } else if ((m = bievPattern.matcher(line)).find()) {
                                detectedBattery = Integer.parseInt(m.group(1));
                            } else if ((m = applePattern.matcher(line)).find()) {
                                int val = Integer.parseInt(m.group(1));
                                // Apple level is 0..9 (or 0..100)
                                if (val <= 9) {
                                    detectedBattery = (val + 1) * 10;
                                } else {
                                    detectedBattery = val;
                                }
                            } else if ((m = genericPattern.matcher(line)).find()) {
                                detectedBattery = Integer.parseInt(m.group(1));
                            }

                            if (detectedBattery >= 0) {
                                if (detectedBattery > 100) detectedBattery = 100;
                                lastKnownBtBattery = detectedBattery;
                                saveCachedBattery(detectedBattery, lastKnownBtDevice);
                                System.out.printf("[Telemetry] Bluetooth Battery detected: %d%% (%s)%n", detectedBattery, lastKnownBtDevice);
                                broadcast(String.format("BATTERY:%d:%s", detectedBattery, lastKnownBtDevice));
                            }
                        }
                    } catch (Exception e) {
                        try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                    } finally {
                        if (logcatProc != null) {
                            try { logcatProc.destroy(); } catch (Exception ignored) {}
                        }
                    }
                }
            }
        });
        telemetryThread.setDaemon(true);
        telemetryThread.setName("TelemetryThread");
        telemetryThread.start();
    }

    private static void startInputEventThread() {
        Thread inputThread = new Thread(new Runnable() {
            @Override
            public void run() {
                Process geteventProc = null;
                while (running) {
                    try {
                        // Monitor /dev/input events for AVRCP & headset key presses
                        geteventProc = Runtime.getRuntime().exec(new String[]{"getevent", "-l"});
                        BufferedReader reader = new BufferedReader(new InputStreamReader(geteventProc.getInputStream()));

                        String line;
                        long lastEventTime = 0;
                        String lastEventCmd = "";

                        while (running && (line = reader.readLine()) != null) {
                            // Example output: /dev/input/event4: EV_KEY       KEY_PLAYPAUSE        DOWN
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
                                // Debounce identical events within 250ms
                                if (cmd.equals(lastEventCmd) && (now - lastEventTime) < 250) {
                                    continue;
                                }
                                lastEventTime = now;
                                lastEventCmd = cmd;
                                System.out.printf("[RemoteControl] Earbud / Headset Event: %s%n", cmd);
                                broadcast(cmd);
                            }
                        }
                    } catch (Exception e) {
                        try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                    } finally {
                        if (geteventProc != null) {
                            try { geteventProc.destroy(); } catch (Exception ignored) {}
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

        // Start integrated Telemetry, Bluetooth Battery & Media Control servers
        startControlServer(ctrlPort);
        startTelemetryThread();
        startInputEventThread();

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
                System.out.println("[Daemon] Shutdown complete.");
            }
        }));

        ServerSocket serverSocket = null;
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(audioPort));
            System.out.printf("[Daemon] Listening for PCM audio stream on 127.0.0.1:%d%n", audioPort);

            // 2048 bytes = 512 stereo samples (~11.6ms) - optimal for low CPU & low latency
            final int CHUNK_SIZE = 2048;
            // Drain threshold: if more than ~100ms backs up, skip to catch up to real-time
            final int MAX_BACKLOG = 16384;

            byte[] chunk = new byte[CHUNK_SIZE];

            while (running) {
                try {
                    Socket client = serverSocket.accept();
                    client.setTcpNoDelay(true);
                    client.setReceiveBufferSize(16384);
                    System.out.println("[Daemon] Host connected! Starting audio playback...");

                    InputStream in = client.getInputStream();

                    // Discard any residual bytes before starting playback
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
                        // Anti-bufferbloat: if socket queue accumulated >100ms, skip to live head
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

                        // Write to AudioTrack (routes automatically to Bluetooth / headphones / speaker)
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
