package id.homebase.imageresize;

import android.graphics.Bitmap;

/** Lanczos-3 resampling between two bitmaps' native pixels, with no copy onto the Java heap. */
public final class NativeResize {
    static {
        System.loadLibrary("homebase_imageresize");
    }

    private NativeResize() {
    }

    /**
     * Fills {@code destination} from all of {@code source}. Both must be premultiplied ARGB_8888;
     * the filter's reach grows with the reduction, so nothing aliases at any ratio.
     *
     * @throws IllegalStateException if either bitmap is the wrong kind or the resize fails
     */
    public static native void lanczos(Bitmap source, Bitmap destination);
}
