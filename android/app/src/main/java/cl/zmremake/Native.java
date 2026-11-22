package cl.zmremake;

import java.nio.ByteBuffer;

public final class Native {
    static { System.loadLibrary("zmnative"); }
    public static native boolean open(String romPath, String savePath);
    public static native int audioRate();
    public static native void setKeys(int keys);
    public static native int runFrame(ByteBuffer video, ByteBuffer mask, short[] audio);
    public static native void close();
    public static native String info();
    public static native int scrollX();
    public static native int scrollY();
    public static native int scrollBg();
    public static native void setTest(boolean on);
    public static native void testStats(int[] out);
    public static native void requestState(String path, int action); // 1 guardar, 2 cargar
    public static native int stateResult();
    public static native void setPaused(boolean on);
}
