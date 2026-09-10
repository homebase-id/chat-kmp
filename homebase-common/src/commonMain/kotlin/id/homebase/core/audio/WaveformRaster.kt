package id.homebase.core.audio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.ThumbnailDescriptor
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.image.HomebaseImageLoader
import id.homebase.core.image.ImageSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.koin.compose.koinInject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.abs
import kotlin.uuid.Uuid

private const val TAG = "WaveformRaster"

// The lossy WebP re-encode of the uploaded raster blurs the bar edges, so a bare
// "any alpha at all" test overstates every bar by a pixel or two.
private const val ALPHA_THRESHOLD = 0.35f
private const val MAX_CACHED_WAVEFORMS = 200

fun ImageBitmap.toWaveformAmplitudes(
    barCount: Int = AudioWaveFormGenerator.BAR_COUNT
): FloatArray {
    if (barCount < 1 || width < 4 || height < 4) return FloatArray(0)

    val strokeWidth = (width.toFloat() / barCount) * 0.8f
    val span = height - strokeWidth
    if (span <= 0f) return FloatArray(0)

    val pixels = toPixelMap()
    val centre = height / 2f
    val out = FloatArray(barCount)
    var any = false

    for (index in 0 until barCount) {
        val from = (index * width) / barCount
        val to = (((index + 1) * width) / barCount).coerceAtMost(width)
        var furthest = 0f
        for (x in from until to) {
            for (y in 0 until height) {
                if (pixels[x, y].alpha <= ALPHA_THRESHOLD) continue
                val distance = abs((y + 0.5f) - centre) + 0.5f
                if (distance > furthest) furthest = distance
            }
        }
        if (furthest > 0f) {
            val amplitude = ((2f * furthest) - strokeWidth) / span
            out[index] = amplitude.coerceIn(0f, 1f)
            if (out[index] > 0f) any = true
        }
    }

    return if (any) out else FloatArray(0)
}

private val waveformCache = LinkedHashMap<String, FloatArray>()

// Read during composition so a bubble scrolling back into view has its bars on the first
// frame; a suspending read costs a frame of placeholder and restarts the grow-in animation.
private val waveformCacheLock = SynchronizedObject()

private fun cachedWaveform(key: String): FloatArray? =
    synchronized(waveformCacheLock) { waveformCache[key] }

private fun storeWaveform(key: String, value: FloatArray) {
    synchronized(waveformCacheLock) {
        waveformCache.remove(key)
        waveformCache[key] = value
        while (waveformCache.size > MAX_CACHED_WAVEFORMS) {
            val eldest = waveformCache.keys.firstOrNull() ?: break
            waveformCache.remove(eldest)
        }
    }
}

@OptIn(ExperimentalEncodingApi::class)
@Composable
fun rememberWaveformAmplitudes(
    driveId: Uuid,
    fileId: Uuid,
    payload: PayloadDescriptor,
    thumbnail: ThumbnailDescriptor,
    keyHeader: KeyHeader,
): FloatArray? {
    val imageLoader: HomebaseImageLoader = koinInject()
    val cacheKey = remember(fileId, payload.key) { "$fileId-${payload.key}" }
    var amplitudes by remember(cacheKey) { mutableStateOf(cachedWaveform(cacheKey)) }

    LaunchedEffect(cacheKey, thumbnail.pixelWidth, thumbnail.pixelHeight) {
        if (amplitudes != null) return@LaunchedEffect

        val decoded = withContext(Dispatchers.Default) {
            runCatching {
                val payloadIv = payload.iv?.let { Base64.decode(it) }
                val requestedSize = ImageSize(
                    pixelWidth = thumbnail.pixelWidth ?: 320,
                    pixelHeight = thumbnail.pixelHeight ?: 64,
                )
                val data = HomebaseImageData(
                    driveId = driveId,
                    fileId = fileId,
                    payloadKey = payload.key,
                    previewThumbnail = thumbnail.toEmbeddedThumb(),
                    requestedSize = requestedSize,
                    keyHeader = if (payloadIv != null) {
                        KeyHeader(iv = payloadIv, aesKey = keyHeader.aesKey)
                    } else {
                        keyHeader
                    },
                    lastModified = payload.lastModified,
                )
                val bytes = imageLoader.loadThumbnail(data, requestedSize)?.bytes
                bytes?.decodeToImageBitmap()?.toWaveformAmplitudes()?.takeIf { it.isNotEmpty() }
            }.onFailure {
                Logger.d(tag = TAG, throwable = it) { "Waveform decode failed" }
            }.getOrNull()
        }

        if (decoded != null) storeWaveform(cacheKey, decoded)
        amplitudes = decoded
    }

    return amplitudes
}
