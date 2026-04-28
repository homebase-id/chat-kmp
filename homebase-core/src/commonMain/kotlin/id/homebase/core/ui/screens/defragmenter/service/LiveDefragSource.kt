package id.homebase.core.ui.screens.defragmenter.service

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.DriveFileProvider
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
 *     row, deserialize `jsonHeader` and run the classifier ([classifyRow]) to
 *     decide which [CellState] the row maps to. Soft-deleted rows still
 *     populate `newGaps`/`gapMap` for the existing hard-delete animation;
 *     new consumers read the richer `newCells`/`cellMap`.
 *
 * Hard delete hits `POST /drives/{driveId}/files/{fileId}/hard-delete` via
 * [DriveFileProvider.hardDeleteFile]. On success we also delete the local
 * `DriveMainIndex` row so the next analyze pass doesn't re-report it.
 * Failures are logged but not retried — this is a best-effort cleanup.
 *
 * @param mapToBasicProbe Optional probe that returns null when a conversation
 *   file (fileType=8888) maps cleanly via `ConversationMapper.mapToBasic`,
 *   non-null when it throws. Production wires the real mapper through DI;
 *   tests inject any predicate. Null disables the conversation-mapper check
 *   entirely (rows are then always classified as Healthy on the conversation
 *   axis).
 */
