package org.x2.export;

public final class NativeRemux {
    static {
        System.loadLibrary("x2export");
    }
    public interface Progress {
        void update(double progress);
        boolean cancelled();
    }
    public static native String run(String input, String output, double expectedDuration, Progress progress);
    private NativeRemux() {}
}
