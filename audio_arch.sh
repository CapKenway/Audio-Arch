#!/usr/bin/env bash

# ==============================================================================
# Audio Arch - Ultra Low Latency USB Audio Card for Android
# ------------------------------------------------------------------------------
# Targets legacy & modern Android devices (including low-end Android 4.4 KitKat)
# Supports BOTH:
#   1. Bluetooth Earphones / Headsets (via low-latency AudioTrack daemon)
#   2. 3.5mm Headphone Jack & Speakers (via Native C ALSA direct-to-DAC daemon)
# Auto-detects connected Bluetooth audio devices (e.g. Redmi Buds).
# Zero network tampering (operates locally via ADB port forward over physical USB).
# Bit-perfect CD quality (16-bit 44.1kHz Stereo PCM).
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="${SCRIPT_DIR}/build"
SRC_DIR="${SCRIPT_DIR}/src"

# Backends
MODE="auto" # "auto", "bt", or "alsa"
ACTIVE_BACKEND="alsa"
BT_DEVICE_NAME=""
DAEMON_VERSION="1.2.0"
REMOTE_VER_FILE="/data/local/tmp/audioarch_version"
FORCE_DEPLOY=false
FORCE_RESTART=false

# Native C Daemon (Card 0 DAC)
C_BIN="audioarch_daemon"
LOCAL_C_BIN="${BUILD_DIR}/${C_BIN}"
REMOTE_C_BIN="/data/local/tmp/${C_BIN}"
REMOTE_C_LOG="/data/local/tmp/audioarch_c.log"

# Bluetooth / AudioTrack Daemon
BT_JAR="audio_receiver.jar"
LOCAL_BT_JAR="${BUILD_DIR}/${BT_JAR}"
REMOTE_BT_JAR="/data/local/tmp/${BT_JAR}"
REMOTE_BT_LOG="/data/local/tmp/audio_receiver.log"
REMOTE_CACHE="/data/local/tmp/dalvik-cache"

PORT=12345
SAMPLE_RATE=44100
CHANNELS=2
LATENCY_MS=15
CLI_LATENCY_SET=false
SINK_NAME="AudioArch-Sink"
SINK_DESC="Android Phone Audio Card"

STREAM_PID=""
SINK_MODULE_ID=""
ORIG_SINK=""
KEEP_RUNNING=true
KILL_DAEMON_ON_EXIT=false
CLEANED_UP=false

# ------------------------------------------------------------------------------
# Colors & Logging
# ------------------------------------------------------------------------------
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

info()  { echo -e "${BLUE}[*]${NC} $*"; }
ok()    { echo -e "${GREEN}[✓]${NC} $*"; }
warn()  { echo -e "${YELLOW}[!]${NC} $*"; }
err()   { echo -e "${RED}[✗]${NC} $*" >&2; }

# ------------------------------------------------------------------------------
# Help / Usage
# ------------------------------------------------------------------------------
usage() {
    cat << EOF
Usage: $(basename "$0") [OPTIONS]

Stream Arch Linux desktop audio to Android over USB with lowest latency.
Auto-routes to Bluetooth earphones/headset if connected, or direct ALSA DAC.

Options:
    --bt, --bluetooth Force routing to Bluetooth earphones (via AudioTrack)
    --alsa, --c       Force routing to phone 3.5mm jack/speaker (via Native C ALSA)
    --status          Check link state, version info, and Android daemon status
    --test            Send a test audio chime to verify sound on the phone
    --build           Force re-compilation of receiver daemons
    --restart         Force restart the audio daemon on the phone
    --force-deploy    Force re-deploying daemon files to the phone
    --kill-daemon     Stop all audio daemons on the phone and exit
    --port PORT       TCP port for ADB forward (default: 12345)
    --rate RATE       Audio sample rate in Hz (default: 44100)
    --latency MS      Latency buffer target in ms (default: 15)
    --help            Display this help message
EOF
    exit 0
}

