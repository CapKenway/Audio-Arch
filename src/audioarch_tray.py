#!/usr/bin/env python3
"""
AudioArch KDE Plasma 6 StatusNotifierItem (System Tray Applet)
Integrates Bluetooth headset battery and media status into the KDE system tray.
Lifecycle aware: passive and calm when phone is not connected.
"""

import sys
import os
import time
import json
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

def get_battery_icon(pct, is_streaming=True):
    if not is_streaming:
        return "audio-headset"
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
            self.status = "Passive"
            self.icon_name = "audio-headset"
            self.device_name = "Waiting for phone..."
            self.battery_pct = -1
            self.battery_source = "cache"
            self.battery_time = ""
            self.phone_battery = -1
            self.mode = "idle"

            try:
                watcher = bus.get_object("org.kde.StatusNotifierWatcher", "/StatusNotifierWatcher")
                watcher.RegisterStatusNotifierItem(self.service_name, dbus_interface="org.kde.StatusNotifierWatcher")
            except Exception:
                pass

        @dbus.service.method("org.kde.StatusNotifierItem", in_signature="ii")
        def Activate(self, x, y):
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

        @dbus.service.signal("org.kde.StatusNotifierItem", signature="s")
        def NewStatus(self, status):
            pass

        @dbus.service.method("org.freedesktop.DBus.Properties", in_signature="ss", out_signature="v")
        def Get(self, interface_name, property_name):
            props = self.GetAll(interface_name)
            if property_name in props:
                return props[property_name]
            raise dbus.exceptions.DBusException(
                f"Property {property_name} not found on interface {interface_name}",
                name="org.freedesktop.DBus.Error.InvalidArgs"
            )

        @dbus.service.method("org.freedesktop.DBus.Properties", in_signature="s", out_signature="a{sv}")
        def GetAll(self, interface_name):
            if interface_name == "org.kde.StatusNotifierItem":
                if self.status == "Passive":
                    tooltip_text = "Audio Arch\nPhone is disconnected. PC speakers active."
                    header = "Audio Arch (Idle)"
                else:
                    tooltip_text = f"{self.device_name}\n"
                    if self.battery_pct >= 0:
                        src_str = f" [{self.battery_source}]" if self.battery_source else ""
                        if self.battery_time:
                            src_str = f" [{self.battery_source} @ {self.battery_time}]"
                        tooltip_text += f"Earbud Battery: {self.battery_pct}%{src_str}\n"
                    if self.phone_battery >= 0:
                        tooltip_text += f"Phone Battery: {self.phone_battery}%\n"
                    tooltip_text += f"Mode: {self.mode} (Ultra-low-latency)"
                    header = f"Audio Arch: {self.device_name}"

                return {
                    "Category": self.category,
                    "Id": self.id,
                    "Title": self.title,
                    "Status": self.status,
                    "IconName": self.icon_name,
                    "OverlayIconName": "",
                    "AttentionIconName": "battery-020-low",
                    "AttentionMovieName": "",
                    "ItemIsMenu": dbus.Boolean(False),
                    "ToolTip": dbus.Struct((
                        self.icon_name,
                        dbus.Array([], signature="v"),
                        header,
                        tooltip_text
                    ), signature="(sa(iiay)ss)")
                }
            return {}

        def update_state(self, state):
            changed = False
            cur_status = state.get("status", "waiting_for_device")
            is_streaming = (cur_status == "streaming")
            new_dbus_status = "Active" if is_streaming else "Passive"

            bt_bat = state.get("bt_battery", -1)
            dev_name = state.get("bt_device", "Bluetooth Headset")
            if is_streaming and dev_name in ["Waiting for phone...", "", "None"]:
                dev_name = "Bluetooth Earbuds"
            phone_bat = state.get("phone_battery", -1)
            mode = state.get("mode", "Bluetooth")

            new_icon = get_battery_icon(bt_bat, is_streaming)
            if new_icon != self.icon_name:
                self.icon_name = new_icon
                changed = True

            if bt_bat != self.battery_pct or dev_name != self.device_name:
                self.battery_pct = bt_bat
                self.device_name = dev_name
                changed = True

            if phone_bat != self.phone_battery:
                self.phone_battery = phone_bat
                changed = True

            src = state.get("bt_battery_source", "cache")
            ts = state.get("bt_battery_time", "")
            if src != self.battery_source or ts != self.battery_time:
                self.battery_source = src
                self.battery_time = ts
                changed = True

            if mode != self.mode:
                self.mode = mode
                changed = True

            if new_dbus_status != self.status:
                self.status = new_dbus_status
                changed = True
                try: self.NewStatus(self.status)
                except Exception: pass

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

                cur_status = state.get("status", "waiting_for_device")
                if cur_status != "streaming":
                    initial_notification_sent = False
                    return True

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
