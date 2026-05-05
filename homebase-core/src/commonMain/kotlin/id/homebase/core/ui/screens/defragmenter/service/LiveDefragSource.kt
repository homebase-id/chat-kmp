package id.homebase.core.ui.screens.defragmenter.service

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.sync.DriveSyncManager
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.chat.services.convo.ConversationService
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
 * @param decodeMessageContentProbe Optional probe that returns null when a
 *   chat-message file's `appData.content` decodes successfully as the
 *   payload type the chat layer would use, non-null when it throws.
 *   Production wires this through DI to mirror `ChatMessageStream.mapToMessageData`'s
 *   runtime decoding; tests can leave it null. Null disables corrupt-content
 *   detection entirely.
 */
class LiveDefragSource(
    private val driveSyncManager: DriveSyncManager,
    private val credentialsManager: CredentialsManager,
    private val databaseManager: DatabaseManager,
    private val driveFileProvider: DriveFileProvider,
    private val conversationService: ConversationService,
    private val mapToBasicProbe: (suspend (HomebaseFile) -> Throwable?)? = null,
    private val decodeMessageContentProbe: (suspend (HomebaseFile) -> Throwable?)? = null,
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
                    corruptMessageContentCandidates = emptyList(),
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
                    corruptMessageContentCandidates = emptyList(),
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
                    corruptMessageContentCandidates = emptyList(),
                )
            )
            return@flow
        }

        // Pre-pass: build the set of CONVERSATION uniqueIds whose mapToBasic
        // succeeds — i.e. the "valid parents" any chat message can legitimately
        // point to via appData.groupId. Messages whose groupId isn't in this
        // set get classified as OrphanChatMessage in the main scan, which the
        // repair pass heals via ConversationService.recoverConversation.
        // Skipped if mapToBasicProbe is null (tests / probe-disabled mode);
        // when null, classifyRow is also called with healthyConversationIds=null
        // so orphan detection turns off cleanly.
        val healthyConversationIds: Set<Uuid>? = if (mapToBasicProbe == null) null else {
            val probe = mapToBasicProbe
            val healthy = HashSet<Uuid>()
            val convFiles = runCatching {
                databaseManager.chatReadCount.selectAllConversations(identityId)
            }.getOrElse {
                Logger.w(tag = tag, throwable = it) {
                    "preload conversations failed — orphan-message detection skipped"
                }
                emptyList()
            }
            for (file in convFiles) {
                val uid = file.fileMetadata.appData.uniqueId ?: continue
                if (file.isSoftDeleted()) continue
                val mapErr = runCatching { probe(file) }.getOrNull()
                if (mapErr == null) healthy.add(uid)
            }
            Logger.i(tag = tag) {
                "preload: ${healthy.size}/${convFiles.size} conversations are mappable " +
                        "(others will cause their messages to flag as orphans)"
            }
            healthy
        }

        // Pass 2: paged scan + classifier.
        val accCells = HashMap<Int, CellState>()
        val accGaps = HashMap<Int, DeletedFileRef>()
        val accCorrupt = ArrayList<QuarantineCandidate>()
        val accCorruptMessages = ArrayList<MessageContentQuarantineCandidate>()
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
                        healthyConversationIds = healthyConversationIds,
                        decodeMessageContentProbe = decodeMessageContentProbe,
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
                    if (state is CellState.CorruptMessageContent) {
                        accCorruptMessages.add(MessageContentQuarantineCandidate.from(state))
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
                    "repair_eligible=${totals.legacyUserDateZero + totals.softDeleteArchivalMismatch + totals.orphanChatMessage}"
        }
        if (cumulative != totalBlocks) {
            // Pre-scan count and the actual paged-scan total disagree — usually
            // means rows were inserted/deleted by another writer between the
            // count and the scan. Surface it instead of silently classifying
            // a moving target.
            Logger.w(tag = tag) {
                "analyze row drift: pre_scan_count=$totalBlocks scanned=$cumulative " +
                        "delta=${cumulative - totalBlocks}"
            }
        }
        emit(
            DefragAnalyzeEvent.Done(
                totalBlocks = totalBlocks,
                cellMap = accCells,
                gapMap = accGaps,
                corruptCandidates = accCorrupt,
                corruptMessageContentCandidates = accCorruptMessages,
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
                    repairedOrphanChatMessage = 0,
                    repairedUnmappableConversation = 0,
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
                    repairedOrphanChatMessage = 0,
                    repairedUnmappableConversation = 0,
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

        // Same preload as analyze() so the orphan-message branch fires here too.
        val healthyConversationIds: Set<Uuid>? = if (mapToBasicProbe == null) null else {
            val probe = mapToBasicProbe
            val healthy = HashSet<Uuid>()
            val convFiles = runCatching {
                databaseManager.chatReadCount.selectAllConversations(identityId)
            }.getOrElse { emptyList() }
            for (file in convFiles) {
                val uid = file.fileMetadata.appData.uniqueId ?: continue
                if (file.isSoftDeleted()) continue
                if (runCatching { probe(file) }.getOrNull() == null) healthy.add(uid)
            }
            healthy
        }

        var analyzed = 0
        var repaired = 0
        var repairedLegacy = 0
        var repairedArchival = 0
        var repairedOrphan = 0
        var repairedUnmappableConvo = 0
        var skipped = 0
        // Per-conversation dedup so we call recoverConversation once per missing
        // (or broken) parent, not once per orphan message belonging to it. Used
        // for both OrphanChatMessage and UnmappableConversation branches.
        val alreadyRecovered = HashSet<Uuid>()

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
                        healthyConversationIds = healthyConversationIds,
                        decodeMessageContentProbe = decodeMessageContentProbe,
                    )
                    analyzed += 1
                    when (state) {
                        is CellState.SoftDeleteArchivalMismatch -> {
                            // Drift is now defined as SQL.archivalStatus=Removed
                            // while fileState != Deleted. The canonical truth
                            // says the file is active, so re-project SQL to
                            // match: clear the stale SQL marker (set to 0).
                            // The row then re-classifies as Healthy (or via
                            // other branches) on the next analyze.
                            val ok = runCatching {
                                mainIndex.repairArchivalStatusByRowId(
                                    rowId = row.rowId,
                                    archivalStatus = 0L,
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
                        is CellState.OrphanChatMessage -> {
                            // First orphan for this conversationId triggers
                            // recoverConversation; subsequent orphans for the
                            // same id just emit the colour-flip event (the
                            // parent is already being recovered). originalAuthor
                            // may be null (self-authored group messages) — the
                            // service handles that as group-with-self.
                            if (alreadyRecovered.add(state.conversationId)) {
                                runCatching {
                                    conversationService.recoverConversation(
                                        conversationId = state.conversationId,
                                        originalAuthor = state.originalAuthor,
                                        sender = state.sender,
                                    )
                                }.onFailure {
                                    Logger.w(tag = tag, throwable = it) {
                                        "recoverConversation failed for convo=${state.conversationId}"
                                    }
                                }
                            }
                            repaired += 1
                            repairedOrphan += 1
                            emit(
                                DefragRepairEvent.Repaired(
                                    position = pos,
                                    driveId = slot.driveId,
                                    fileId = row.fileId,
                                    rowId = row.rowId,
                                    kind = DefragRepairEvent.RepairKind.OrphanChatMessage,
                                )
                            )
                        }
                        is CellState.UnmappableConversation -> {
                            val convoId = state.conversationId
                            if (convoId == null) {
                                // Without a uniqueId we can't address the file
                                // through ConversationService — leave it for
                                // the corrupt-JSON prompt flow.
                                skipped += 1
                            } else {
                                if (alreadyRecovered.add(convoId)) {
                                    runCatching {
                                        conversationService.recoverConversation(
                                            conversationId = convoId,
                                            originalAuthor = state.originalAuthor,
                                        )
                                    }.onFailure {
                                        Logger.w(tag = tag, throwable = it) {
                                            "recoverConversation (unmappable) failed for convo=$convoId"
                                        }
                                    }
                                }
                                repaired += 1
                                repairedUnmappableConvo += 1
                                emit(
                                    DefragRepairEvent.Repaired(
                                        position = pos,
                                        driveId = slot.driveId,
                                        fileId = row.fileId,
                                        rowId = row.rowId,
                                        kind = DefragRepairEvent.RepairKind.UnmappableConversation,
                                    )
                                )
                            }
                        }
                        is CellState.CorruptJsonHeader -> {
                            // Not auto-repairable here.
                            skipped += 1
                        }
                        is CellState.CorruptMessageContent -> {
                            // Not auto-repairable here — the file's bytes are
                            // actually broken, no SQL re-projection can fix it.
                            // The corrupt-content review dialog handles delete.
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
                    "legacy=$repairedLegacy archival=$repairedArchival " +
                    "orphans=$repairedOrphan unmappable=$repairedUnmappableConvo " +
                    "recovered_convos=${alreadyRecovered.size} skipped=$skipped"
        }
        emit(
            DefragRepairEvent.Done(
                analyzed = analyzed,
                repaired = repaired,
                repairedLegacyUserDateZero = repairedLegacy,
                repairedSoftDeleteArchivalMismatch = repairedArchival,
                repairedOrphanChatMessage = repairedOrphan,
                repairedUnmappableConversation = repairedUnmappableConvo,
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
            is CellState.CorruptMessageContent -> "corrupt_message_content"
            is CellState.UnmappableConversation -> "unmappable_conversation"
            is CellState.OrphanChatMessage -> "orphan_chat_message"
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
            is CellState.CorruptMessageContent -> Logger.w(tag = tag) {
                "issue=corrupt_message_content drive=$driveId fileId=${row.fileId} " +
                        "rowId=${row.rowId} convoId=${state.conversationId} " +
                        "decodeError=${state.decodeError} " +
                        "content.take(80)=${state.rawContentPrefix.take(80)}"
            }
            is CellState.UnmappableConversation -> Logger.w(tag = tag) {
                "issue=unmappable_conversation drive=$driveId fileId=${row.fileId} " +
                        "rowId=${row.rowId}"
            }
            is CellState.OrphanChatMessage -> Logger.w(tag = tag) {
                "issue=orphan_chat_message drive=$driveId fileId=${row.fileId} " +
                        "rowId=${row.rowId} convoId=${state.conversationId} " +
                        "originalAuthor=${state.originalAuthor?.domainName}"
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
        var corruptMessageContent = 0
        var unmappableConversation = 0
        var orphanChatMessage = 0

        fun bump(state: CellState) {
            when (state) {
                is CellState.Healthy -> healthy += 1
                is CellState.SoftDeleted -> softDeleted += 1
                is CellState.LegacyUserDateZero -> legacyUserDateZero += 1
                is CellState.SoftDeleteArchivalMismatch -> softDeleteArchivalMismatch += 1
                is CellState.CorruptJsonHeader -> corruptJsonHeader += 1
                is CellState.CorruptMessageContent -> corruptMessageContent += 1
                is CellState.UnmappableConversation -> unmappableConversation += 1
                is CellState.OrphanChatMessage -> orphanChatMessage += 1
            }
        }

        operator fun plusAssign(other: StateCounters) {
            healthy += other.healthy
            softDeleted += other.softDeleted
            legacyUserDateZero += other.legacyUserDateZero
            softDeleteArchivalMismatch += other.softDeleteArchivalMismatch
            corruptJsonHeader += other.corruptJsonHeader
            corruptMessageContent += other.corruptMessageContent
            unmappableConversation += other.unmappableConversation
            orphanChatMessage += other.orphanChatMessage
        }

        fun format(): String =
            "healthy=$healthy soft_deleted=$softDeleted " +
                    "legacy_userDate_zero=$legacyUserDateZero " +
                    "softdelete_archival_mismatch=$softDeleteArchivalMismatch " +
                    "corrupt_jsonheader=$corruptJsonHeader " +
                    "corrupt_message_content=$corruptMessageContent " +
                    "unmappable_conversation=$unmappableConversation " +
                    "orphan_chat_message=$orphanChatMessage"
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
        if (!remoteOk) {
            // hardDeleteFile returns false on any 2xx that isn't 200 (e.g. 202,
            // 204). throwForFailure already covered 4xx/5xx, so reaching here
            // with a non-throw means the server accepted but didn't confirm —
            // log it so we don't silently leave the row in the local index.
            Logger.w(tag = tag) {
                "hardDeleteFile non-200 ack for drive=$driveId file=$fileId — local row kept"
            }
            return false
        }

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
