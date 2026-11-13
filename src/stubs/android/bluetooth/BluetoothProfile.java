package android.bluetooth;

import java.util.List;

public interface BluetoothProfile {
    public static final int HEADSET = 1;
    public static final int A2DP = 2;
    public static final int STATE_DISCONNECTED = 0;
    public static final int STATE_CONNECTING = 1;
    public static final int STATE_CONNECTED = 2;
    public static final int STATE_DISCONNECTING = 3;
    public static final String EXTRA_STATE = "android.bluetooth.profile.extra.STATE";

    public List<BluetoothDevice> getConnectedDevices();

    public interface ServiceListener {
        public void onServiceConnected(int profile, BluetoothProfile proxy);
        public void onServiceDisconnected(int profile);
    }
}

