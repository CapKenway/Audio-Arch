package android.bluetooth;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class BluetoothSocket implements Closeable {
    public void connect() throws IOException {}
    public void close() throws IOException {}
    public InputStream getInputStream() throws IOException { return null; }
    public OutputStream getOutputStream() throws IOException { return null; }
    public boolean isConnected() { return false; }
}

