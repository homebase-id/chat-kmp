package id.homebase.photobase.gallery

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.core.photobase.PhotobaseProtocol
import kotlin.uuid.Uuid

data class PhotoGalleryItem(
    val fileId: Uuid,
    val driveId: Uuid,
    val capturedAtMs: Long,
    val keyHeader: KeyHeader,
    val payload: PayloadDescriptor,
    /** An untouched original video's copy to play (UPLOADS.md section 3.6). */
    val playableCopy: PayloadDescriptor? = null,
    /** A Live or motion photo's clip to play (UPLOADS.md section 3.7). */
    val motion: PayloadDescriptor? = null,
) {
    /** What the gallery shows and plays. */
    val shown: PayloadDescriptor get() = playableCopy ?: payload
}

data class PhotoGalleryPage(val items: List<PhotoGalleryItem>, val nextCursor: String?)

fun interface PhotoGalleryPageSource {
    suspend fun page(cursor: String?, newestFirst: Boolean): PhotoGalleryPage
}

// A phone shows about 24 tiles, and the first page is what the first screen waits on.
internal fun galleryPageSize(cursor: String?) = if (cursor == null) 40 else 100

internal fun HomebaseFile.toGalleryItem(): PhotoGalleryItem? {
    val appData = fileMetadata.appData
    if (appData.fileType != PhotobaseProtocol.PhotoFileType) return null
    // A video's playlist is a payload of its own, and not one to show.
    val payloads = fileMetadata.payloads.orEmpty()
    val payload = payloads.firstOrNull { !it.isInternalDescriptor() && it.key != PhotobaseProtocol.LivePhotoVideoPayloadKey } ?: return null
    val live = payloads.firstOrNull { it.key == PhotobaseProtocol.LivePhotoVideoPayloadKey }
    return PhotoGalleryItem(
        fileId = fileId,
        driveId = driveId,
        capturedAtMs = appData.userDate ?: fileMetadata.created.milliseconds,
        keyHeader = keyHeader,
        payload = payload,
        playableCopy = payload.takeIf { it.isVideo() }?.playableCopyIn(payloads),
        motion = live?.playableIn(payloads),
    )
}
