package cl.zmremake;

import java.nio.ByteBuffer;

public final class Native {
    static { System.loadLibrary("zmnative"); }
    public static native boolean open(String romPath, String savePath);
    public static native int audioRate();
    public static native void setKeys(int keys);
    public static native int runFrame(ByteBuffer video, short[] audio);
    public static native void close();
}
