package android.bluetooth;

import android.content.Context;
import java.util.Set;

public final class BluetoothAdapter {
    public static synchronized BluetoothAdapter getDefaultAdapter() { return null; }
    public boolean isEnabled() { return false; }
    public BluetoothDevice getRemoteDevice(String address) { return null; }
    public Set<BluetoothDevice> getBondedDevices() { return null; }
    public boolean getProfileProxy(Context context, BluetoothProfile.ServiceListener listener, int profile) { return false; }
    public void closeProfileProxy(int profile, BluetoothProfile proxy) {}
}

