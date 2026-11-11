# Audio Arch - Ultra-Low Latency USB Audio Card for Android

Audio Arch transforms low-end and legacy Android devices (tested on Android 4.4 KitKat `GT-S5282`, single-core 850MHz ARMv7, 512MB RAM) into a high-fidelity external sound card and wireless Bluetooth bridge for Arch Linux with near-zero latency.

Audio Arch operates completely offline over a physical USB cable using ADB port forwarding (`127.0.0.1`), leaving your Ethernet and Wi-Fi networks completely untouched.

---

## Key Features

- **Extreme Low-End Android Support**: Designed specifically to run smoothly on legacy single-core hardware (tested on Samsung Galaxy Pocket Neo / Star `GT-S5282` with an 850MHz CPU and 512MB RAM).
- **Zero-APK Architecture**: No sideloading or Google Play Store apps required. Runs directly out of `/data/local/tmp` via native static C binaries and Dalvik's built-in `app_process`.
- **Dual-Backend Routing**:
  - **Native C ALSA Backend**: Direct kernel ALSA playback (`/dev/snd/pcmC0D0p`) via TinyALSA for ~23ms latency through the phone's 3.5mm headphone jack or internal speaker (<0.1% CPU, ~80 KB RAM).
  - **Bluetooth AudioTrack Backend**: Java receiver interacting with Android's `AudioTrack` and `AudioPolicyManager` to stream desktop audio to connected Bluetooth earbuds (e.g., Redmi Buds, AirPods, Galaxy Buds).
- **Intelligent Auto-Detection**: Automatically detects when Bluetooth earbuds are connected via `dumpsys audio` and routes audio seamlessly; falls back to Native C ALSA when no Bluetooth device is present.
- **Bufferbloat Elimination**: Eliminates Linux pipe buffering (clamped to 4 KB via `fcntl F_SETPIPE_SZ`), enforces `TCP_NODELAY` and 4 KB socket buffers, and drains stale backlogs dynamically to prevent drift and stutter.
- **Safe Signal Handling**: Automatically creates a dedicated PipeWire virtual sink (`AudioArch-Sink`), makes it the default, and cleanly restores your original desktop speakers/headphones upon pressing `Ctrl+C`.

---

## Architecture

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
   audio_receiver.jar                       audioarch_daemon (96KB binary)
   • Runs via Dalvik app_process            • Direct kernel ALSA (/dev/snd/pcmC0D0p)
   • Dynamic backlog drainer                • Double-buffer (512x2 = ~23ms latency)
   • Binds to Android AudioPolicy           • Statically compiled with TinyALSA
   • Auto-routes to Bluetooth earbuds       • Consumes ~80 KB RAM, <0.1% CPU
         │                                        │
         ▼                                        ▼
