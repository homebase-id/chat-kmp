package id.homebase.blake3;

import java.nio.ByteBuffer;

/** The official BLAKE3 C implementation, with NEON on ARM: plain hashing with the 32-byte output. */
public final class NativeBlake3 {
    static {
        System.loadLibrary("homebase_blake3");
    }

    private NativeBlake3() {
    }

    /** Bytes of hasher state, for a direct {@link ByteBuffer} that holds it. */
    public static native int stateSize();

    public static native void init(ByteBuffer state);

    public static native void update(ByteBuffer state, byte[] data, int offset, int length);

    public static native void finish(ByteBuffer state, byte[] out);
}