class LiveDefragSource(
    private val driveSyncManager: DriveSyncManager,
    private val credentialsManager: CredentialsManager,
    private val databaseManager: DatabaseManager,
    private val driveFileProvider: DriveFileProvider,
    private val mapToBasicProbe: (suspend (HomebaseFile) -> Throwable?)? = null,
) : DefragSource {

    private val tag = "Defrag"

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
            emit(
                DefragAnalyzeEvent.Done(
                    totalBlocks = 0,
                    cellMap = emptyMap(),
                    gapMap = emptyMap(),
                    corruptCandidates = emptyList(),
                )
            )
            return@flow
        }
        cachedIdentityId = identityId

        val drives = driveSyncManager.driveStatuses.value.values
            .map { it.driveId }
            .sortedBy { it.toString() }

        if (drives.isEmpty()) {
            emit(
                DefragAnalyzeEvent.Done(
                    totalBlocks = 0,
                    cellMap = emptyMap(),
                    gapMap = emptyMap(),
                    corruptCandidates = emptyList(),
                )
            )
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
            emit(
                DefragAnalyzeEvent.Done(
                    totalBlocks = 0,
                    cellMap = emptyMap(),
                    gapMap = emptyMap(),
                    corruptCandidates = emptyList(),
                )
            )
            return@flow
        }

        // Pass 2: paged scan + classifier.
        val accCells = HashMap<Int, CellState>()
        val accGaps = HashMap<Int, DeletedFileRef>()
        val accCorrupt = ArrayList<QuarantineCandidate>()
        // Cumulative per-state counters for the final summary.
        val totals = StateCounters()
        // Per-issue first-occurrence dedup, drive-scoped — so the log gets one
        // detail line per (driveId, issueType) pair across the whole scan.
        val firstSeen = HashSet<Pair<Uuid, String>>()

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

                val chunkCells = HashMap<Int, CellState>()
                val chunkGaps = HashMap<Int, DeletedFileRef>()
                val chunkCounters = StateCounters()
                for (row in page) {
                    val pos = slot.offset + indexInDrive
                    val state = classifyRow(
                        driveId = slot.driveId,
                        row = row,
                        mapToBasicProbe = mapToBasicProbe,
                    )
                    chunkCells[pos] = state
                    chunkCounters.bump(state)
                    if (state is CellState.SoftDeleted) {
                        chunkGaps[pos] = state.ref
                    }
                    if (state is CellState.CorruptJsonHeader) {
                        // Build the prompt-ready candidate by re-running the
                        // strict deserialise to capture the actual exception
                        // message; lenient JSON pass extracts whatever salvageable
                        // metadata it can.
                        val err = runCatching {
                            id.homebase.api.serialization.OdinSystemSerializer
                                .deserialize<HomebaseFile>(row.jsonHeader)
                        }.exceptionOrNull()
                        accCorrupt.add(buildQuarantineCandidate(slot.driveId, row, err))
                    }
                    logFirstOccurrence(state, slot.driveId, row, firstSeen)
                    indexInDrive += 1
                    cumulative += 1
                    sinceRowId = row.rowId
                    if (indexInDrive >= slot.count) break
                }
                accCells.putAll(chunkCells)
                accGaps.putAll(chunkGaps)
                totals += chunkCounters
                Logger.i(tag = tag) {
                    "chunk drive=${slot.driveId} offset=${slot.offset} count=${page.size} ${chunkCounters.format()}"
                }
                emit(
                    DefragAnalyzeEvent.Progress(
                        analyzedUpto = cumulative,
                        newCells = chunkCells,
                        newGaps = chunkGaps,
                    )
                )
                yield()
            }
        }

        Logger.i(tag = tag) {
            "analyze complete: rows=$totalBlocks drives=${driveSlots.size} ${totals.format()} " +
                    "repair_eligible=${totals.legacyUserDateZero + totals.softDeleteArchivalMismatch}"
        }
        emit(
            DefragAnalyzeEvent.Done(
                totalBlocks = totalBlocks,
                cellMap = accCells,
                gapMap = accGaps,
                corruptCandidates = accCorrupt,
            )
        )
    }.flowOn(Dispatchers.Default)

    /**
     * Re-scans every drive in the same order/cadence as [analyze], applies the
     * local-only repair for any [CellState.LegacyUserDateZero] /
     * [CellState.SoftDeleteArchivalMismatch] row encountered, and emits one
     * [DefragRepairEvent.Repaired] per successful UPDATE so the UI can flip
     * cells back to Healthy as we go. CorruptJsonHeader and
     * UnmappableConversation rows are not auto-repairable here — they need
     * the prompt-driven hard-delete or upstream content fix.
     *
     * Each repair is one tiny SQL UPDATE; the reclassify+UPDATE per page
     * stays well under the page-scan latency.
     *
     * ────────────────────────────────────────────────────────────────────
     * TODO(server-repair): the current implementation is LOCAL-ONLY.
     *
     *   Some classifier states are caused by inconsistencies on the
     *   server-side file itself — notably [CellState.SoftDeleteArchivalMismatch],
     *   which fires when a file's JSON has `fileState = deleted` but
     *   `appData.archivalStatus != Removed`. The SQL projection faithfully
     *   captures the inconsistent value, so a logout + resync from the
     *   server will reproduce the same flagged state and the local UPDATE
     *   we just applied is wiped.
     *
     *   The honest fix is download-verify-patch-upload:
     *     1. GET the canonical header from the server by fileId.
     *     2. Verify the inconsistency is still present (server may have
     *        self-healed; skip if so).
     *     3. Patch the JSON in place — for archivalStatus drift, set
     *        `appData.archivalStatus = Removed` so the file is internally
     *        consistent. Leave everything else identical.
     *     4. PUT/UPDATE the file (UpdateFileByUniqueId-style — there is
     *        existing outbox infrastructure). Server bumps versionTag,
     *        distributes to peers via sync; their SQL projections heal too.
     *
     *   Caveats to design for before flipping the switch:
     *     - Write authority: peer-authored files (most chat messages) we
     *       can't update server-side; the server rejects. The pass would
     *       have to skip those and report them as "needs_owner_repair".
     *     - VersionTag bumps cascade as sync events to all peers. Pace
     *       uploads (e.g. one per 100ms) so we don't thundering-herd.
     *     - Encrypted payloads stay untouched — we only rewrite header
     *       metadata, no re-encryption needed.
     *     - Add a "verify on server" preflight that downloads a sample
     *       and confirms the diagnosis before any write goes out.
     *
     *   Until that lands, keep this method local-only. The user explicitly
     *   chose the conservative path so we don't risk corrupting server
     *   data while the diagnosis logic is still bedding in.
     * ────────────────────────────────────────────────────────────────────
     */
    override fun repair(): Flow<DefragRepairEvent> = flow {
        val identityId: Uuid = runCatching {
            credentialsManager.requireActiveCredentials().getIdentityId()
        }.getOrElse {
            Logger.w(tag = tag, throwable = it) { "repair: no active credentials — skipping" }
            emit(
                DefragRepairEvent.Done(
                    analyzed = 0,
                    repaired = 0,
                    repairedLegacyUserDateZero = 0,
                    repairedSoftDeleteArchivalMismatch = 0,
                    skipped = 0,
                )
            )
            return@flow
        }

        val drives = driveSyncManager.driveStatuses.value.values
            .map { it.driveId }
            .sortedBy { it.toString() }
        if (drives.isEmpty()) {
            emit(
                DefragRepairEvent.Done(
                    analyzed = 0,
                    repaired = 0,
                    repairedLegacyUserDateZero = 0,
                    repairedSoftDeleteArchivalMismatch = 0,
                    skipped = 0,
                )
            )
            return@flow
        }

        val mainIndex = databaseManager.driveMainIndex

        // Pass 1: re-count + slot offsets, mirroring analyze() so positions
        // emitted on Repaired line up with the UI's grid cell map.
        val driveSlots = ArrayList<DriveSlot>(drives.size)
        var totalBlocks = 0
        for (driveId in drives) {
            val count = runCatching { mainIndex.countByIdentityAndDrive(identityId, driveId) }
                .getOrElse { 0L }
                .coerceIn(0L, Int.MAX_VALUE.toLong() - totalBlocks)
                .toInt()
            if (count == 0) continue
            driveSlots.add(DriveSlot(driveId = driveId, offset = totalBlocks, count = count))
            totalBlocks += count
        }

        emit(DefragRepairEvent.Started(eligibleEstimate = -1))

        var analyzed = 0
        var repaired = 0
        var repairedLegacy = 0
        var repairedArchival = 0
        var skipped = 0

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
                        "repair page scan failed for drive=${slot.driveId} since=$sinceRowId"
                    }
                    emptyList()
                }
                if (page.isEmpty()) break

                for (row in page) {
                    val pos = slot.offset + indexInDrive
                    val state = classifyRow(
                        driveId = slot.driveId,
                        row = row,
                        mapToBasicProbe = mapToBasicProbe,
                    )
                    analyzed += 1
                    when (state) {
                        is CellState.SoftDeleteArchivalMismatch -> {
                            val ok = runCatching {
                                mainIndex.repairArchivalStatusByRowId(
                                    rowId = row.rowId,
                                    archivalStatus = ARCHIVAL_STATUS_REMOVED,
                                )
                            }.getOrElse {
                                Logger.w(tag = tag, throwable = it) {
                                    "repair archivalStatus failed rowId=${row.rowId}"
                                }
                                false
                            }
                            if (ok) {
                                repaired += 1
                                repairedArchival += 1
                                emit(
                                    DefragRepairEvent.Repaired(
                                        position = pos,
                                        driveId = slot.driveId,
                                        fileId = row.fileId,
                                        rowId = row.rowId,
                                        kind = DefragRepairEvent.RepairKind.SoftDeleteArchivalMismatch,
                                    )
                                )
                            } else {
                                skipped += 1
                            }
                        }
                        is CellState.LegacyUserDateZero -> {
                            val ok = runCatching {
                                mainIndex.repairUserDateByRowId(
                                    rowId = row.rowId,
                                    userDate = state.createdMs,
                                )
                            }.getOrElse {
                                Logger.w(tag = tag, throwable = it) {
                                    "repair userDate failed rowId=${row.rowId}"
                                }
                                false
                            }
                            if (ok) {
                                repaired += 1
                                repairedLegacy += 1
                                emit(
                                    DefragRepairEvent.Repaired(
                                        position = pos,
                                        driveId = slot.driveId,
                                        fileId = row.fileId,
                                        rowId = row.rowId,
                                        kind = DefragRepairEvent.RepairKind.LegacyUserDateZero,
                                    )
                                )
                            } else {
                                skipped += 1
                            }
                        }
                        is CellState.CorruptJsonHeader,
                        is CellState.UnmappableConversation -> {
                            // Not auto-repairable here.
                            skipped += 1
                        }
                        is CellState.SoftDeleted, is CellState.Healthy -> Unit
                    }
                    indexInDrive += 1
                    sinceRowId = row.rowId
                    if (indexInDrive >= slot.count) break
                }
                yield()
            }
        }

        Logger.i(tag = tag) {
            "repair complete: analyzed=$analyzed repaired=$repaired " +
                    "legacy=$repairedLegacy archival=$repairedArchival skipped=$skipped"
        }
        emit(
            DefragRepairEvent.Done(
                analyzed = analyzed,
                repaired = repaired,
                repairedLegacyUserDateZero = repairedLegacy,
                repairedSoftDeleteArchivalMismatch = repairedArchival,
                skipped = skipped,
            )
        )
    }.flowOn(Dispatchers.Default)

    private fun logFirstOccurrence(
        state: CellState,
        driveId: Uuid,
        row: id.homebase.api.sync.database.DriveMainIndexWrapper.PagedScanRow,
        firstSeen: HashSet<Pair<Uuid, String>>,
    ) {
        val issueKey: String = when (state) {
            is CellState.LegacyUserDateZero -> "legacy_userDate_zero"
            is CellState.SoftDeleteArchivalMismatch -> "softdelete_archival_mismatch"
            is CellState.CorruptJsonHeader -> "corrupt_jsonheader"
            is CellState.UnmappableConversation -> "unmappable_conversation"
            // Healthy and SoftDeleted are not "issues" — no per-occurrence detail line.
            else -> return
        }
        if (!firstSeen.add(driveId to issueKey)) return
        when (state) {
            is CellState.LegacyUserDateZero -> Logger.w(tag = tag) {
                "issue=legacy_userDate_zero drive=$driveId fileId=${row.fileId} " +
                        "rowId=${row.rowId} created(ms)=${state.createdMs}"
            }
            is CellState.SoftDeleteArchivalMismatch -> Logger.w(tag = tag) {
                "issue=softdelete_archival_mismatch drive=$driveId fileId=${row.fileId} " +
                        "rowId=${row.rowId} archivalStatus=${row.archivalStatus}"
            }
            is CellState.CorruptJsonHeader -> Logger.w(tag = tag) {
                "issue=corrupt_jsonheader drive=$driveId fileId=${row.fileId} " +
                        "rowId=${row.rowId} header.take(120)=${row.jsonHeader.take(120)}"
            }
            is CellState.UnmappableConversation -> Logger.w(tag = tag) {
                "issue=unmappable_conversation drive=$driveId fileId=${row.fileId} " +
                        "rowId=${row.rowId}"
            }
            // Healthy and SoftDeleted are filtered out by the early-return above.
            is CellState.Healthy, is CellState.SoftDeleted -> Unit
        }
    }

    /** Per-state counters for chunk + summary logging. */
    private class StateCounters {
        var healthy = 0
        var softDeleted = 0
        var legacyUserDateZero = 0
        var softDeleteArchivalMismatch = 0
        var corruptJsonHeader = 0
        var unmappableConversation = 0

        fun bump(state: CellState) {
            when (state) {
                is CellState.Healthy -> healthy += 1
                is CellState.SoftDeleted -> softDeleted += 1
                is CellState.LegacyUserDateZero -> legacyUserDateZero += 1
                is CellState.SoftDeleteArchivalMismatch -> softDeleteArchivalMismatch += 1
                is CellState.CorruptJsonHeader -> corruptJsonHeader += 1
                is CellState.UnmappableConversation -> unmappableConversation += 1
            }
        }

        operator fun plusAssign(other: StateCounters) {
            healthy += other.healthy
            softDeleted += other.softDeleted
            legacyUserDateZero += other.legacyUserDateZero
            softDeleteArchivalMismatch += other.softDeleteArchivalMismatch
            corruptJsonHeader += other.corruptJsonHeader
            unmappableConversation += other.unmappableConversation
        }

        fun format(): String =
            "healthy=$healthy soft_deleted=$softDeleted " +
                    "legacy_userDate_zero=$legacyUserDateZero " +
                    "softdelete_archival_mismatch=$softDeleteArchivalMismatch " +
                    "corrupt_jsonheader=$corruptJsonHeader " +
                    "unmappable_conversation=$unmappableConversation"
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
        withContext(Dispatchers.Default) { databaseManager.vacuum() }
    }

    private data class DriveSlot(val driveId: Uuid, val offset: Int, val count: Int)

    private companion object {
        const val PAGE_SIZE = 500L
    }
}
