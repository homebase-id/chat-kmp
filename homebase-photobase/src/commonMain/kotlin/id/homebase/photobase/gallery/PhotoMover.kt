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
import id.homebase.api.client.drives.files.TombstoneOptions
import id.homebase.core.photobase.PhotobaseProtocol
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * [done] photos went where they were sent; [changedElsewhere] another device had moved or deleted first; [failed] the
 * server refused, or a request after the first didn't get through. [written] are the photos this call moved itself.
 */
data class PhotoMoveResult(val done: Int, val changedElsewhere: Int = 0, val failed: Int = 0, val written: Set<Uuid> = emptySet())

/** The server's side of the shelves, answered per file. */
interface PhotoMoveServer {
    suspend fun setArchivalStatus(status: ArchivalStatus, files: List<FileVersionTag>): List<PerFileResult>

    suspend fun softDelete(files: List<FileVersionTag>): List<PerFileResult>

    /** Adds or removes [tag] in each file's local tags, on top of the ones it has. */
    suspend fun setLocalTag(tag: Uuid, on: Boolean, fileIds: List<Uuid>): List<PerFileResult>

    /** Null when the server hasn't the file. */
    suspend fun header(fileId: Uuid): HomebaseFile?
}

class HttpPhotoMoveServer(
    private val files: DriveFileHttpProvider,
    private val headers: DriveFileProvider,
    private val driveId: Uuid = PhotobaseProtocol.DriveAlias,
) : PhotoMoveServer {
    override suspend fun setArchivalStatus(status: ArchivalStatus, files: List<FileVersionTag>) =
        this.files.updateArchivalStatus(driveId, status, files).results

    override suspend fun softDelete(files: List<FileVersionTag>) =
        this.files.softDeleteFiles(driveId, files, TombstoneOptions(keepUniqueId = true)).results

    override suspend fun setLocalTag(tag: Uuid, on: Boolean, fileIds: List<Uuid>) =
        if (on) this.files.updateLocalTags(driveId, fileIds, add = listOf(tag)).results else this.files.updateLocalTags(driveId, fileIds, remove = listOf(tag)).results

    override suspend fun header(fileId: Uuid): HomebaseFile? = headers.getFileHeader(driveId, fileId)
}

/**
 * Moves photos between shelves, and deletes them from the bin for good. Each write names the version the device last
 * synced; a photo another device wrote since is read again, and written once more only if it is still where this
 * device saw it, so another device's move or restore stands.
 */
