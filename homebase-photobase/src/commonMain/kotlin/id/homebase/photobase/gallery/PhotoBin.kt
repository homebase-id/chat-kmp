package id.homebase.photobase.gallery

import co.touchlab.kermit.Logger
import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.ArchivalStatus
import id.homebase.api.client.drives.files.DriveFileHttpProvider
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.files.FileVersionTag
import id.homebase.api.client.drives.files.PerFileResult
import id.homebase.core.photobase.PhotobaseProtocol
import kotlin.uuid.Uuid

/** [done] photos went where they were sent; [changedElsewhere] another device had moved or deleted first; [failed] the server refused. */
data class PhotoBinResult(val done: Int, val changedElsewhere: Int = 0, val failed: Int = 0)

/** The server's side of the bin, answered per file. */
interface PhotoBinServer {
    suspend fun setArchivalStatus(status: ArchivalStatus, files: List<FileVersionTag>): List<PerFileResult>

    suspend fun softDelete(files: List<FileVersionTag>): List<PerFileResult>

    /** Null when the server hasn't the file. */
    suspend fun header(fileId: Uuid): HomebaseFile?
}

class HttpPhotoBinServer(
    private val files: DriveFileHttpProvider,
    private val headers: DriveFileProvider,
    private val driveId: Uuid = PhotobaseProtocol.DriveAlias,
) : PhotoBinServer {
    override suspend fun setArchivalStatus(status: ArchivalStatus, files: List<FileVersionTag>) =
        this.files.updateArchivalStatus(driveId, status, files).results

    override suspend fun softDelete(files: List<FileVersionTag>) = this.files.softDeleteFiles(driveId, files).results

    override suspend fun header(fileId: Uuid): HomebaseFile? = headers.getFileHeader(driveId, fileId)
}

/**
 * Moves photos to the bin and back, and deletes them from it for good. Each write names the version the device last
 * synced; a photo another device wrote since is read again, and written once more only if it is still where this
 * device saw it, so another device's move or restore stands.
 */
class PhotoBin(
    private val server: PhotoBinServer,
    /** The device's copy of the photos with these ids, on any shelf. */
    private val filesOf: suspend (Collection<Uuid>) -> List<HomebaseFile>,
    /** The device's copy of every photo in the bin. */
    private val binned: suspend () -> List<HomebaseFile>,
    /** Asks the drive to sync, so the device's copy catches up with the writes. */
    private val sync: () -> Unit,
) {
    suspend fun moveToBin(fileIds: Collection<Uuid>): PhotoBinResult =
        write(fileIds, filesOf(fileIds), from = ArchivalStatus.None, to = ArchivalStatus.Removed)

    suspend fun restore(fileIds: Collection<Uuid>): PhotoBinResult =
        write(fileIds, filesOf(fileIds), from = ArchivalStatus.Removed, to = ArchivalStatus.None)

    suspend fun deleteForGood(fileIds: Collection<Uuid>): PhotoBinResult =
        write(fileIds, filesOf(fileIds), from = ArchivalStatus.Removed, to = null)

    /** Deletes for good what the bin shows, not what reached the server since. */
    suspend fun empty(): PhotoBinResult = binned().let { files -> write(files.map { it.fileId }, files, ArchivalStatus.Removed, to = null) }

    /** [to] null deletes for good. */
    private suspend fun write(fileIds: Collection<Uuid>, files: List<HomebaseFile>, from: ArchivalStatus, to: ArchivalStatus?): PhotoBinResult {
        val tally = Tally(to)
        val held = files.distinctBy { it.fileId }
        // One the device no longer holds was deleted by another device.
        tally.gone(fileIds.size - held.size)
        val ready = mutableListOf<FileVersionTag>()
        for (file in held) {
            val versionTag = file.fileMetadata.versionTag
            when {
                to != null && file.isAt(to) -> tally.done++
                versionTag == null || !file.isAt(from) -> tally.changedElsewhere++
                else -> ready += FileVersionTag(file.fileId, versionTag)
            }
        }
        if (ready.isEmpty()) return tally.result()
        try {
            val again = answer(send(ready, to), from, tally)
            if (again.isNotEmpty()) answer(send(again, to), from, tally, retry = false)
        } finally {
            sync()
        }
        return tally.result().also { Logger.i(tag = TAG) { "${to ?: "deleted for good"}: $it" } }
    }

    private suspend fun send(files: List<FileVersionTag>, to: ArchivalStatus?): List<PerFileResult> =
        if (to == null) server.softDelete(files) else server.setArchivalStatus(to, files)

    /** Tallies [answers]; returns the files to send again at the version the server has now. */
    private suspend fun answer(answers: List<PerFileResult>, from: ArchivalStatus, tally: Tally, retry: Boolean = true): List<FileVersionTag> {
        val again = mutableListOf<FileVersionTag>()
        for (answer in answers) {
            when (answer.errorCode) {
                null -> tally.done++
                OdinClientErrorCode.FileNotFound, OdinClientErrorCode.CannotUpdateNonActiveFile -> tally.gone(1)
                OdinClientErrorCode.VersionTagMismatch -> {
                    if (!retry) {
                        tally.changedElsewhere++
                        continue
                    }
                    val now = server.header(answer.fileId)
                    val versionTag = now?.fileMetadata?.versionTag
                    when {
                        now == null || now.fileState != FileState.Active -> tally.gone(1)
                        tally.to != null && now.isAt(tally.to) -> tally.done++
                        versionTag != null && now.isAt(from) -> again += FileVersionTag(answer.fileId, versionTag)
                        else -> tally.changedElsewhere++
                    }
                }
                else -> {
                    tally.failed++
                    Logger.w(tag = TAG) { "photo ${answer.fileId} refused: ${answer.errorCode}" }
                }
            }
        }
        return again
    }

    private class Tally(val to: ArchivalStatus?) {
        var done = 0
        var changedElsewhere = 0
        var failed = 0

        // Gone is what deleting for good wanted; for a move, another device deleted it first.
        fun gone(count: Int) {
            if (to == null) done += count else changedElsewhere += count
        }

        fun result() = PhotoBinResult(done, changedElsewhere, failed)
    }

    private companion object {
        const val TAG = "PhotoBin"
    }
}
