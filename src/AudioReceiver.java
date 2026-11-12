package com.audioarch;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import java.io.InputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class AudioReceiver {
    public static final String VERSION = "1.2.0";
    private static final int DEFAULT_PORT = 12345;
    private static final int DEFAULT_SAMPLE_RATE = 44100;
    private static volatile boolean running = true;

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("--version")) {
            System.out.println("AudioArch AudioReceiver v" + VERSION);
            return;
        }

        int port = DEFAULT_PORT;
        int sampleRate = DEFAULT_SAMPLE_RATE;

        if (args.length > 0) {
            try { port = Integer.parseInt(args[0]); } catch (Exception ignored) {}
        }
        if (args.length > 1) {
            try { sampleRate = Integer.parseInt(args[1]); } catch (Exception ignored) {}
        }

        System.out.printf("[Daemon] AudioArch Receiver v%s (Bluetooth & Speaker) starting on port %d, rate=%dHz%n", VERSION, port, sampleRate);

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
            serverSocket.bind(new java.net.InetSocketAddress(port));
            System.out.printf("[Daemon] Listening on 127.0.0.1:%d. Ready for host stream.%n", port);

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
