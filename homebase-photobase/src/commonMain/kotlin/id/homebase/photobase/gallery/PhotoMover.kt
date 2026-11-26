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
import id.homebase.api.client.ClientException
import id.homebase.api.client.drives.upload.DriveUploadProvider
import id.homebase.api.client.drives.upload.headerUpdate
import id.homebase.api.client.drives.upload.withTag
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

    /** Adds or removes [tag] in each file's app tags, which a guest's query can see; done for a file already as asked. */
    suspend fun setAppTag(tag: Uuid, on: Boolean, fileIds: List<Uuid>): List<PerFileResult>

    /** Null when the server hasn't the file. */
    suspend fun header(fileId: Uuid): HomebaseFile?
}

class HttpPhotoMoveServer(
    private val files: DriveFileHttpProvider,
    private val headers: DriveFileProvider,
    private val uploads: DriveUploadProvider,
    private val driveId: Uuid = PhotobaseProtocol.DriveAlias,
) : PhotoMoveServer {
    override suspend fun setArchivalStatus(status: ArchivalStatus, files: List<FileVersionTag>) =
        this.files.updateArchivalStatus(driveId, status, files).results

    override suspend fun softDelete(files: List<FileVersionTag>) =
        this.files.softDeleteFiles(driveId, files, TombstoneOptions(keepUniqueId = true)).results

    override suspend fun setLocalTag(tag: Uuid, on: Boolean, fileIds: List<Uuid>): List<PerFileResult> {
        val tags = listOf(tag)
        return this.files.updateLocalTags(driveId, fileIds, add = if (on) tags else emptyList(), remove = if (on) emptyList() else tags).results
    }

    override suspend fun header(fileId: Uuid): HomebaseFile? = headers.getFileHeader(driveId, fileId)

    // The header is the file's own, so a tag is written by rewriting it; one changed meanwhile is read again once.
    override suspend fun setAppTag(tag: Uuid, on: Boolean, fileIds: List<Uuid>): List<PerFileResult> {
        val permits = Semaphore(HEADER_WRITES)
        return coroutineScope {
            fileIds.map { fileId -> async { permits.withPermit { appTag(tag, on, fileId) } } }.awaitAll()
        }
    }

    private suspend fun appTag(tag: Uuid, on: Boolean, fileId: Uuid): PerFileResult {
        repeat(2) {
            val file = header(fileId)?.takeIf { it.fileState == FileState.Active } ?: return PerFileResult(fileId, OdinClientErrorCode.FileNotFound)
            if ((tag in file.fileMetadata.appData.tags.orEmpty()) == on) return PerFileResult(fileId)
            try {
                if (uploads.updateFileByFileId(file.headerUpdate { it.copy(tags = it.tags.withTag(tag, on)) }, onVersionConflict = { null }) != null) {
                    return PerFileResult(fileId)
                }
            } catch (e: ClientException) {
                if (e.errorCode != OdinClientErrorCode.VersionTagMismatch) return PerFileResult(fileId, e.errorCode)
            }
        }
        return PerFileResult(fileId, OdinClientErrorCode.VersionTagMismatch)
    }

    private companion object {
        const val HEADER_WRITES = 8
    }
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
     * Puts [tag] on [fileIds], a heart or an album, or takes it off, wherever each is. No version is checked: a tag is
     * the same whoever set it, and another device's tags on the same photo stand beside this one. An album's tag goes
     * on the photos' headers too ([mirrorAppTag]), which is how a guest's query finds them.
     */
    suspend fun setTag(tag: Uuid, on: Boolean, fileIds: Collection<Uuid>): PhotoMoveResult {
        val tally = Tally(to = null)
        try {
            for (answer in send(fileIds.distinct(), tally) { server.setLocalTag(tag, on, it) }) {
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
            if (tag != PhotobaseProtocol.FavoriteTag) mirrorAppTag(tag, on, tally.written)
        } finally {
            sync()
        }
        return tally.result().also {
            Logger.i(tag = TAG) { "tag $tag ${if (on) "on" else "off"}: ${it.done} done, ${it.changedElsewhere} changed elsewhere, ${it.failed} failed" }
        }
    }

    /**
     * Puts album [tag] on [fileIds]' headers, or takes it off; the number that didn't take. The local tag is what this
     * app shows, so one that fails is logged and left for the album's next share or upkeep to put on again.
     */
    suspend fun mirrorAppTag(tag: Uuid, on: Boolean, fileIds: Collection<Uuid>): Int {
        var failed = 0
        for (batch in fileIds.distinct().chunked(DriveFileHttpProvider.FILE_BATCH)) {
            failed += try {
                server.setAppTag(tag, on, batch).count { it.errorCode != null && it.errorCode != OdinClientErrorCode.FileNotFound }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(tag = TAG, throwable = e) { "album tag $tag not mirrored on ${batch.size} photos" }
                batch.size
            }
        }
        if (failed > 0) Logger.w(tag = TAG) { "album tag $tag ${if (on) "on" else "off"}: $failed photos' headers not written" }
        return failed
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
        val call: suspend (List<FileVersionTag>) -> List<PerFileResult> = { batch -> to?.let { server.setArchivalStatus(it, batch) } ?: server.softDelete(batch) }
        try {
            val again = answer(send(ready, tally, call), from, tally)
            if (again.isNotEmpty()) answer(send(again, tally, call), from, tally, retry = false)
        } finally {
            sync()
        }
        return tally.result().also {
            Logger.i(tag = TAG) { "${to ?: "deleted for good"}: ${it.done} done, ${it.changedElsewhere} changed elsewhere, ${it.failed} failed" }
        }
    }

    /** Sends [files] a batch at a time; one that fails after others went through fails the rest, not the call. */
    private suspend fun <T> send(files: List<T>, tally: Tally, call: suspend (List<T>) -> List<PerFileResult>): List<PerFileResult> {
        val answers = mutableListOf<PerFileResult>()
        for (batch in files.chunked(DriveFileHttpProvider.FILE_BATCH)) {
            try {
                answers += call(batch)
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
