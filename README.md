# Audio Arch - Ultra-Low Latency USB Audio Card for Android

Audio Arch transforms low-end and legacy Android devices (tested on Android 4.4 KitKat `GT-S5282`, single-core 850MHz, 512MB RAM) into a high-fidelity external sound card / DAC for Arch Linux with near-zero latency.

It operates completely offline over a physical USB cable using ADB port forwarding (`127.0.0.1`), leaving your Ethernet and Wi-Fi networks completely untouched.

---

## Dual-Backend Architecture

Audio Arch includes two complementary, high-performance backends:

```
[ Arch Linux Desktop Audio ]
         │
         ▼
[ PipeWire / PulseAudio ] (AudioArch-Sink: node.latency=128/44100)
         │
         ▼
[ parec --raw low-latency capture ] (16-bit 44.1kHz Stereo PCM)
         │
         ▼
[ fast_sender.py ] (fcntl pipe size=4KB, TCP_NODELAY=1, SO_SNDBUF=4KB)
         │
         ▼
[ ADB USB Tunnel ] (127.0.0.1:12345 over physical USB cable)
         │
         ├────────────────────────────────────────┬────────────────────────────────────────┐
         │ (When Bluetooth is connected)          │ (When 3.5mm jack / speaker is used)    │
         ▼                                        ▼                                        ▼
[ Bluetooth AudioTrack Daemon ]          [ Native C ALSA Daemon ]
   audio_receiver.jar                       audioarch_daemon (94KB binary)
   • Dynamic backlog drainer                • Direct kernel ALSA (/dev/snd/pcmC0D0p)
   • Binds to Android AudioPolicy           • Double-buffer (512x2 = 23ms latency)
   • Auto-routes to Bluetooth earbuds       • 80 KB RAM, <0.1% CPU
         │                                        │
         ▼                                        ▼
[ Redmi Buds / Bluetooth Headset ]       [ Phone 3.5mm Jack / Internal Speaker ]
```

---

## Intelligent Auto-Detection

- **Auto-Detection (Default)**:
  - If a Bluetooth headset (e.g. `Redmi Buds 6 Play`) is connected to the phone, Audio Arch automatically selects the **Bluetooth backend** and streams audio straight into your earbuds.
  - If no Bluetooth device is connected, Audio Arch automatically selects the **Native C ALSA backend** for lowest possible ~23ms latency to the 3.5mm headphone jack or internal speaker.
- **Manual Overrides**:
  - Force Bluetooth: `./audio_arch.sh --bt`
  - Force Native C ALSA: `./audio_arch.sh --c`

---

## Why Bluetooth Audio Requires Android's Audio Stack

- Physical DAC chips (ALSA `/dev/snd/pcmC0D0p`) are hardwired strictly to the phone's internal speakers and 3.5mm analog jack.
- Bluetooth headsets receive digital radio packets over the 2.4GHz Broadcom chip (`BCM4330`).
- The SBC audio encoder and Bluetooth A2DP service live inside Android's userspace stack (`com.android.bluetooth`).
- Audio Arch bridges desktop audio directly into Android's native audio system with zero pipe delay and dynamic bufferbloat draining, giving you clean, non-stuttering sound in your Bluetooth earbuds.

---

## Quick Usage

### 1. Start Streaming
```bash
./audio_arch.sh
```
Automatically detects your Bluetooth earbuds or 3.5mm jack and begins streaming. Press `Ctrl+C` to disconnect and restore PC desktop sound.

### 2. Send a 1-Second Test Chime
```bash
./audio_arch.sh --test
```
Plays a quick audio chime to confirm you can hear sound in your earbuds.

### 3. Check Status
```bash
./audio_arch.sh --status
```

### 4. Stop Background Phone Daemon
```bash
./audio_arch.sh --kill-daemon
```

---

## Command Options

| Option | Description |
|---|---|
| `--bt`, `--bluetooth` | Force routing to Bluetooth earphones |
| `--alsa`, `--c` | Force routing to 3.5mm jack / internal speaker (Native C) |
| `--test` | Send a 1-second test audio chime to verify sound |
| `--status` | Check link state, Bluetooth detection, and daemon status |
| `--kill-daemon` | Stop audio daemons on the phone |
| `--build` | Force re-compilation of daemons |
| `--latency MS` | Target latency buffer in ms (default: `15`) |
| `--port PORT` | TCP port for ADB forward (default: `12345`) |
| `--rate RATE` | Audio sample rate in Hz (default: `44100`) |
| `--help` | Display help message |