# ------------------------------------------------------------------------------
# Parse Arguments
# ------------------------------------------------------------------------------
ACTION="run"
while [[ $# -gt 0 ]]; do
    case "$1" in
        --bt|--bluetooth)
            MODE="bt"
            shift
            ;;
        --alsa|--c)
            MODE="alsa"
            shift
            ;;
        --status)
            ACTION="status"
            shift
            ;;
        --test)
            ACTION="test"
            shift
            ;;
        --build)
            ACTION="build"
            shift
            ;;
        --restart)
            FORCE_RESTART=true
            shift
            ;;
        --force-deploy)
            FORCE_DEPLOY=true
            shift
            ;;
        --kill-daemon)
            ACTION="kill-daemon"
            shift
            ;;
        --port)
            PORT="$2"
            shift 2
            ;;
        --rate)
            SAMPLE_RATE="$2"
            shift 2
            ;;
        --latency)
            LATENCY_MS="$2"
            CLI_LATENCY_SET=true
            shift 2
            ;;
        -h|--help)
            usage
            ;;
        *)
            err "Unknown option: $1"
            usage
            ;;
    esac
done

# ------------------------------------------------------------------------------
# 1. Dependency Validation
# ------------------------------------------------------------------------------
check_dependencies() {
    local missing=()
    for cmd in adb pactl parec python3; do
        if ! command -v "$cmd" &>/dev/null; then
            missing+=("$cmd")
        fi
    done

    if [[ ${#missing[@]} -gt 0 ]]; then
        err "Missing required host tools: ${missing[*]}"
        err "Install them on Arch with: sudo pacman -S android-tools libpulse python"
        exit 1
    fi
}

# ------------------------------------------------------------------------------
# 2. Check Android Link State & Bluetooth Route
# ------------------------------------------------------------------------------
check_device() {
    if ! adb get-state &>/dev/null; then
        err "No Android device detected via ADB."
        err "Ensure USB debugging is enabled and the phone is connected via USB."
        exit 1
    fi

    local model
    model=$(adb shell getprop ro.product.model 2>/dev/null | tr -d '\r\n')
    local android_ver
    android_ver=$(adb shell getprop ro.build.version.release 2>/dev/null | tr -d '\r\n')
    info "Connected device: ${model:-Unknown} (Android ${android_ver:-Unknown})"

    BT_DEVICE_NAME=$(adb shell dumpsys audio 2>/dev/null | awk -F'=' '/mBluetoothName=/{print $2}' | tr -d '\r\n')

    # Read latency configured in Launcher app settings (if not explicitly overridden on CLI)
    if [[ "$CLI_LATENCY_SET" == "false" ]]; then
        local phone_latency
        phone_latency=$(adb shell "cat /data/local/tmp/audioarch_latency 2>/dev/null" | tr -d '\r\n[:space:]')
        if [[ -n "$phone_latency" && "$phone_latency" =~ ^[0-9]+$ ]]; then
            LATENCY_MS="$phone_latency"
            ok "Audio latency loaded from Launcher app settings: ${CYAN}${LATENCY_MS} ms${NC}"
        fi
    fi

    if [[ "$MODE" == "bt" ]]; then
        ACTIVE_BACKEND="bt"
        info "Mode forced: ${CYAN}Bluetooth Audio${NC} (via Android AudioTrack framework)"
    elif [[ "$MODE" == "alsa" ]]; then
        ACTIVE_BACKEND="alsa"
        info "Mode forced: ${CYAN}Native C ALSA DAC${NC} (direct to /dev/snd/pcmC0D0p)"
    elif [[ -n "$BT_DEVICE_NAME" ]]; then
        ACTIVE_BACKEND="bt"
        ok "Active Bluetooth audio device detected: ${GREEN}${BT_DEVICE_NAME}${NC}"
        info "Auto-selected backend: ${CYAN}Bluetooth Audio (AudioTrack)${NC}"
    else
        ACTIVE_BACKEND="alsa"
        info "No Bluetooth device detected."
        info "Auto-selected backend: ${CYAN}Native C ALSA DAC (3.5mm jack / speaker)${NC}"
    fi
}

# ------------------------------------------------------------------------------
# 3. Build Daemons
# ------------------------------------------------------------------------------
build_c_daemon() {
    info "Compiling native C ARM AudioArch daemon..."
    local toolchain_dir="${HOME}/.cache/audio_arch/toolchain"
    local arm_gcc="${toolchain_dir}/bin/arm-linux-gcc"
    local arm_strip="${toolchain_dir}/bin/arm-linux-strip"

    if [[ ! -x "$arm_gcc" ]]; then
        info "Downloading standalone ARM musl cross-compiler..."
        mkdir -p "$toolchain_dir"
        curl -s -L "https://toolchains.bootlin.com/downloads/releases/toolchains/armv5-eabi/tarballs/armv5-eabi--musl--stable-2024.05-1.tar.xz" | \
            tar -xJ -C "$toolchain_dir" --strip-components=1
        ok "ARM toolchain ready."
    fi

    if [[ ! -d "${SRC_DIR}/tinyalsa/include" ]]; then
        info "Downloading TinyALSA sources..."
        mkdir -p "${SRC_DIR}/tinyalsa"
        git clone --depth 1 https://github.com/tinyalsa/tinyalsa "${SRC_DIR}/tinyalsa"
        rm -rf "${SRC_DIR}/tinyalsa/.git"
        ok "TinyALSA sources ready."
    fi

    mkdir -p "${BUILD_DIR}"
    "$arm_gcc" -static -O2 \
        -I"${SRC_DIR}/tinyalsa/include" \
        -I"${SRC_DIR}/tinyalsa/src" \
        "${SRC_DIR}/audioarch_daemon.c" \
        "${SRC_DIR}/tinyalsa/src/pcm.c" \
        "${SRC_DIR}/tinyalsa/src/pcm_hw.c" \
        "${SRC_DIR}/tinyalsa/src/snd_card_plugin.c" \
        "${SRC_DIR}/tinyalsa/src/mixer.c" \
        "${SRC_DIR}/tinyalsa/src/mixer_hw.c" \
        "${SRC_DIR}/tinyalsa/src/mixer_plugin.c" \
        -lm \
        -o "${LOCAL_C_BIN}"

    if [[ -x "$arm_strip" ]]; then
        "$arm_strip" "${LOCAL_C_BIN}"
    fi
    ok "Native C daemon built: ${LOCAL_C_BIN} ($(du -h "${LOCAL_C_BIN}" | cut -f1))"
}

build_bt_daemon() {
    info "Compiling Bluetooth AudioReceiver DEX jar..."
    local cache_dir="${HOME}/.cache/audio_arch"
    local r8_jar="${cache_dir}/r8.jar"

    if [[ ! -f "$r8_jar" ]]; then
        mkdir -p "$cache_dir"
        curl -s -L -o "$r8_jar" "https://dl.google.com/dl/android/maven2/com/android/tools/r8/8.2.42/r8-8.2.42.jar"
    fi

    mkdir -p "${BUILD_DIR}"
    javac -source 7 -target 7 -d "${BUILD_DIR}" \
        "${SRC_DIR}/stubs/android/media/"*.java \
        "${SRC_DIR}/AudioReceiver.java" 2>/dev/null || \
    javac -source 8 -target 8 -d "${BUILD_DIR}" \
        "${SRC_DIR}/stubs/android/media/"*.java \
        "${SRC_DIR}/AudioReceiver.java"

    java -cp "$r8_jar" com.android.tools.r8.D8 \
        --no-desugaring \
        --min-api 19 \
        --output "${LOCAL_BT_JAR}" \
        "${BUILD_DIR}/com/audioarch/"*.class

    ok "Bluetooth receiver DEX jar built: ${LOCAL_BT_JAR}"
}

build_all() {
    build_c_daemon
    build_bt_daemon
}

# ------------------------------------------------------------------------------
# 4. Version & Daemon Detection Helpers
# ------------------------------------------------------------------------------
get_remote_version() {
    adb shell "cat ${REMOTE_VER_FILE} 2>/dev/null" | tr -d '\r\n[:space:]'
}

get_running_daemon_info() {
    # Returns "<mode> <pid>" where mode is "bt" or "alsa", or "none" if not running.
    # Strictly excludes com.audioarch.launcher!
    local daemon_line
    daemon_line=$(adb shell ps 2>/dev/null | grep -v "com.audioarch.launcher" | grep -E "audioarch_|audioarch_daemon|AudioReceiver" | head -n 1 || true)
    if [[ -z "$daemon_line" ]]; then
        echo "none"
        return
    fi

    local pid
    pid=$(echo "$daemon_line" | awk '{print $2}')
    local cmdline
    cmdline=$(adb shell "cat /proc/$pid/cmdline 2>/dev/null" | tr '\0' ' ')

    if [[ "$cmdline" =~ AudioReceiver|audioarch_bt_daemon ]]; then
        echo "bt $pid"
    elif [[ "$cmdline" =~ audioarch_daemon ]]; then
        echo "alsa $pid"
    elif [[ "$daemon_line" =~ audioarch_daemon ]]; then
        echo "alsa $pid"
    elif [[ "$daemon_line" =~ audioarch_|AudioReceiver ]]; then
        echo "bt $pid"
    else
        echo "none"
    fi
}

is_daemon_running() {
    local info
    info=$(get_running_daemon_info)
    local mode
    mode=$(echo "$info" | awk '{print $1}')
    [[ "$mode" == "$ACTIVE_BACKEND" ]]
}

# ------------------------------------------------------------------------------
# 5. Smart Deployment (Skips if already deployed & matches DAEMON_VERSION)
# ------------------------------------------------------------------------------
deploy_receiver() {
    local target_remote_file
    local target_local_file
    local daemon_name

    if [[ "$ACTIVE_BACKEND" == "bt" ]]; then
        target_remote_file="${REMOTE_BT_JAR}"
        target_local_file="${LOCAL_BT_JAR}"
        daemon_name="${BT_JAR}"
    else
        target_remote_file="${REMOTE_C_BIN}"
        target_local_file="${LOCAL_C_BIN}"
        daemon_name="${C_BIN}"
    fi

    local remote_ver
    remote_ver=$(get_remote_version)

    # Check if target remote file exists on the phone
    local remote_exists=false
    if adb shell "[ -f ${target_remote_file} ]" &>/dev/null; then
        remote_exists=true
    fi

    if [[ "$FORCE_DEPLOY" == "false" && "$remote_exists" == "true" && "$remote_ver" == "$DAEMON_VERSION" ]]; then
        ok "Daemon '${daemon_name}' is already up-to-date (v${DAEMON_VERSION}) on phone. Skipping deployment."
        return 0
    fi

    # Deployment required
    if [[ "$remote_exists" == "true" && -n "$remote_ver" && "$remote_ver" != "$DAEMON_VERSION" ]]; then
        info "Updating daemon on phone from v${remote_ver:-unknown} -> v${DAEMON_VERSION}..."
    else
        info "Deploying daemon '${daemon_name}' (v${DAEMON_VERSION}) to phone..."
    fi

    # If daemon is running, stop it before updating binary/jar
    local running_info
    running_info=$(get_running_daemon_info)
    if [[ "$running_info" != "none" ]]; then
        stop_daemon
    fi

    if [[ "$ACTIVE_BACKEND" == "bt" ]]; then
        if [[ ! -f "${LOCAL_BT_JAR}" ]]; then
            build_bt_daemon
        fi
        adb shell "mkdir -p ${REMOTE_CACHE}"
        adb push "${LOCAL_BT_JAR}" "${REMOTE_BT_JAR}" >/dev/null
        adb shell "echo '${DAEMON_VERSION}' > ${REMOTE_VER_FILE}"
        ok "Bluetooth receiver deployed (v${DAEMON_VERSION})."
    else
        if [[ ! -f "${LOCAL_C_BIN}" ]]; then
            build_c_daemon
        fi
        adb push "${LOCAL_C_BIN}" "${REMOTE_C_BIN}" >/dev/null
        adb shell "su -c 'chmod 755 ${REMOTE_C_BIN}' 2>/dev/null || chmod 755 ${REMOTE_C_BIN} 2>/dev/null || true"
        adb shell "echo '${DAEMON_VERSION}' > ${REMOTE_VER_FILE}"
        ok "Native C daemon deployed (v${DAEMON_VERSION})."
    fi
}

# ------------------------------------------------------------------------------
# 6. Daemon Lifecycle Management (Preserves active running daemons)
# ------------------------------------------------------------------------------
ensure_daemon_running() {
    local running_info
    running_info=$(get_running_daemon_info)
    local running_backend
    running_backend=$(echo "$running_info" | awk '{print $1}')
    local running_pid
    running_pid=$(echo "$running_info" | awk '{print $2}')

    if [[ "$FORCE_RESTART" == "true" ]]; then
        info "Force restart requested. Stopping active daemon..."
        stop_daemon
        start_daemon
        return 0
    fi

    if [[ "$running_backend" == "$ACTIVE_BACKEND" ]]; then
        ok "Audio daemon (PID ${running_pid}) is already active in ${CYAN}${ACTIVE_BACKEND}${NC} mode. Reusing running daemon."
        return 0
    fi

    if [[ "$running_backend" != "none" ]]; then
        info "Daemon is active in '${running_backend}' mode, but '${ACTIVE_BACKEND}' mode is required. Switching..."
        stop_daemon
    fi

    start_daemon
}

start_daemon() {
    adb shell "svc power stayon usb" &>/dev/null || true

    if [[ "$ACTIVE_BACKEND" == "bt" ]]; then
        info "Starting Bluetooth AudioReceiver daemon on phone..."
        adb shell "sh -c 'export ANDROID_DATA=/data/local/tmp; export CLASSPATH=${REMOTE_BT_JAR}; nohup app_process /system/bin --nice-name=audioarch_bt_daemon com.audioarch.AudioReceiver ${PORT} ${SAMPLE_RATE} > ${REMOTE_BT_LOG} 2>&1 &'"
        sleep 1
        if ! is_daemon_running; then
            err "Failed to start Bluetooth AudioReceiver on phone. Logs:"
            adb shell "cat ${REMOTE_BT_LOG}" 2>/dev/null || true
            exit 1
        fi
        local new_pid
        new_pid=$(get_running_daemon_info | awk '{print $2}')
        ok "Bluetooth AudioReceiver started successfully (PID: ${new_pid:-active}, routing to ${BT_DEVICE_NAME:-Bluetooth Earphones})."
    else
        info "Starting Native C ALSA daemon on phone..."
        adb shell "su -c 'nohup ${REMOTE_C_BIN} ${PORT} ${SAMPLE_RATE} 0 0 > ${REMOTE_C_LOG} 2>&1 &'" 2>/dev/null || \
        adb shell "nohup ${REMOTE_C_BIN} ${PORT} ${SAMPLE_RATE} 0 0 > ${REMOTE_C_LOG} 2>&1 &" 2>/dev/null || true
        sleep 1
        if ! is_daemon_running; then
            err "Failed to start Native ALSA daemon on phone. Logs:"
            adb shell "cat ${REMOTE_C_LOG}" 2>/dev/null || true
            exit 1
        fi
        local new_pid
        new_pid=$(get_running_daemon_info | awk '{print $2}')
        ok "Native C ALSA daemon started successfully (PID: ${new_pid:-active}, routing to 3.5mm jack / speaker)."
    fi
}

stop_daemon() {
    info "Stopping AudioArch daemon on phone..."
    local pids
    pids=$(adb shell ps 2>/dev/null | grep -v "com.audioarch.launcher" | grep -E "audioarch_|audioarch_daemon|AudioReceiver" | awk '{print $2}' || true)
    if [[ -n "$pids" ]]; then
        for pid in $pids; do
            adb shell "kill -9 $pid 2>/dev/null" || true
        done
        ok "Audio daemon stopped."
    else
        ok "No audio daemon was running."
    fi
    adb forward --remove "tcp:${PORT}" 2>/dev/null || true
}

check_status() {
    check_device
    echo ""
    info "Host Script Version: ${GREEN}v${DAEMON_VERSION}${NC}"
    local remote_ver
    remote_ver=$(get_remote_version)
    info "Phone Daemon Version: ${GREEN}${remote_ver:-None recorded}${NC}"
    info "Target Active Mode: ${CYAN}${ACTIVE_BACKEND}${NC}"
    if [[ "$ACTIVE_BACKEND" == "bt" ]]; then
        info "Bluetooth Device: ${GREEN}${BT_DEVICE_NAME:-None}${NC}"
    fi
    echo ""
    info "Port forward status:"
    adb forward --list | grep "${PORT}" || echo "  No forward active on port ${PORT}"
    echo ""
    info "Daemon process status:"
    local running_info
    running_info=$(get_running_daemon_info)
    if [[ "$running_info" != "none" ]]; then
        local r_backend
        r_backend=$(echo "$running_info" | awk '{print $1}')
        local r_pid
        r_pid=$(echo "$running_info" | awk '{print $2}')
        ok "Audio daemon is RUNNING on phone (PID: ${r_pid}, Mode: ${CYAN}${r_backend}${NC})."
        echo ""
        info "Recent logs:"
        if [[ "$r_backend" == "bt" ]]; then
            adb shell "cat ${REMOTE_BT_LOG} 2>/dev/null" || echo "  No logs found."
        else
            adb shell "cat ${REMOTE_C_LOG} 2>/dev/null" || echo "  No logs found."
        fi
    else
        warn "Audio daemon is STOPPED."
    fi
}

play_test_tone() {
    check_device
    deploy_receiver
    ensure_daemon_running

    info "Forwarding USB port ${PORT}..."
    adb forward "tcp:${PORT}" "tcp:${PORT}"
    sleep 0.5

    info "Sending 1-second test chime to phone..."
    if command -v ffmpeg &>/dev/null; then
        ffmpeg -f lavfi -i "sine=frequency=587.33:duration=0.8" -f s16le -ar 44100 -ac 2 -loglevel error - | python3 "${SRC_DIR}/fast_sender.py" "${PORT}"
        ok "Test chime sent! Sound should play through: ${GREEN}${BT_DEVICE_NAME:-Speakers/Headphones}${NC}"
    else
        err "ffmpeg is required for --test. Install with: sudo pacman -S ffmpeg"
    fi
}

# ------------------------------------------------------------------------------
# 6. Cleanup Handler
# ------------------------------------------------------------------------------
cleanup() {
    if [[ "${CLEANED_UP}" == "true" ]]; then
        return
    fi
    CLEANED_UP=true
    KEEP_RUNNING=false
    echo ""
    info "Shutting down audio pipeline..."

    if [[ -n "${STREAM_PID}" ]]; then
        kill "${STREAM_PID}" 2>/dev/null || true
        wait "${STREAM_PID}" 2>/dev/null || true
    fi

    adb forward --remove "tcp:${PORT}" 2>/dev/null || true

    if [[ -n "${ORIG_SINK}" ]]; then
        info "Restoring default audio sink: ${ORIG_SINK}"
        pactl set-default-sink "${ORIG_SINK}" 2>/dev/null || true
    fi

    if [[ -n "${SINK_MODULE_ID}" ]]; then
        info "Unloading virtual audio device..."
        pactl unload-module "${SINK_MODULE_ID}" 2>/dev/null || true
    fi

    if [[ "${KILL_DAEMON_ON_EXIT}" == "true" ]]; then
        stop_daemon
    else
        info "Phone daemon kept active for instant reconnection."
        info "To stop the daemon, run: ./$(basename "$0") --kill-daemon"
    fi

    ok "Desktop audio cleanly restored."
    exit 0
}

# ------------------------------------------------------------------------------
# Main Flow
# ------------------------------------------------------------------------------
check_dependencies

case "${ACTION}" in
    build)
        build_all
        exit 0
        ;;
    status)
        check_status
        exit 0
        ;;
    test)
        play_test_tone
        exit 0
        ;;
    kill-daemon)
        check_device
        stop_daemon
        exit 0
        ;;
    run)
        ;;
