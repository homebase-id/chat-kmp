package id.homebase.chat.services.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import co.touchlab.kermit.Logger
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentationResult
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import id.homebase.api.ActivityProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

private const val TAG = "BackgroundRemover"

/**
 * Cap (px, longest edge) on the bitmap fed to ML Kit. A 12-MP frame would otherwise
 * decode into a ~48 MB ARGB bitmap which, alongside ML Kit's own foreground bitmap,
 * risks OOM on low-RAM devices; 2048px still yields a high-quality mask for a <=512px
 * sticker.
 */
private const val MAX_SEGMENT_INPUT_DIM = 2048

/**
 * Android implementation: ML Kit Subject Segmentation (on-device, model downloaded
 * via Google Play services — not bundled, so ~0 APK cost). The segmenter's
 * `foregroundBitmap` is already an alpha-cut ARGB bitmap; we PNG-encode it so the
 * transparency survives into the send pipeline.
 *
 * Returns null (soft fail, caller keeps the original) when:
 *  - Google Play services / the segmentation model isn't available,
 *  - the model hasn't been downloaded yet (process() fails),
 *  - the source can't be decoded,
 *  - or no foreground subject was detected.
 */
actual suspend fun removeBackground(srcBytes: ByteArray): ByteArray? = withContext(Dispatchers.Default) {
    if (!isBackgroundRemovalSupported()) return@withContext null

    val source = runCatching {
        decodeBoundedBitmap(srcBytes, MAX_SEGMENT_INPUT_DIM)
    }.getOrNull()
    if (source == null) {
        Logger.w(tag = TAG) { "Android: could not decode source bytes for background removal" }
        return@withContext null
    }

    val options = SubjectSegmenterOptions.Builder()
        .enableForegroundBitmap()
        .build()
    val segmenter = SubjectSegmentation.getClient(options)

    try {
        val result = suspendCancellableCoroutine<SubjectSegmentationResult?> { cont ->
            segmenter.process(InputImage.fromBitmap(source, 0))
                .addOnSuccessListener { res -> cont.resume(res) }
                .addOnFailureListener { e ->
                    // The common path here is "model not downloaded yet" (the model
                    // downloads in the background on first use). Treat as soft-null.
                    Logger.w(tag = TAG) { "Android: subject segmentation failed: ${e.message}" }
                    cont.resume(null)
                }
        } ?: return@withContext null

        val foreground: Bitmap = result.foregroundBitmap ?: run {
            Logger.d(tag = TAG) { "Android: no foreground subject found" }
            return@withContext null
        }

        ByteArrayOutputStream().use { out ->
            foreground.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    } catch (e: Exception) {
        Logger.w(tag = TAG) { "Android: background removal threw: ${e.message}" }
        null
    } finally {
        segmenter.close()
    }
}

/**
 * Supported when Google Play services is present — the Subject Segmentation model
 * rides on GMS and is unavailable on GMS-less devices. We don't probe model
 * download state here (that would require a network/IPC round-trip); a missing
 * model simply surfaces later as a null from [removeBackground].
 */
actual fun isBackgroundRemovalSupported(): Boolean = runCatching {
    val context = ActivityProvider.requireApplicationContext()
    GoogleApiAvailability.getInstance()
        .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
}.getOrDefault(false)

/**
 * Decode [srcBytes] with an `inSampleSize` cap so the segmenter input stays within
 * [maxDim] on its longest edge. `inSampleSize` only powers-of-two downsamples at decode
 * time and does NOT apply EXIF rotation — matching the previous raw `decodeByteArray`,
 * so the cut-out's orientation is unchanged. ARGB_8888 preserves the alpha channel ML
 * Kit fills into the foreground bitmap.
 */
private fun decodeBoundedBitmap(srcBytes: ByteArray, maxDim: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(srcBytes, 0, srcBytes.size, bounds)
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    var sample = 1
    while (longest > 0 && longest / sample > maxDim) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeByteArray(srcBytes, 0, srcBytes.size, options)
}
