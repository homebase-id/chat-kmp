package id.homebase.core.ui.screens.defragmenter.service

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.DriveSyncManager
import id.homebase.api.sync.database.DatabaseManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.concurrent.Volatile
import kotlin.uuid.Uuid

/**
 * Live DB/API-backed [DefragSource].
 *
 * Analyze is a two-pass streaming scan:
 *  1. Sum `count(*)` across every drive (cheap, indexed). Emit [Sized].
 *  2. Per drive (sorted), keyset-page rows in rowId-ascending order. For each
 *     row, deserialize `jsonHeader` and call [HomebaseFile.isSoftDeleted].
 *     Gaps land at `offset + indexInDrive` in the global grid. After each
 *     chunk emit [Progress]; emit [Done] at the end.
 *
 * Hard delete hits `POST /drives/{driveId}/files/{fileId}/hard-delete` via
 * [DriveFileProvider.hardDeleteFile]. On success we also delete the local
 * `DriveMainIndex` row so the next analyze pass doesn't re-report it.
 * Failures are logged but not retried — this is a best-effort cleanup.
 */
class LiveDefragSource(
    private val driveSyncManager: DriveSyncManager,
    private val credentialsManager: CredentialsManager,
    private val databaseManager: DatabaseManager,
    private val driveFileProvider: DriveFileProvider,
) : DefragSource {

    private val tag = "LiveDefragSource"

    // Cached from the most recent analyze() so hardDelete() can delete the
    // matching local row without re-fetching credentials on every call.
    @Volatile
    private var cachedIdentityId: Uuid? = null

    override fun analyze(): Flow<DefragAnalyzeEvent> = flow {
        val identityId: Uuid = runCatching {
            credentialsManager.requireActiveCredentials().getIdentityId()
        }.getOrElse {
            Logger.w(tag = tag, throwable = it) { "no active credentials — cannot analyze" }
            cachedIdentityId = null
            emit(DefragAnalyzeEvent.Done(totalBlocks = 0, gapMap = emptyMap()))
            return@flow
        }
        cachedIdentityId = identityId

        val drives = driveSyncManager.driveStatuses.value.values
            .map { it.driveId }
            .sortedBy { it.toString() }

        if (drives.isEmpty()) {
            emit(DefragAnalyzeEvent.Done(totalBlocks = 0, gapMap = emptyMap()))
            return@flow
        }

        val mainIndex = databaseManager.driveMainIndex

        // Pass 1: count.
        val driveSlots = ArrayList<DriveSlot>(drives.size)
        var totalBlocks = 0
        for (driveId in drives) {
            val count = runCatching { mainIndex.countByIdentityAndDrive(identityId, driveId) }
                .getOrElse {
                    Logger.w(tag = tag, throwable = it) { "count failed for drive $driveId" }
                    0L
                }
                .coerceIn(0L, Int.MAX_VALUE.toLong() - totalBlocks)
                .toInt()
            if (count == 0) continue
            driveSlots.add(DriveSlot(driveId = driveId, offset = totalBlocks, count = count))
            totalBlocks += count
        }
        emit(DefragAnalyzeEvent.Sized(totalBlocks = totalBlocks))
        if (totalBlocks == 0) {
            emit(DefragAnalyzeEvent.Done(totalBlocks = 0, gapMap = emptyMap()))
            return@flow
        }

        // Pass 2: paged scan + deserialize.
        val accGaps = HashMap<Int, DeletedFileRef>()
        var cumulative = 0
        for (slot in driveSlots) {
            var sinceRowId = 0L
            var indexInDrive = 0
            while (indexInDrive < slot.count) {
                val page = runCatching {
                    mainIndex.selectFileIdAndJsonByDriveSince(
                        identityId = identityId,
                        driveId = slot.driveId,
                        sinceRowId = sinceRowId,
                        limit = PAGE_SIZE,
                    )
                }.getOrElse {
                    Logger.w(tag = tag, throwable = it) {
                        "page scan failed for drive=${slot.driveId} since=$sinceRowId"
                    }
                    emptyList()
                }
                if (page.isEmpty()) break

                val chunkGaps = HashMap<Int, DeletedFileRef>()
                for (row in page) {
                    val header = runCatching {
                        OdinSystemSerializer.deserialize<HomebaseFile>(row.jsonHeader)
                    }.getOrNull()
                    if (header != null && header.isSoftDeleted()) {
                        val pos = slot.offset + indexInDrive
                        chunkGaps[pos] = DeletedFileRef(driveId = slot.driveId, fileId = row.fileId)
                    }
                    indexInDrive += 1
                    cumulative += 1
                    sinceRowId = row.rowId
                    if (indexInDrive >= slot.count) break
                }
                accGaps.putAll(chunkGaps)
                emit(
                    DefragAnalyzeEvent.Progress(
                        analyzedUpto = cumulative,
                        newGaps = chunkGaps,
                    )
                )
                yield()
            }
        }

        Logger.d(tag = tag) {
            "analyze: totalBlocks=$totalBlocks, gaps=${accGaps.size} across ${driveSlots.size} drive(s)"
        }
        emit(DefragAnalyzeEvent.Done(totalBlocks = totalBlocks, gapMap = accGaps))
    }.flowOn(Dispatchers.Default)

    override suspend fun hardDelete(driveId: Uuid, fileId: Uuid): Boolean {
        val remoteOk = runCatching {
            driveFileProvider.hardDeleteFile(driveId = driveId, fileId = fileId)
        }.getOrElse {
            Logger.w(tag = tag, throwable = it) {
                "hardDeleteFile failed for drive=$driveId file=$fileId"
            }
            false
        }
        if (!remoteOk) return false

        // Remote call succeeded — also remove the local DriveMainIndex row so
        // subsequent Analyze passes don't re-report it as soft-deleted.
        val identityId = cachedIdentityId
        if (identityId != null) {
            runCatching {
                databaseManager.driveMainIndex.deleteBy(
                    identityId = identityId,
                    driveId = driveId,
                    fileId = fileId,
                )
            }.onFailure {
                Logger.w(tag = tag, throwable = it) {
                    "local deleteBy failed for drive=$driveId file=$fileId"
                }
            }
        }
        return true
    }

    override suspend fun vacuum() {
        withContext(Dispatchers.Default) { databaseManager.vacuum() }
    }

    private data class DriveSlot(val driveId: Uuid, val offset: Int, val count: Int)

    private companion object {
        const val PAGE_SIZE = 500L
    }
}
