package android.bluetooth;

import android.os.Parcelable;
import java.io.IOException;
import java.util.UUID;

public final class BluetoothDevice implements Parcelable {
    public static final String EXTRA_DEVICE = "android.bluetooth.device.extra.DEVICE";
    public static final String ACTION_ACL_DISCONNECTED = "android.bluetooth.device.action.ACL_DISCONNECTED";

    public String getName() { return null; }
    public String getAddress() { return null; }
    public BluetoothSocket createInsecureRfcommSocketToServiceRecord(UUID uuid) throws IOException { return null; }
    public BluetoothSocket createRfcommSocketToServiceRecord(UUID uuid) throws IOException { return null; }
}

