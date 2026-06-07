package id.homebase.chat.services.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import co.touchlab.kermit.Logger
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentationResult
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import id.homebase.api.ActivityProvider
import kotlinx.coroutines.CancellationException
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

private fun segmenterOptions(): SubjectSegmenterOptions =
    SubjectSegmenterOptions.Builder()
        .enableForegroundBitmap()
        .build()

/**
 * Android implementation: ML Kit Subject Segmentation (on-device, model downloaded
 * via Google Play services — not bundled, so ~0 APK cost). The segmenter's
 * `foregroundBitmap` is already an alpha-cut ARGB bitmap; we PNG-encode it so the
 * transparency survives into the send pipeline.
 *
 * The model is an optional Play-services module that is NOT in the APK — first use has
 * to download it. We [ensureSegmentationModuleInstalled] (suspending until the download
 * finishes) BEFORE process(), so the first attempt waits for the model instead of
 * one-shot failing with "Waiting for the subject segmentation optional module to be
 * downloaded." [warmUpBackgroundRemoval] can pre-warm it earlier so that wait is
 * usually instant.
 *
 * Returns null (soft fail, caller keeps the original) when:
 *  - Google Play services isn't available,
 *  - the model can't be installed (download failed / declined),
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

    val segmenter = SubjectSegmentation.getClient(segmenterOptions())

    try {
        // The model isn't bundled — make sure Play services has downloaded it (waiting
        // on the download if this is the first use) before we hand it an image.
        if (!ensureSegmentationModuleInstalled(segmenter)) {
            Logger.w(tag = TAG) { "Android: segmentation model unavailable (install failed/declined)" }
            return@withContext null
        }

        val result = suspendCancellableCoroutine<SubjectSegmentationResult?> { cont ->
            segmenter.process(InputImage.fromBitmap(source, 0))
                .addOnSuccessListener { res -> if (cont.isActive) cont.resume(res) }
                .addOnFailureListener { e ->
                    Logger.w(tag = TAG) { "Android: subject segmentation failed: ${e.message}" }
                    if (cont.isActive) cont.resume(null)
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
    } catch (e: CancellationException) {
        // Editor closed / coroutine cancelled mid-segmentation — let structured
        // concurrency tear this down; never swallow it as a soft "failed" result.
        throw e
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
 * Best-effort proactive download: when an image editor opens, ask Play services to
 * *deferred-install* the Subject Segmentation module (it fetches when the device is
 * idle and on unmetered Wi-Fi — no UI, no guarantee it finishes before the user taps).
 * The on-tap path still installs-and-waits, so this only shortens the common case.
 * Fire-and-forget and idempotent; never throws.
 */
actual fun warmUpBackgroundRemoval() {
    if (!isBackgroundRemovalSupported()) return
    runCatching {
        val context = ActivityProvider.requireApplicationContext()
        val segmenter = SubjectSegmentation.getClient(segmenterOptions())
        ModuleInstall.getClient(context)
            .deferredInstall(segmenter)
            .addOnFailureListener { e ->
                Logger.d(tag = TAG) { "Android: deferred segmentation install not scheduled: ${e.message}" }
            }
        // The deferred request is already enqueued with Play services; the local handle
        // isn't needed past scheduling, so release its native pipeline.
        segmenter.close()
    }.onFailure {
        Logger.d(tag = TAG) { "Android: warmUpBackgroundRemoval skipped: ${it.message}" }
    }
}

/**
 * Make sure the ML Kit Subject Segmentation optional module is installed, downloading
 * it (and suspending until it finishes) if necessary. Returns true once the model is
 * present, false if Play services can't provide it.
 *
 * The model rides on Play services and is NOT in the APK; without this gate the first
 * process() call fails with "...optional module to be downloaded. Please wait." We turn
 * that one-shot failure into a wait (the editor shows a spinner meanwhile).
 *
 * Key subtlety: the Task from installModules() resolves when the request is ACCEPTED,
 * not when the download FINISHES — so completion is observed via the
 * [InstallStatusListener] reaching a terminal state. [invokeOnCancellation] unregisters
 * the listener so a coroutine cancelled mid-download (editor closed) doesn't leak it.
 */
private suspend fun ensureSegmentationModuleInstalled(segmenter: SubjectSegmenter): Boolean {
    val context = ActivityProvider.requireApplicationContext()
    val client = ModuleInstall.getClient(context)

    // Fast path: already installed (every call after the first) — skip the install request.
    val alreadyInstalled = suspendCancellableCoroutine<Boolean> { cont ->
        client.areModulesAvailable(segmenter)
            .addOnSuccessListener { response -> if (cont.isActive) cont.resume(response.areModulesAvailable()) }
            .addOnFailureListener { e ->
                Logger.w(tag = TAG) { "Android: areModulesAvailable failed: ${e.message}" }
                if (cont.isActive) cont.resume(false)
            }
    }
    if (alreadyInstalled) return true

    return suspendCancellableCoroutine { cont ->
        lateinit var listener: InstallStatusListener
        listener = InstallStatusListener { update ->
            when (update.installState) {
                ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED -> {
                    client.unregisterListener(listener)
                    if (cont.isActive) cont.resume(true)
                }

                ModuleInstallStatusUpdate.InstallState.STATE_FAILED,
                ModuleInstallStatusUpdate.InstallState.STATE_CANCELED -> {
                    client.unregisterListener(listener)
                    if (cont.isActive) cont.resume(false)
                }

                else -> Unit // PENDING / DOWNLOADING / DOWNLOADED / INSTALLING — keep waiting.
            }
        }

        val request = ModuleInstallRequest.newBuilder()
            .addApi(segmenter)
            .setListener(listener)
            .build()

        client.installModules(request)
            .addOnSuccessListener { response ->
                // Rare race: it became installed between the availability check and now.
                // No status updates will fire in that case, so resolve from the response.
                if (response.areModulesAlreadyInstalled() && cont.isActive) {
                    client.unregisterListener(listener)
                    cont.resume(true)
                }
            }
            .addOnFailureListener { e ->
                Logger.w(tag = TAG) { "Android: module install request failed: ${e.message}" }
                client.unregisterListener(listener)
                if (cont.isActive) cont.resume(false)
            }

        cont.invokeOnCancellation { client.unregisterListener(listener) }
    }
}

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
