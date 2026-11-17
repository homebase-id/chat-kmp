package id.homebase.api.video

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.file.FileOperationsProvider
import kotlin.uuid.Uuid

/**
 * A received HLS video, downloaded and remuxed into an MP4 at [outputPath] (a file, or a `content://` entry [fileOps]
 * can write). False, logged, when the payload isn't there or isn't H.264/AAC in MPEG-TS; nothing is left at
 * [outputPath] then. A failed download, decrypt or write throws, as any download does.
 */
suspend fun DriveFileProvider.downloadHlsAsMp4(
    driveId: Uuid,
    fileId: Uuid,
    payloadKey: String,
    keyHeader: KeyHeader,
    fileOps: FileOperationsProvider,
    outputPath: String,
): Boolean {
    val label = "payload $payloadKey of file $fileId"
    var remuxed = false
    val found = streamPayloadDecrypted(driveId, fileId, payloadKey, keyHeader) { ts ->
        remuxed = VideoCompressionService.remuxTsToMp4(fileOps, ts, outputPath, label)
    }
    if (!found) Logger.e(tag = "HlsSave") { "download/decrypt failed: $label" }
    return found && remuxed
}
