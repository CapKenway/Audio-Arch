package android.content;

public abstract class Context {
    public abstract Context getApplicationContext();
    public abstract Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter);
    public abstract void unregisterReceiver(BroadcastReceiver receiver);
}