class PhotoMover(
    private val server: PhotoMoveServer,
    /** The device's copy of the photos with these ids, on any shelf. */
    private val filesOf: suspend (Collection<Uuid>) -> List<HomebaseFile>,
    /** The device's copy of every photo in the bin. */
    private val binned: suspend () -> List<HomebaseFile>,
    /** Asks the drive to sync, so the device's copy catches up with the writes. */
    private val sync: () -> Unit,
) {
    suspend fun move(fileIds: Collection<Uuid>, from: PhotoShelf, to: PhotoShelf): PhotoMoveResult =
        write(fileIds, filesOf(fileIds), from.status, to.status)

    suspend fun deleteForGood(fileIds: Collection<Uuid>): PhotoMoveResult =
        write(fileIds, filesOf(fileIds), from = ArchivalStatus.Removed, to = null)

    /**
     * Hearts [fileIds], or takes the heart away, wherever each is. No version is checked: a heart is the same whoever set
     * it, and another device's heart on the same photo stands beside this one.
     */
    suspend fun setFavorite(fileIds: Collection<Uuid>, favorite: Boolean): PhotoMoveResult {
        val tally = Tally(to = null)
        val ids = fileIds.distinct()
        var answered = 0
        try {
            for (batch in ids.chunked(DriveFileHttpProvider.FILE_BATCH)) {
                val answers = try {
                    server.setLocalTag(PhotobaseProtocol.FavoriteTag, favorite, batch)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (answered == 0) throw e
                    Logger.w(tag = TAG, throwable = e) { "${ids.size - answered} photos not sent" }
                    tally.failed += ids.size - answered
                    break
                }
                answered += answers.size
                for (answer in answers) {
                    when (answer.errorCode) {
                        null -> {
                            tally.done++
                            tally.written += answer.fileId
                        }
                        OdinClientErrorCode.FileNotFound -> tally.changedElsewhere++
                        else -> {
                            tally.failed++
                            Logger.w(tag = TAG) { "photo ${answer.fileId} refused: ${answer.errorCode}" }
                        }
                    }
                }
            }
        } finally {
            if (answered > 0) sync()
        }
        return tally.result().also {
            Logger.i(tag = TAG) { "${if (favorite) "favorite" else "not favorite"}: ${it.done} done, ${it.changedElsewhere} changed elsewhere, ${it.failed} failed" }
        }
    }

    /** Deletes for good what the bin shows, not what reached the server since. */
    suspend fun empty(): PhotoMoveResult = binned().let { files -> write(files.map { it.fileId }, files, ArchivalStatus.Removed, to = null) }

    /** [to] null deletes for good. */
    private suspend fun write(fileIds: Collection<Uuid>, files: List<HomebaseFile>, from: ArchivalStatus, to: ArchivalStatus?): PhotoMoveResult {
        val tally = Tally(to)
        val held = files.associateBy { it.fileId }
        val ready = mutableListOf<FileVersionTag>()
        // Not held (gone, or a header that won't parse), or maybe this phone's own last write not synced back yet:
        // the server says where it is.
        val unsure = mutableListOf<Uuid>()
        for (fileId in fileIds.distinct()) {
            val file = held[fileId]
            val versionTag = file?.fileMetadata?.versionTag
            when {
                file != null && file.fileState != FileState.Active -> tally.gone(1)
                file != null && versionTag != null && file.isAt(from) -> ready += FileVersionTag(fileId, versionTag)
                else -> unsure += fileId
            }
        }
        ready += reconsider(unsure, from, tally)
        if (ready.isEmpty()) return tally.result()
        try {
            val again = answer(send(ready, tally), from, tally)
            if (again.isNotEmpty()) answer(send(again, tally), from, tally, retry = false)
        } finally {
            sync()
        }
        return tally.result().also {
            Logger.i(tag = TAG) { "${to ?: "deleted for good"}: ${it.done} done, ${it.changedElsewhere} changed elsewhere, ${it.failed} failed" }
        }
    }

    /** Sends [files] a batch at a time; one that fails after others went through fails the rest, not the call. */
    private suspend fun send(files: List<FileVersionTag>, tally: Tally): List<PerFileResult> {
        val answers = mutableListOf<PerFileResult>()
        for (batch in files.chunked(DriveFileHttpProvider.FILE_BATCH)) {
            try {
                answers += tally.to?.let { server.setArchivalStatus(it, batch) } ?: server.softDelete(batch)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (answers.isEmpty() && tally.written.isEmpty()) throw e
                Logger.w(tag = TAG, throwable = e) { "${files.size - answers.size} photos not sent" }
                tally.failed += files.size - answers.size
                break
            }
        }
        return answers
    }

    /** Tallies [answers]; returns the files to send again at the version the server has now. */
    private suspend fun answer(answers: List<PerFileResult>, from: ArchivalStatus, tally: Tally, retry: Boolean = true): List<FileVersionTag> {
        val mismatched = mutableListOf<Uuid>()
        for (answer in answers) {
            when (answer.errorCode) {
                null -> {
                    tally.done++
                    tally.written += answer.fileId
                }
                OdinClientErrorCode.FileNotFound, OdinClientErrorCode.CannotUpdateNonActiveFile -> tally.gone(1)
                OdinClientErrorCode.VersionTagMismatch -> if (retry) mismatched += answer.fileId else tally.changedElsewhere++
                else -> {
                    tally.failed++
                    Logger.w(tag = TAG) { "photo ${answer.fileId} refused: ${answer.errorCode}" }
                }
            }
        }
        return reconsider(mismatched, from, tally)
    }

    /**
     * Tallies each photo by where the server has it; returns those still at [from], at the version it has now. A
     * lookup that fails once something was written counts as failed, so the call doesn't throw away what went.
     */
    private suspend fun reconsider(fileIds: List<Uuid>, from: ArchivalStatus, tally: Tally): List<FileVersionTag> {
        if (fileIds.isEmpty()) return emptyList()
        val permits = Semaphore(HEADER_READS)
        val headers = coroutineScope {
            fileIds.map { fileId -> async { permits.withPermit { lookUp(fileId) } } }.awaitAll()
        }
        val still = mutableListOf<FileVersionTag>()
        for ((fileId, header) in fileIds.zip(headers)) {
            val now = header.getOrElse { e ->
                if (tally.written.isEmpty()) throw e
                Logger.w(tag = TAG, throwable = e) { "photo $fileId not looked up" }
                tally.failed++
                continue
            }
            val versionTag = now?.fileMetadata?.versionTag
            when {
                now == null || now.fileState != FileState.Active -> tally.gone(1)
                tally.to != null && now.isAt(tally.to) -> tally.done++
                versionTag != null && now.isAt(from) -> still += FileVersionTag(fileId, versionTag)
                else -> tally.changedElsewhere++
            }
        }
        return still
    }

    private suspend fun lookUp(fileId: Uuid): Result<HomebaseFile?> = try {
        Result.success(server.header(fileId))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private class Tally(val to: ArchivalStatus?) {
        var done = 0
        var changedElsewhere = 0
        var failed = 0
        val written = mutableSetOf<Uuid>()

        // Gone is what deleting for good wanted; for a move, another device deleted it first.
        fun gone(count: Int) {
            if (to == null) done += count else changedElsewhere += count
        }

        fun result() = PhotoMoveResult(done, changedElsewhere, failed, written.toSet())
    }

    private companion object {
        const val TAG = "PhotoMover"
        const val HEADER_READS = 8
    }
}
