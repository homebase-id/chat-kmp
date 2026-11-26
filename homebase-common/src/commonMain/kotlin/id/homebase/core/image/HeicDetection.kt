package id.homebase.core.image

import coil3.fetch.SourceFetchResult
import id.homebase.api.lib.image.ImageFormatDetector

/**
 * Shared HEIC detection for Coil3 [SourceFetchResult]s.
 * Checks mimeType first (fast), then peeks at the ISOBMFF ftyp header bytes.
 */
fun isHeicSource(result: SourceFetchResult): Boolean {
    if (result.mimeType == "image/heic" || result.mimeType == "image/heif") {
        return true
    }
    val header = result.peekHeader(12) ?: return false
    return header.size == 12 && ImageFormatDetector.isHeic(header)
}
