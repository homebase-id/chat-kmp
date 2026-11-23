@file:OptIn(ExperimentalForeignApi::class)

package id.homebase.core.scan

import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.create
import platform.Vision.VNImageRequestHandler
import platform.Vision.VNRecognizeTextRequest
import platform.Vision.VNRecognizedText
import platform.Vision.VNRecognizedTextObservation
import platform.Vision.VNRequestTextRecognitionLevelAccurate

private const val TAG = "CardTextRecognizer"

actual suspend fun recognizeCardText(jpeg: ByteArray): String? = withContext(Dispatchers.Default) {
    val data = jpeg.usePinned { NSData.create(bytes = it.addressOf(0), length = jpeg.size.toULong()) }
    val request = VNRecognizeTextRequest().apply {
        recognitionLevel = VNRequestTextRecognitionLevelAccurate
        usesLanguageCorrection = false
    }
    val handler = VNImageRequestHandler(data = data, options = emptyMap<Any?, Any?>())
    val performed = memScoped {
        val error = alloc<kotlinx.cinterop.ObjCObjectVar<NSError?>>()
        handler.performRequests(listOf(request), error.ptr).also {
            if (!it) Logger.w(TAG) { "text recognition failed: code ${error.value?.code}" }
        }
    }
    if (!performed) return@withContext null
    request.results.orEmpty()
        .filterIsInstance<VNRecognizedTextObservation>()
        .mapNotNull { it.topCandidates(1u).firstOrNull() }
        .joinToString("\n") { (it as VNRecognizedText).string }
}
