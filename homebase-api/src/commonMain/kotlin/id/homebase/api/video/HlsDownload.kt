package id.homebase.api.video

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.file.AppCacheDirs
import id.homebase.api.file.FileOperationsProvider
import kotlin.uuid.Uuid

/**
 * A received HLS video as an MP4 in the media scratch folder, to save or share; null, logged, when its download or
 * remux fails. The caller deletes the MP4.
 */
suspend fun DriveFileProvider.downloadHlsAsMp4(
    driveId: Uuid,
    fileId: Uuid,
    payloadKey: String,
    keyHeader: KeyHeader,
    fileOps: FileOperationsProvider,
): String? {
    val dir = AppCacheDirs.scratchPath(fileOps.getCacheDirectory(), AppCacheDirs.MEDIA_WORK)
    val mp4Path = "$dir/hlsdl_${Uuid.random().toString().take(8)}.mp4"
    val label = "payload $payloadKey of file $fileId"
    var remuxed = false
    val found = streamPayloadDecrypted(driveId, fileId, payloadKey, keyHeader) { ts ->
        remuxed = VideoCompressionService.remuxTsToMp4(fileOps, ts, mp4Path, label)
    }
    if (!found) {
        Logger.e(tag = "HlsSave") { "download/decrypt failed: $label" }
        return null
    }
    if (!remuxed) {
        Logger.e(tag = "HlsSave") { "remux to MP4 failed: $label" }
        return null
    }
    return mp4Path
}
