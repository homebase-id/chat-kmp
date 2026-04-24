package id.homebase.core.ui.screens.defragmenter.service

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.sync.DriveSyncManager
import id.homebase.api.sync.database.DatabaseManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.random.Random
import kotlin.uuid.Uuid

/**
 * Live DB/API-backed [DefragSource].
 *
 * Analyze walks every drive in [DriveSyncManager.driveStatuses], sums
 * `count(*)`, and fetches soft-deleted `fileId`s via the new
 * `selectSoftDeletedFileIds` SQL query. Each drive gets a contiguous range in
 * the grid; soft-deleted files are scattered to random positions within their
 * drive's range so the visualisation looks realistic rather than clumped at
 * the top of the grid.
 *
 * Hard delete hits `POST /drives/{driveId}/files/{fileId}/hard-delete` via
 * [DriveFileProvider.hardDeleteFile]. Failures are logged but not retried —
 * this is a best-effort cleanup feature.
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

    override suspend fun analyze(): DefragAnalysis = withContext(Dispatchers.Default) {
        val identityId: Uuid = runCatching {
            credentialsManager.requireActiveCredentials().getIdentityId()
        }.getOrElse {
            Logger.w(tag = tag, throwable = it) { "no active credentials — cannot analyze" }
            cachedIdentityId = null
            return@withContext DefragAnalysis.EMPTY
        }
        cachedIdentityId = identityId

        // Sort drives so grid assignment is stable across Analyze runs.
        val drives = driveSyncManager.driveStatuses.value.values
            .map { it.driveId to it.label }
            .sortedBy { it.first.toString() }

        if (drives.isEmpty()) return@withContext DefragAnalysis.EMPTY

        val mainIndex = databaseManager.driveMainIndex
        val gapMap = HashMap<Int, DeletedFileRef>()
        var totalBlocks = 0
        val scatterRandom = Random.Default

        for ((driveId, _) in drives) {
            val count = runCatching { mainIndex.countByIdentityAndDrive(identityId, driveId) }
                .getOrElse {
                    Logger.w(tag = tag, throwable = it) { "count failed for drive $driveId" }
                    0L
                }
                .coerceIn(0L, Int.MAX_VALUE.toLong() - totalBlocks)
                .toInt()
            if (count == 0) continue

            val softIds = runCatching { mainIndex.selectSoftDeletedFileIds(identityId, driveId) }
                .getOrElse {
                    Logger.w(tag = tag, throwable = it) { "soft-delete enumeration failed for drive $driveId" }
                    emptyList()
                }
                .take(count) // defensively clamp — can't have more gaps than rows.

            // Scatter softIds into random distinct positions inside this drive's
            // grid range [offset, offset + count). Using a Fisher-Yates partial
            // shuffle so each position is chosen exactly once.
            val offset = totalBlocks
            if (softIds.isNotEmpty()) {
                val positions = IntArray(count) { it } // 0..count-1
                for (i in 0 until softIds.size) {
                    val j = i + scatterRandom.nextInt(count - i)
                    val tmp = positions[i]
                    positions[i] = positions[j]
                    positions[j] = tmp
                    val absPos = offset + positions[i]
                    gapMap[absPos] = DeletedFileRef(driveId = driveId, fileId = softIds[i])
                }
            }

            totalBlocks += count
        }

        Logger.d(tag = tag) {
            "analyze: totalBlocks=$totalBlocks, gaps=${gapMap.size} across ${drives.size} drive(s)"
        }
        DefragAnalysis(totalBlocks = totalBlocks, gapMap = gapMap)
    }

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
        databaseManager.vacuum()
    }
}
