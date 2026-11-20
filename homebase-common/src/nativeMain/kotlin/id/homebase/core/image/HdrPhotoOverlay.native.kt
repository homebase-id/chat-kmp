@file:OptIn(ExperimentalForeignApi::class)

package id.homebase.core.image

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import co.touchlab.kermit.Logger
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.ImageIO.CGImageSourceRef
import id.homebase.api.image.ImageIoDecode
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGImageGetContentHeadroom
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIImage
import platform.UIKit.UIImageDynamicRangeHigh
import platform.UIKit.UIImageView
import platform.UIKit.UIView
import platform.UIKit.UIViewContentMode
import kotlin.math.max
import kotlin.math.roundToInt

/** [capped] when the decode is smaller than the photo, so zooming past it would show less than the SDR tiles. */
internal class HdrPhoto(val image: UIImage, val width: Int, val height: Int, val headroom: Float, val capped: Boolean)

/** [bytes] decoded in HDR with a long edge of at most [maxLongEdge], or null when they have no gain map. */
internal fun decodeHdrPhoto(bytes: ByteArray, maxLongEdge: Int): HdrPhoto? =
    ImageIoDecode.open(bytes)?.let { decodeHdrPhoto(it, bytes, maxLongEdge) }

/** The file at [path], read in place; its header is enough to find an Ultra HDR gain map. */
internal fun decodeHdrPhoto(path: String, maxLongEdge: Int): HdrPhoto? {
    val source = ImageIoDecode.open(path) ?: return null
    val header = try {
        FileSystem.SYSTEM.read(path.toPath()) {
            request(HEADER_BYTES)
            readByteArray(minOf(buffer.size, HEADER_BYTES))
        }
    } catch (e: Exception) {
        CFRelease(source)
        throw e
    }
    return decodeHdrPhoto(source, header, maxLongEdge)
}

private fun decodeHdrPhoto(source: CGImageSourceRef, header: ByteArray, maxLongEdge: Int): HdrPhoto? {
    try {
        if (!ImageIoDecode.hasGainmap(source, header)) return null
        val photoEdge = ImageIoDecode.displayedSize(source)?.let { max(it.pixelWidth, it.pixelHeight) } ?: return null
        val image = ImageIoDecode.decodeHdr(source, maxLongEdge) ?: return null
        try {
            val headroom = CGImageGetContentHeadroom(image)
            val width = CGImageGetWidth(image).toInt()
            val height = CGImageGetHeight(image).toInt()
            return HdrPhoto(UIImage.imageWithCGImage(image), width, height, headroom, capped = max(width, height) < photoEdge)
        } finally {
            CGImageRelease(image)
        }
    } finally {
        CFRelease(source)
    }
}

// An Ultra HDR JPEG's MPF index sits after EXIF, XMP and ICC; 256 KB reaches past them, as the upload path's read does.
private const val HEADER_BYTES = 256L * 1024

@Composable
internal actual fun HdrPhotoOverlay(
    active: Boolean,
    encoded: suspend () -> ByteArray?,
    path: String?,
    displayedRect: () -> Rect,
    modifier: Modifier,
) {
    if (!active) return
    val window = LocalWindowInfo.current.containerSize
    val maxLongEdge = max(window.width, window.height)
    val photo by produceState<HdrPhoto?>(null, encoded, path, maxLongEdge) {
        if (maxLongEdge <= 0) return@produceState
        value = withContext(Dispatchers.Default) {
            try {
                if (path != null) decodeHdrPhoto(path, maxLongEdge) else encoded()?.let { decodeHdrPhoto(it, maxLongEdge) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(tag = "HdrPhotoOverlay", throwable = e) { "The photo couldn't be decoded in HDR; it shows SDR" }
                null
            }
        }
    }
    val hdr = photo ?: return
    var container by remember { mutableStateOf(IntSize.Zero) }
    val scale = LocalDensity.current.density.toDouble()
    Box(modifier.onSizeChanged { container = it }) {
        UIKitView(
            factory = {
                UIView().apply {
                    clipsToBounds = true
                    userInteractionEnabled = false
                    addSubview(UIImageView(hdr.image).apply {
                        preferredImageDynamicRange = UIImageDynamicRangeHigh
                        contentMode = UIViewContentMode.UIViewContentModeScaleToFill
                    })
                }
            },
            modifier = Modifier.layout { measurable, constraints ->
                val shown = displayedRect().intersect(Rect(0f, 0f, container.width.toFloat(), container.height.toFloat()))
                val placeable = measurable.measure(
                    Constraints.fixed(shown.width.roundToInt().coerceAtLeast(0), shown.height.roundToInt().coerceAtLeast(0)),
                )
                layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(shown.left.roundToInt(), shown.top.roundToInt()) }
            },
            update = { view ->
                val rect = displayedRect()
                // Zoomed past a decode smaller than the photo, the HDR copy would blur, so the SDR tiles show instead.
                view.hidden = hdr.capped && rect.width > hdr.width * 1.01f
                val left = rect.left.coerceAtLeast(0f).roundToInt()
                val top = rect.top.coerceAtLeast(0f).roundToInt()
                (view.subviews.firstOrNull() as? UIImageView)?.apply {
                    if (image !== hdr.image) setImage(hdr.image)
                    setFrame(CGRectMake((rect.left - left) / scale, (rect.top - top) / scale, rect.width / scale, rect.height / scale))
                }
            },
            properties = UIKitInteropProperties(interactionMode = null),
        )
    }
}
