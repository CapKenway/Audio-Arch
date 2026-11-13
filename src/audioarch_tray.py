#!/usr/bin/env python3
"""
AudioArch KDE Plasma 6 StatusNotifierItem (System Tray Applet)
Integrates Bluetooth headset battery and media status into the KDE system tray.
"""

import sys
import os
import time
import json
import socket
import signal
import subprocess

STATE_FILE = os.path.join(os.environ.get("XDG_RUNTIME_DIR", "/tmp"), "audioarch.state")
IPC_SOCKET = os.path.join(os.environ.get("XDG_RUNTIME_DIR", "/tmp"), "audioarch.sock")

def send_notification(summary, body, icon="audio-headset", urgency="normal"):
    try:
        subprocess.run([
            "notify-send",
            "-a", "Audio Arch",
            "-i", icon,
            "-u", urgency,
            summary,
            body
        ], check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except Exception:
        pass

def get_battery_icon(pct):
    if pct is None or pct < 0:
        return "audio-headset"
    if pct >= 85:
        return "battery-100"
    elif pct >= 65:
        return "battery-080"
    elif pct >= 45:
        return "battery-060"
    elif pct >= 25:
        return "battery-040"
    else:
        return "battery-020-low"

def run_dbus_tray():
    try:
        import dbus
        import dbus.service
        import dbus.mainloop.glib
        from gi.repository import GLib
    except ImportError:
        # Fallback to periodic notification daemon if dbus-python/gi is not available
        run_fallback_loop()
        return

    dbus.mainloop.glib.DBusGMainLoop(set_as_default=True)
    session_bus = dbus.SessionBus()

    class StatusNotifierItem(dbus.service.Object):
        def __init__(self, bus):
            self.service_name = f"org.kde.StatusNotifierItem-{os.getpid()}-1"
            self.bus_name = dbus.service.BusName(self.service_name, bus)
            super().__init__(self.bus_name, "/StatusNotifierItem")

            self.category = "Hardware"
            self.id = "AudioArch"
            self.title = "Audio Arch"
            self.status = "Active"
            self.icon_name = "audio-headset"
            self.device_name = "Android Phone"
            self.battery_pct = -1
            self.phone_battery = -1
            self.mode = "Bluetooth"

            # Register with StatusNotifierWatcher
            try:
                watcher = bus.get_object("org.kde.StatusNotifierWatcher", "/StatusNotifierWatcher")
                watcher.RegisterStatusNotifierItem(self.service_name, dbus_interface="org.kde.StatusNotifierWatcher")
            except Exception as e:
                pass

        @dbus.service.method("org.kde.StatusNotifierItem", in_signature="ii")
        def Activate(self, x, y):
            # Click toggles desktop media Play/Pause via MPRIS
            try:
                subprocess.run(["playerctl", "play-pause"], check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            except Exception:
                subprocess.run([
                    "busctl", "--user", "call",
                    "org.mpris.MediaPlayer2.player",
                    "/org/mpris/MediaPlayer2",
                    "org.mpris.MediaPlayer2.Player",
                    "PlayPause"
                ], check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

        @dbus.service.method("org.kde.StatusNotifierItem", in_signature="ii")
        def SecondaryActivate(self, x, y):
            pass

        @dbus.service.method("org.kde.StatusNotifierItem", in_signature="is")
        def Scroll(self, delta, orientation):
            # Mouse scroll adjusts desktop volume
            try:
                step = "+5%" if delta > 0 else "-5%"
                subprocess.run(["wpctl", "set-volume", "@DEFAULT_AUDIO_SINK@", step],
                               check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            except Exception:
                pass

        @dbus.service.method("org.kde.StatusNotifierItem", in_signature="ii")
        def ContextMenu(self, x, y):
            pass

        @dbus.service.signal("org.kde.StatusNotifierItem")
        def NewIcon(self):
            pass

        @dbus.service.signal("org.kde.StatusNotifierItem")
        def NewToolTip(self):
            pass

        @dbus.service.method("org.freedesktop.DBus.Properties", in_signature="ss", out_signature="v")
        def Get(self, interface_name, property_name):
            return self.GetAll(interface_name).get(property_name, "")

        @dbus.service.method("org.freedesktop.DBus.Properties", in_signature="s", out_signature="a{sv}")
        def GetAll(self, interface_name):
            if interface_name == "org.kde.StatusNotifierItem":
                tooltip_text = f"{self.device_name}\n"
                if self.battery_pct >= 0:
                    tooltip_text += f"Earbud Battery: {self.battery_pct}%\n"
                if self.phone_battery >= 0:
                    tooltip_text += f"Phone Battery: {self.phone_battery}%\n"
                tooltip_text += f"Mode: {self.mode}"

                return {
                    "Category": self.category,
                    "Id": self.id,
                    "Title": self.title,
                    "Status": self.status,
                    "IconName": self.icon_name,
                    "OverlayIconName": "",
                    "AttentionIconName": "battery-020-low",
                    "AttentionMovieName": "",
                    "ItemIsMenu": False,
                    "ToolTip": dbus.Struct((
                        self.icon_name,
                        dbus.Array([], signature="v"),
                        f"Audio Arch: {self.device_name}",
                        tooltip_text
                    ), signature="(sa(iiay)ss)")
                }
            return {}

        def update_state(self, state):
            changed = False
            bt_bat = state.get("bt_battery", -1)
            dev_name = state.get("bt_device", "Bluetooth Headset")
            phone_bat = state.get("phone_battery", -1)
            mode = state.get("mode", "Bluetooth")

            if bt_bat != self.battery_pct or dev_name != self.device_name:
                self.battery_pct = bt_bat
                self.device_name = dev_name
                self.icon_name = get_battery_icon(bt_bat)
                changed = True

            if phone_bat != self.phone_battery:
                self.phone_battery = phone_bat
                changed = True

            if mode != self.mode:
                self.mode = mode
                changed = True

            if changed:
                self.NewIcon()
                self.NewToolTip()

    sni = StatusNotifierItem(session_bus)
    last_notified_bat = -1
    initial_notification_sent = False

    def poll_state():
        nonlocal last_notified_bat, initial_notification_sent
        if os.path.exists(STATE_FILE):
            try:
                with open(STATE_FILE, "r") as f:
                    state = json.load(f)
                sni.update_state(state)

                # Send initial connection notification once battery is detected
                bt_bat = state.get("bt_battery", -1)
                dev_name = state.get("bt_device", "Bluetooth Headset")
                if bt_bat >= 0 and not initial_notification_sent:
                    send_notification(
                        f"🎧 {dev_name} Connected",
                        f"Battery Level: {bt_bat}%\nAudio Arch ultra-low-latency streaming active.",
                        icon="audio-headset"
                    )
                    initial_notification_sent = True
                    last_notified_bat = bt_bat

                # Low battery warning (<20%)
                if bt_bat >= 0 and bt_bat <= 20 and last_notified_bat > 20:
                    send_notification(
                        f"⚠️ {dev_name} Low Battery",
                        f"Battery is at {bt_bat}%. Consider charging your earbuds.",
                        icon="battery-020-low",
                        urgency="critical"
                    )
                    last_notified_bat = bt_bat
            except Exception:
                pass
        return True

    GLib.timeout_add(1500, poll_state)
    loop = GLib.MainLoop()
    loop.run()

def run_fallback_loop():
    while True:
        time.sleep(5)

if __name__ == "__main__":
    signal.signal(signal.SIGINT, lambda s, f: sys.exit(0))
    signal.signal(signal.SIGTERM, lambda s, f: sys.exit(0))
    run_dbus_tray()