esac

check_device
deploy_receiver
ensure_daemon_running

info "Mapping USB TCP port ${PORT} -> phone:${PORT}..."
adb forward "tcp:${PORT}" "tcp:${PORT}"
ok "Port mapped."

# Verify receiver listener socket
if ! adb shell "netstat -an 2>/dev/null | grep -E '12345.*LISTEN'" &>/dev/null; then
    warn "Daemon port ${PORT} is not listening on phone. Starting daemon..."
    start_daemon
    adb forward "tcp:${PORT}" "tcp:${PORT}"
    sleep 0.5
fi
ok "Phone receiver connection verified."

# Save original default sink
ORIG_SINK=$(pactl get-default-sink 2>/dev/null || pactl info | awk -F': ' '/Default Sink:/{print $2}')
info "Current default audio sink: ${ORIG_SINK}"

# Create Virtual Audio Sink
info "Creating virtual audio device '${SINK_NAME}'..."
pactl list modules short | awk -v name="${SINK_NAME}" '$0 ~ name {print $1}' | xargs -r -n 1 pactl unload-module 2>/dev/null || true

SINK_MODULE_ID=$(pactl load-module module-null-sink \
    sink_name="${SINK_NAME}" \
    rate="${SAMPLE_RATE}" \
    channels="${CHANNELS}" \
    sink_properties="device.description='${SINK_DESC}' node.latency=128/${SAMPLE_RATE}")

