package android.content;

import android.os.Parcelable;

public class Intent {
    public String getAction() { return null; }
    public int getIntExtra(String name, int defaultValue) { return defaultValue; }
    public <T extends Parcelable> T getParcelableExtra(String name) { return null; }
}

