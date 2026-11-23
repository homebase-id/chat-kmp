package id.homebase.core.scan

import android.graphics.BitmapFactory
import co.touchlab.kermit.Logger
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import id.homebase.api.ActivityProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

private const val TAG = "CardTextRecognizer"

actual suspend fun recognizeCardText(jpeg: ByteArray): String? = withContext(Dispatchers.Default) {
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    try {
        val client = ModuleInstall.getClient(ActivityProvider.requireApplicationContext())
        val installed = suspendCancellableCoroutine<Boolean> { cont ->
            client.areModulesAvailable(recognizer)
                .addOnSuccessListener { if (cont.isActive) cont.resume(it.areModulesAvailable()) }
                .addOnFailureListener { if (cont.isActive) cont.resume(false) }
        }
        if (!installed) {
            // Play services downloads the model in the background; this scan is labelled a plain card.
            client.deferredInstall(recognizer)
            return@withContext null
        }
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return@withContext null
        suspendCancellableCoroutine<String?> { cont ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { if (cont.isActive) cont.resume(it.text) }
                .addOnFailureListener { e ->
                    Logger.w(TAG) { "text recognition failed: ${e::class.simpleName}" }
                    if (cont.isActive) cont.resume(null)
                }
        }.also { bitmap.recycle() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.w(TAG) { "text recognition unavailable: ${e::class.simpleName}" }
        null
    } finally {
        recognizer.close()
    }
}