ok "Virtual sink created (Module ID: ${SINK_MODULE_ID})."
pactl set-default-sink "${SINK_NAME}"

trap cleanup SIGINT SIGTERM EXIT

info "Starting ultra-low-latency PCM audio stream (16-bit 44.1kHz Stereo)..."
echo -e "${GREEN}================================================================${NC}"
echo -e "${GREEN} Audio Arch is LIVE! Phone is now active as your sound card.${NC}"
if [[ "$ACTIVE_BACKEND" == "bt" ]]; then
    echo -e "${CYAN} Output Destination: ${GREEN}${BT_DEVICE_NAME:-Bluetooth Earphones}${NC}"
else
    echo -e "${CYAN} Output Destination: ${GREEN}Phone 3.5mm Jack / Internal Speaker (Native C)${NC}"
fi
echo -e "${GREEN} Hardware volume keys on the phone will adjust the volume.${NC}"
echo -e "${YELLOW} Press Ctrl+C at any time to disconnect safely.${NC}"
echo -e "${GREEN}================================================================${NC}"

while $KEEP_RUNNING; do
    if ! adb get-state &>/dev/null; then
        warn "Android device disconnected. Waiting for USB reconnection..."
        adb wait-for-device 2>/dev/null || true
        sleep 1
    fi

    if ! is_daemon_running; then
        warn "Audio daemon dropped. Restarting..."
        ensure_daemon_running || { sleep 2; continue; }
        adb forward "tcp:${PORT}" "tcp:${PORT}"
        sleep 0.5
    fi

    PIPEWIRE_LATENCY="128/${SAMPLE_RATE}" PULSE_LATENCY_MSEC="${LATENCY_MS}" parec --raw -d "${SINK_NAME}.monitor" \
        --format=s16le \
        --rate="${SAMPLE_RATE}" \
        --channels="${CHANNELS}" \
        --latency-msec="${LATENCY_MS}" \
        --process-time-msec="${LATENCY_MS}" 2>/dev/null | python3 "${SRC_DIR}/fast_sender.py" "${PORT}" &
    STREAM_PID=$!
    wait "${STREAM_PID}" 2>/dev/null || true
    if $KEEP_RUNNING; then
        warn "Stream connection dropped, reconnecting in 1s..."
        sleep 1
    fi
done