[ Redmi Buds / Bluetooth Headset ]       [ Phone 3.5mm Jack / Internal Speaker ]
```

---

## Why Bluetooth Audio Requires Android's Audio Stack

- Physical DAC chips (ALSA `/dev/snd/pcmC0D0p`) are hardwired strictly to the phone's internal speakers and 3.5mm analog jack.
- Bluetooth headsets receive digital radio packets over the phone's 2.4GHz wireless chip (e.g., Broadcom BCM4330).
- The SBC/AAC audio encoder and Bluetooth A2DP service live inside Android's userspace stack (`com.android.bluetooth`).
- Audio Arch bridges desktop audio directly into Android's native audio system with zero pipe delay and dynamic bufferbloat draining, giving you clean, non-stuttering sound in your Bluetooth earbuds.

---

## Prerequisites

### Host (Arch Linux)
Ensure standard tools and audio utilities are installed:
```bash
sudo pacman -S android-tools pipewire-pulse libpulse python ffmpeg
```

### Android Device
1. Enable **Developer Options** (tap *Build Number* 7 times in *Settings > About Phone*).
2. Enable **USB Debugging**.
3. Connect the device via USB and authorize the computer when prompted.
4. *(Optional)* Pair and connect your Bluetooth earbuds to the phone if streaming wirelessly.

---

## Quick Usage

### 1. Start Streaming Desktop Audio
```bash
./audio_arch.sh
```
Automatically detects your Bluetooth earbuds or 3.5mm jack and begins streaming. Press `Ctrl+C` at any time to disconnect cleanly and restore your PC's original audio output.

### 2. Send a 1-Second Test Chime
```bash
./audio_arch.sh --test
```
Generates and plays a quick audio chime to confirm you can hear sound through your phone or Bluetooth earbuds.

### 3. Check System & Link Status
```bash
./audio_arch.sh --status
```
Displays device model, Android version, active Bluetooth connections, ADB port forwards, and daemon status.

### 4. Stop Background Phone Daemon
```bash
./audio_arch.sh --kill-daemon
```
Terminates any background daemons running on the phone and cleans up port forwards.

---

## Command-Line Options

| Option | Description |
|---|---|
| *(no args)* | Auto-detect output and start low-latency audio stream |
| `--bt`, `--bluetooth` | Force routing through Bluetooth AudioTrack backend |
| `--alsa`, `--c` | Force routing through Native C ALSA backend (3.5mm jack / speaker) |
| `--test` | Send a 1-second test audio chime to verify sound |
| `--status` | Check connection state, Bluetooth device, and daemon status |
| `--kill-daemon` | Stop running audio daemons on the phone |
| `--build` | Force re-compilation of both C and Java daemons |
| `--latency MS` | Target latency buffer in ms (default: `15`) |
| `--port PORT` | TCP port for ADB forward (default: `12345`) |
| `--rate RATE` | Audio sample rate in Hz (default: `44100`) |
| `--help` | Display usage instructions |

---

## Repository & Build Details

Audio Arch is fully self-contained. You do not need to install an Android NDK or full Android SDK:
- **TinyALSA**: Automatically cloned on-demand during the build process if missing. It is excluded from the Git repository via `.gitignore` to keep the codebase clean.
- **Cross-Compiler**: Automatically fetches a lightweight Bootlin ARMv5/ARMv7 musl toolchain into `~/.cache/audio_arch/` to produce a fully static, standalone binary (`build/audioarch_daemon`).
- **D8 Compiler**: Automatically downloads Google's R8/D8 dexer jar into `~/.cache/audio_arch/` to dex the compiled Java bytecode into `build/audio_receiver.jar`.
- **Compile-Time Stubs**: Included under `src/stubs/android/media/` so `javac` can compile `AudioReceiver.java` without requiring the multi-gigabyte Android SDK.

To recompile all daemons manually:
```bash
./audio_arch.sh --build
```

---

## Desktop Launcher Integration

A ready-to-use desktop entry is included:
```bash
cp audio_arch.desktop ~/.local/share/applications/
```
You can then launch **Audio Arch** directly from your application launcher (Rofi, Wofi, GNOME, KDE, etc.) with a single click.

---

## Troubleshooting & FAQ

### Audio cuts out or broken pipe occurs
- If the phone goes to deep sleep, USB sleep states may disconnect the tunnel. Audio Arch automatically executes `svc power stayon usb` to keep the CPU awake while charging.
- If you disconnect the USB cable, Audio Arch will automatically pause and wait for the USB connection to reappear before resuming the stream.
- If an old instance of the daemon is stuck, run:
  ```bash
  ./audio_arch.sh --kill-daemon
  ```
  Then run `./audio_arch.sh` again.

### How to adjust volume
- **Hardware Volume Buttons**: Use the physical volume buttons on your phone to adjust the analog or Bluetooth output volume directly.
- **Host Mixer**: You can also adjust the volume of individual applications or the `AudioArch-Sink` master volume via `pavucontrol` or your desktop volume applet.

### Restoring host audio if interrupted
If the terminal was killed abruptly (`kill -9`) without letting `Ctrl+C` run the exit trap:
```bash
pactl set-default-sink $(pactl list short sinks | grep -v AudioArch | head -n1 | awk '{print $2}')
pactl unload-module $(pactl list modules short | grep module-null-sink | awk '{print $1}')
```
