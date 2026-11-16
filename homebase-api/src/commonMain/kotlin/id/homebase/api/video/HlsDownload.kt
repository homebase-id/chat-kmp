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
    val uid = Uuid.random().toString().take(8)
    val tsPath = "$dir/input_hlsdl_$uid.ts"
    val mp4Path = "$dir/hlsdl_$uid.mp4"
    try {
        if (!streamPayloadDecryptedToPath(driveId, fileId, payloadKey, keyHeader, tsPath, fileOps)) {
            Logger.e(tag = "HlsSave") { "download/decrypt failed: payload $payloadKey of file $fileId" }
            return null
        }
        if (!VideoCompressionService.remuxTsToMp4(fileOps, tsPath, mp4Path)) {
            Logger.e(tag = "HlsSave") { "remux to MP4 failed: payload $payloadKey of file $fileId" }
            return null
        }
        return mp4Path
    } finally {
        runCatching { fileOps.deleteTempFile(tsPath) }
    }
}
