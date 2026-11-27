package id.homebase.api.video

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.payload.PayloadLocation
import id.homebase.api.client.payload.PayloadReader
import id.homebase.api.client.drives.files.DescriptorContent
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.file.CONTENT_TYPE_MP4
import id.homebase.api.util.safeFileName
import id.homebase.api.util.splitExtension

/**
 * A received HLS video, downloaded and remuxed into an MP4 at [outputPath] (a file, or a `content://` entry the reader's
 * file operations can write). False, logged, when the payload isn't there or isn't H.264/AAC in MPEG-TS; a file at
 * [outputPath] is then deleted, a `content://` entry is the caller's to discard. A failed download, decrypt or write throws.
 */
suspend fun PayloadReader.downloadHlsAsMp4(
    at: PayloadLocation,
    payloadKey: String,
    keyHeader: KeyHeader,
    outputPath: String,
    cache: Boolean,
): Boolean {
    val label = "payload $payloadKey at $at"
    var remuxed = false
    val found = stream(at, payloadKey, keyHeader, cache) { ts ->
        remuxed = VideoCompressionService.remuxTsToMp4(fileOps, ts, outputPath, label)
    }
    if (!found) Logger.e(tag = "HlsSave") { "download/decrypt failed: $label" }
    return found && remuxed
}

/** An HLS video's payload is an MPEG-TS stream, not a file people can open: it leaves the app through [downloadHlsAsMp4]. */
val PayloadDescriptor.isHlsVideo: Boolean
    get() = (descriptorInfo() as? DescriptorContent.VideoFile)?.isSegmented == true

/** The type of the file a person gets for this payload: an MP4 for an HLS video. */
val PayloadDescriptor.playableContentType: String?
    get() = if (isHlsVideo) CONTENT_TYPE_MP4 else contentType

/** [name] for the file a person gets for this payload: an HLS video's is an `.mp4`. */
fun PayloadDescriptor.playableFileName(name: String?): String? =
    if (isHlsVideo) safeFileName(name?.let { splitExtension(it).first }, "video", "mp4") else name

/**
 * This payload decrypted to [outputPath] as a file people can open: an HLS video remuxed into an MP4
 * ([downloadHlsAsMp4]), anything else as it is. False when it isn't there or can't be remuxed. [cache] as for
 * [PayloadReader.toFile].
 */
suspend fun PayloadReader.writePlayable(
    at: PayloadLocation,
    payload: PayloadDescriptor,
    keyHeader: KeyHeader,
    outputPath: String,
    cache: Boolean,
): Boolean = if (payload.isHlsVideo) {
    downloadHlsAsMp4(at, payload.key, keyHeader, outputPath, cache)
} else {
    toFile(at, payload.key, keyHeader, outputPath, cache)
}
