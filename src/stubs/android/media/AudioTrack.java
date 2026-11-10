package android.media;

public class AudioTrack {
    public static final int MODE_STREAM = 1;
    public static final int STATE_INITIALIZED = 1;
    public static int getMinBufferSize(int sampleRateInHz, int channelConfig, int audioFormat) { return 0; }
    public AudioTrack(int streamType, int sampleRateInHz, int channelConfig, int audioFormat, int bufferSizeInBytes, int mode) {}
    public void play() {}
    public void pause() {}
    public void flush() {}
    public void stop() {}
    public void release() {}
    public int write(byte[] audioData, int offsetInBytes, int sizeInBytes) { return 0; }
    public int getState() { return 0; }
    public int getPlaybackHeadPosition() { return 0; }
}
