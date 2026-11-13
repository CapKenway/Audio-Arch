package android.bluetooth;

import java.util.List;

public final class BluetoothHeadset implements BluetoothProfile {
    public List<BluetoothDevice> getConnectedDevices() { return null; }
    public boolean setPriority(BluetoothDevice device, int priority) { return false; }
    public boolean disconnect(BluetoothDevice device) { return false; }
}

