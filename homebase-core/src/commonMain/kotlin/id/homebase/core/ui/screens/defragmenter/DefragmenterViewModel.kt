package id.homebase.core.ui.screens.defragmenter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.core.ui.screens.defragmenter.model.BlockGrid
import id.homebase.core.ui.screens.defragmenter.model.CELL_CORRUPT_JSON_HEADER
import id.homebase.core.ui.screens.defragmenter.model.CELL_CORRUPT_MESSAGE_CONTENT
import id.homebase.core.ui.screens.defragmenter.model.CELL_LEGACY_USERDATE_ZERO
import id.homebase.core.ui.screens.defragmenter.model.CELL_SOFT_DELETE_ARCHIVAL_MISMATCH
import id.homebase.core.ui.screens.defragmenter.model.CELL_ORPHAN_CHAT_MESSAGE
import id.homebase.core.ui.screens.defragmenter.model.CELL_UNMAPPABLE_CONVERSATION
import id.homebase.core.ui.screens.defragmenter.model.CELL_HEALTHY
import id.homebase.core.ui.screens.defragmenter.service.CellState
import id.homebase.core.ui.screens.defragmenter.service.DefragAnalyzeEvent
import id.homebase.core.ui.screens.defragmenter.service.DefragRepairEvent
import id.homebase.core.ui.screens.defragmenter.service.DefragSource
import id.homebase.core.ui.screens.defragmenter.service.DeletedFileRef
import kotlin.uuid.Uuid
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.time.TimeSource

class DefragmenterViewModel(
    private val source: DefragSource,
) : ViewModel() {

    private val tag = "Defragmenter"

    private val _uiState = MutableStateFlow(DefragmenterUiState())
    val uiState: StateFlow<DefragmenterUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<DefragmenterUiEvent>(extraBufferCapacity = 1)
    val events = _events.asSharedFlow()

    // Tick-loop bookkeeping
    private var lastTickNanos: Long = 0L
    private var runStartMark: TimeSource.Monotonic.ValueTimeMark? = null
    private var elapsedMsAtPause: Long = 0L
    private var pendingMoves: Float = 0f
    private val remainingGaps: ArrayList<Int> = ArrayList()
    private var nextFilledCursor: Int = Int.MAX_VALUE
    private var moveIdCounter: Long = 0L
    private var smoothedMovesPerSecond: Float = BASE_MOVES_PER_SECOND

    // Per-run animation telemetry, reset when a new defrag starts. Logged
    // alongside the cleanup-pass summary so we can tell at a glance how many
    // hard-deletes the move loop fired vs the unreachable-tail sweep.
    private var animationCommits: Long = 0L
    private var animationHardDeletes: Long = 0L

    private var analyzeJob: Job? = null
    private var repairJob: Job? = null

    // Maps grid position → file-to-hard-delete. Populated on Analyze; the
    // tick loop looks up the toIndex of each committed move here and fires
    // hardDelete on that file.
    private var gapMap: Map<Int, DeletedFileRef> = emptyMap()

    fun onAction(action: DefragmenterUiAction) {
        when (action) {
            DefragmenterUiAction.Analyze -> analyze()
            DefragmenterUiAction.Start -> start()
            DefragmenterUiAction.Pause -> pause()
            DefragmenterUiAction.Resume -> resume()
            DefragmenterUiAction.Cancel -> cancel()
            DefragmenterUiAction.Close -> viewModelScope.launch {
                _events.emit(DefragmenterUiEvent.CloseRequested)
            }
            DefragmenterUiAction.OpenReview -> openReview()
            DefragmenterUiAction.DismissReview -> dismissReview()
            DefragmenterUiAction.ReviewSkip -> reviewSkip()
            DefragmenterUiAction.ReviewDelete -> reviewDelete()
        }
    }

    // region Phase transitions

    private fun analyze() {
        analyzeJob?.cancel()
        analyzeJob = viewModelScope.launch {
            // Reset for a fresh scan; show empty grid immediately.
            _uiState.update {
                it.copy(
                    phase = DefragmenterPhase.Analyzing,
                    grid = BlockGrid.EMPTY,
                    gridVersion = it.gridVersion + 1,
                    totalBlocks = 0,
                    initialGaps = 0,
                    gapsRemaining = 0,
                    movesCompleted = 0L,
                    progressFraction = 0f,
                    elapsedMs = 0L,
                    estRemainingMs = 0L,
                    inFlight = emptyList(),
                    targetHighlights = IntArray(0),
                    analyzedUpto = 0,
                    analyzeTotal = 0,
                    cellStates = ByteArray(0),
                    issueCountLegacyUserDateZero = 0,
                    issueCountArchivalMismatch = 0,
                    issueCountCorruptJson = 0,
                    issueCountUnmappableConvo = 0,
                    issueCountOrphanMessage = 0,
                    issueCountCorruptMessageContent = 0,
                    corruptHeaderCandidates = emptyList(),
                    corruptMessageCandidates = emptyList(),
                    reviewIndex = 0,
                    showReviewDialog = false,
                )
            }
            val accGaps = HashMap<Int, DeletedFileRef>()
            var liveGrid: BlockGrid = BlockGrid.EMPTY
            var liveCellStates: ByteArray = ByteArray(0)
            var legacyCount = 0
            var archivalCount = 0
            var corruptCount = 0
            var unmappableCount = 0
            var orphanCount = 0
            var corruptMessageContentCount = 0
            source.analyze().collect { ev ->
                when (ev) {
                    is DefragAnalyzeEvent.Sized -> {
                        liveGrid = BlockGrid.createEmpty(ev.totalBlocks)
                        liveCellStates = ByteArray(ev.totalBlocks) // default 0 = Healthy
                        _uiState.update {
                            it.copy(
                                grid = liveGrid,
                                cellStates = liveCellStates,
                                gridVersion = it.gridVersion + 1,
                                totalBlocks = ev.totalBlocks,
                                analyzeTotal = ev.totalBlocks,
                                analyzedUpto = 0,
                            )
                        }
                    }

                    is DefragAnalyzeEvent.Progress -> {
                        val prev = _uiState.value.analyzedUpto
                        // Flip non-gap positions in [prev, analyzedUpto) to filled.
                        // Positions present in newGaps stay cleared (= gap).
                        val grid = liveGrid
                        if (grid !== BlockGrid.EMPTY) {
                            for (pos in prev until ev.analyzedUpto) {
                                if (!ev.newGaps.containsKey(pos)) {
                                    grid.setFilled(pos, true)
                                }
                            }
                        }
                        if (ev.newGaps.isNotEmpty()) accGaps.putAll(ev.newGaps)
                        // Stamp per-cell state codes for the canvas. SoftDeleted
                        // is left at 0 — the gap representation already covers
                        // it. Healthy is also 0 (the array default).
                        val states = liveCellStates
                        if (states.size == grid.totalBlocks) {
                            for ((pos, state) in ev.newCells) {
                                if (pos !in 0 until states.size) continue
                                when (state) {
                                    is CellState.LegacyUserDateZero -> {
                                        states[pos] = CELL_LEGACY_USERDATE_ZERO
                                        legacyCount++
                                    }
                                    is CellState.SoftDeleteArchivalMismatch -> {
                                        states[pos] = CELL_SOFT_DELETE_ARCHIVAL_MISMATCH
                                        archivalCount++
                                    }
                                    is CellState.CorruptJsonHeader -> {
                                        states[pos] = CELL_CORRUPT_JSON_HEADER
                                        corruptCount++
                                    }
                                    is CellState.UnmappableConversation -> {
                                        states[pos] = CELL_UNMAPPABLE_CONVERSATION
                                        unmappableCount++
                                    }
                                    is CellState.OrphanChatMessage -> {
                                        states[pos] = CELL_ORPHAN_CHAT_MESSAGE
                                        orphanCount++
                                    }
                                    is CellState.CorruptMessageContent -> {
                                        states[pos] = CELL_CORRUPT_MESSAGE_CONTENT
                                        corruptMessageContentCount++
                                    }
                                    is CellState.Healthy, is CellState.SoftDeleted -> Unit
                                }
                            }
                        }
                        _uiState.update {
                            it.copy(
                                analyzedUpto = ev.analyzedUpto,
                                gridVersion = it.gridVersion + 1,
                                issueCountLegacyUserDateZero = legacyCount,
                                issueCountArchivalMismatch = archivalCount,
                                issueCountCorruptJson = corruptCount,
                                issueCountUnmappableConvo = unmappableCount,
                                issueCountOrphanMessage = orphanCount,
                                issueCountCorruptMessageContent = corruptMessageContentCount,
                            )
                        }
                    }

                    is DefragAnalyzeEvent.Done -> {
                        gapMap = accGaps
                        resetCursors()
                        val grid = liveGrid
                        val total = ev.totalBlocks
                        Logger.d(tag = tag) {
                            "Analyze complete: total=$total, gaps=${accGaps.size}, " +
                                    "legacy=$legacyCount archival=$archivalCount " +
                                    "corrupt=$corruptCount unmappable=$unmappableCount " +
                                    "corruptMessageContent=$corruptMessageContentCount " +
                                    "reviewQueue=${ev.corruptCandidates.size + ev.corruptMessageContentCandidates.size}"
                        }
                        _uiState.update {
                            it.copy(
                                phase = DefragmenterPhase.Ready,
                                grid = grid,
                                cellStates = liveCellStates,
                                gridVersion = it.gridVersion + 1,
                                totalBlocks = total,
                                initialGaps = accGaps.size,
                                gapsRemaining = accGaps.size,
                                analyzedUpto = total,
                                analyzeTotal = total,
                                issueCountLegacyUserDateZero = legacyCount,
                                issueCountArchivalMismatch = archivalCount,
                                issueCountCorruptJson = corruptCount,
                                issueCountUnmappableConvo = unmappableCount,
                                issueCountOrphanMessage = orphanCount,
                                issueCountCorruptMessageContent = corruptMessageContentCount,
                                corruptHeaderCandidates = ev.corruptCandidates,
                                corruptMessageCandidates = ev.corruptMessageContentCandidates,
                                reviewIndex = 0,
                                showReviewDialog = false,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun start() {
        val s = _uiState.value
        if (s.phase !is DefragmenterPhase.Ready) return
        if (s.gapsRemaining == 0) {
            // No soft-deletes to compact. If the classifier flagged repair-eligible
            // rows, run the repair pass (animates per-cell colour flips) before the
            // vacuum/green finale. Otherwise jump straight to vacuum.
            if (hasRepairTargets(s)) {
                _uiState.update { it.copy(phase = DefragmenterPhase.Repairing) }
                kickOffRepair()
            } else {
                enterVacuumAndComplete()
            }
            return
        }
        runStartMark = TimeSource.Monotonic.markNow()
        elapsedMsAtPause = 0L
        pendingMoves = 0f
        lastTickNanos = 0L
        smoothedMovesPerSecond = BASE_MOVES_PER_SECOND
        animationCommits = 0L
        animationHardDeletes = 0L
        _uiState.update { it.copy(phase = DefragmenterPhase.Defragmenting) }
    }

    private fun hasRepairTargets(s: DefragmenterUiState): Boolean =
        s.issueCountLegacyUserDateZero > 0 ||
            s.issueCountArchivalMismatch > 0 ||
            s.issueCountOrphanMessage > 0 ||
            s.issueCountUnmappableConvo > 0

    private fun pause() {
        val s = _uiState.value
        if (s.phase !is DefragmenterPhase.Defragmenting) return
        elapsedMsAtPause = s.elapsedMs
        _uiState.update { it.copy(phase = DefragmenterPhase.Paused) }
    }

    private fun resume() {
        val s = _uiState.value
        if (s.phase !is DefragmenterPhase.Paused) return
        runStartMark = TimeSource.Monotonic.markNow()
        lastTickNanos = 0L
        _uiState.update { it.copy(phase = DefragmenterPhase.Defragmenting) }
    }

    private fun cancel() {
        val s = _uiState.value
        if (s.phase is DefragmenterPhase.Analyzing) {
            // Abort the in-progress scan and return to Idle. The partial
            // grid/gap state is discarded — next Analyze starts fresh.
            analyzeJob?.cancel()
            analyzeJob = null
            gapMap = emptyMap()
            _uiState.update {
                it.copy(
                    phase = DefragmenterPhase.Idle,
                    grid = BlockGrid.EMPTY,
                    gridVersion = it.gridVersion + 1,
                    totalBlocks = 0,
                    initialGaps = 0,
                    gapsRemaining = 0,
                    analyzedUpto = 0,
                    analyzeTotal = 0,
                    inFlight = emptyList(),
                    targetHighlights = IntArray(0),
                    cellStates = ByteArray(0),
                    issueCountLegacyUserDateZero = 0,
                    issueCountArchivalMismatch = 0,
                    issueCountCorruptJson = 0,
                    issueCountUnmappableConvo = 0,
                    issueCountOrphanMessage = 0,
                    issueCountCorruptMessageContent = 0,
                    corruptHeaderCandidates = emptyList(),
                    corruptMessageCandidates = emptyList(),
                    reviewIndex = 0,
                    showReviewDialog = false,
                )
            }
            return
        }
        if (s.phase is DefragmenterPhase.Repairing) {
            // Stop the repair flow before flipping phase — the finally block
            // in kickOffRepair checks phase before chaining to Vacuuming, so
            // the order matters.
            repairJob?.cancel()
            repairJob = null
            _uiState.update {
                it.copy(
                    phase = DefragmenterPhase.Cancelled,
                    inFlight = emptyList(),
                    targetHighlights = IntArray(0),
                )
            }
            return
        }
        if (s.phase !is DefragmenterPhase.Defragmenting &&
            s.phase !is DefragmenterPhase.Paused &&
            s.phase !is DefragmenterPhase.Ready
        ) return
        _uiState.update {
            it.copy(
                phase = DefragmenterPhase.Cancelled,
                inFlight = emptyList(),
                targetHighlights = IntArray(0),
            )
        }
    }

    // endregion

    /**
     * Called once per frame by the screen while [DefragmenterPhase.Defragmenting].
     * Advances the simulation by ~dt seconds worth of moves, commits completed
     * in-flight moves, and updates timing state.
     */
    fun tick(frameTimeNanos: Long) {
        val state = _uiState.value
        if (state.phase !is DefragmenterPhase.Defragmenting) return
        if (state.grid === BlockGrid.EMPTY || state.totalBlocks == 0) return

        val dtSeconds: Float = if (lastTickNanos == 0L) {
            0f
        } else {
            ((frameTimeNanos - lastTickNanos).coerceAtLeast(0L) / 1_000_000_000f)
                .coerceAtMost(0.25f) // cap large jumps (e.g., after pause)
        }
        lastTickNanos = frameTimeNanos

        // 1) Commit any in-flight moves whose animation has completed.
        val grid = state.grid
        var commits = 0
        val remainingInFlight = ArrayList<InFlightMove>(state.inFlight.size)
        for (move in state.inFlight) {
            if (frameTimeNanos - move.startTimeNanos >= move.durationNanos) {
                grid.setFilled(move.fromIndex, false)
                grid.setFilled(move.toIndex, true)
                // Belt-and-suspenders for the source gate in nextFilledToMove:
                // if a coloured cell ever slips through, wipe its overlay code
                // here so the now-empty source can't keep painting an issue rect.
                val cs = state.cellStates
                if (move.fromIndex in 0 until cs.size) cs[move.fromIndex] = CELL_HEALTHY
                commits++
                animationCommits++
                // The gap (toIndex) represents a soft-deleted row. Fire the
                // real hard-delete POST against its backing fileId.
                val ref = gapMap[move.toIndex]
                if (ref != null) {
                    animationHardDeletes++
                    viewModelScope.launch {
                        source.hardDelete(ref.driveId, ref.fileId)
                    }
                }
            } else {
                remainingInFlight.add(move)
            }
        }

        // 2) Spawn new in-flight moves at a fixed rate.
        val movesPerSecond = BASE_MOVES_PER_SECOND
        smoothedMovesPerSecond = smoothedMovesPerSecond + 0.1f * (movesPerSecond - smoothedMovesPerSecond)
        pendingMoves += movesPerSecond * dtSeconds
        val mergedInFlight = remainingInFlight
        val durationNanos = ((1_000_000_000f / movesPerSecond) * MOVE_DURATION_FACTOR).toLong()
            .coerceAtLeast(16_000_000L) // at least one frame
        var spawnExhausted = false
        while (pendingMoves >= 1f && mergedInFlight.size < MAX_CONCURRENT_MOVES) {
            val fromIdx = nextFilledToMove(grid, state.cellStates)
            if (fromIdx < 0) {
                pendingMoves = 0f
                spawnExhausted = true
                break
            }
            val toIdx = nextGapBelow(fromIdx)
            if (toIdx < 0) {
                // No remaining gap sits below the current source block — every
                // surviving gap is past the last filled cell, so they're
                // unreachable soft-deletes that will be cleaned up on Complete.
                pendingMoves = 0f
                spawnExhausted = true
                break
            }
            // Preemptively clear fromIdx so the cached filled-blocks path stops
            // drawing it — the animated in-flight sprite takes over. We DO NOT
            // touch toIdx: the target must appear empty (dark) until the block
            // physically arrives on commit. nextGapBelow removes its return
            // value from the pool, so we won't re-pick the same target.
            grid.setFilled(fromIdx, false)
            mergedInFlight.add(
                InFlightMove(
                    id = ++moveIdCounter,
                    fromIndex = fromIdx,
                    toIndex = toIdx,
                    startTimeNanos = frameTimeNanos,
                    durationNanos = durationNanos,
                )
            )
            pendingMoves -= 1f
        }

        // User-visible gaps remaining: track independently of grid.gapCount
        // (which stays constant because each spawn flips fromIdx 1→0, and each
        // commit flips toIdx 0→1 — net zero). Subtract only on actual commits.
        val displayedGapsRemaining = state.gapsRemaining - commits

        val totalMoves = state.movesCompleted + commits
        val progressFraction = if (state.initialGaps > 0) {
            (totalMoves.toFloat() / state.initialGaps.toFloat()).coerceIn(0f, 1f)
        } else 1f

        val runMs = runStartMark?.elapsedNow()?.inWholeMilliseconds ?: 0L
        val newElapsed = elapsedMsAtPause + runMs
        val etaMs = if (smoothedMovesPerSecond > 0.01f) {
            (displayedGapsRemaining.toLong() * 1000L / smoothedMovesPerSecond.toLong()
                .coerceAtLeast(1L))
        } else 0L

        val targetHighlights = collectTargets(mergedInFlight)

        val noWorkLeft = displayedGapsRemaining <= 0 || spawnExhausted
        val reachedEnd = noWorkLeft && mergedInFlight.isEmpty()
        val phase = if (reachedEnd) {
            Logger.d(tag = tag) {
                "Animation complete: commits=$animationCommits hardDeletes=$animationHardDeletes " +
                        "remainingGapPool=${remainingGaps.size}"
            }
            // Hard-delete any soft-deleted files that never got animated
            // (gaps whose grid bit is still 0 at defrag end).
            cleanupUnreachableGaps(grid)
            // If the classifier turned up repair-eligible rows, run the
            // repair pass next so the user sees those cells flip colour
            // before the vacuum/green sweep covers everything.
            if (hasRepairTargets(state)) DefragmenterPhase.Repairing
            else DefragmenterPhase.Vacuuming
        } else state.phase

        _uiState.update {
            it.copy(
                phase = phase,
                grid = grid,
                gridVersion = if (commits > 0 || reachedEnd) it.gridVersion + 1 else it.gridVersion,
                inFlight = mergedInFlight,
                targetHighlights = targetHighlights,
                gapsRemaining = max(0, displayedGapsRemaining),
                movesCompleted = totalMoves,
                progressFraction = if (reachedEnd) 1f else progressFraction,
                elapsedMs = newElapsed,
                estRemainingMs = if (reachedEnd) 0L else etaMs,
            )
        }

        if (reachedEnd) {
            if (phase is DefragmenterPhase.Repairing) kickOffRepair()
            else kickOffVacuum()
        }
    }

    /**
     * Drive [DefragSource.repair] in the background. Each per-cell
     * [DefragRepairEvent.Repaired] flips the matching cell's state byte to
     * Healthy, decrements the issue tally, and bumps gridVersion so the
     * canvas redraws. A small inter-event delay paces the animation so the
     * user can see the colour transitions even when the underlying SQL
     * UPDATEs are sub-millisecond. When the flow terminates we hand off to
     * [kickOffVacuum] for the green finale.
     */
    private fun kickOffRepair() {
        repairJob?.cancel()
        repairJob = viewModelScope.launch {
            try {
                source.repair().collect { ev ->
                    when (ev) {
                        is DefragRepairEvent.Started -> Unit
                        is DefragRepairEvent.Repaired -> applyRepaired(ev)
                        is DefragRepairEvent.Done -> {
                            Logger.d(tag = tag) {
                                "Repair complete: analyzed=${ev.analyzed} " +
                                        "repaired=${ev.repaired} " +
                                        "(legacy=${ev.repairedLegacyUserDateZero} " +
                                        "archival=${ev.repairedSoftDeleteArchivalMismatch}) " +
                                        "skipped=${ev.skipped}"
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Logger.w(tag = tag, throwable = e) { "repair flow failed" }
            } finally {
                // Whether the flow completed normally or the user cancelled,
                // only proceed to vacuum if we're still in Repairing — Cancel
                // takes over to Cancelled and should NOT trigger vacuum.
                if (_uiState.value.phase is DefragmenterPhase.Repairing) {
                    kickOffVacuum()
                }
            }
        }
    }

    private suspend fun applyRepaired(ev: DefragRepairEvent.Repaired) {
        val state = _uiState.value
        val states = state.cellStates
        if (ev.position !in 0 until states.size) return
        states[ev.position] = CELL_HEALTHY
        _uiState.update {
            when (ev.kind) {
                DefragRepairEvent.RepairKind.LegacyUserDateZero -> it.copy(
                    gridVersion = it.gridVersion + 1,
                    issueCountLegacyUserDateZero =
                        (it.issueCountLegacyUserDateZero - 1).coerceAtLeast(0),
                )
                DefragRepairEvent.RepairKind.SoftDeleteArchivalMismatch -> it.copy(
                    gridVersion = it.gridVersion + 1,
                    issueCountArchivalMismatch =
                        (it.issueCountArchivalMismatch - 1).coerceAtLeast(0),
                )
                DefragRepairEvent.RepairKind.OrphanChatMessage -> it.copy(
                    gridVersion = it.gridVersion + 1,
                    issueCountOrphanMessage =
                        (it.issueCountOrphanMessage - 1).coerceAtLeast(0),
                )
                DefragRepairEvent.RepairKind.UnmappableConversation -> it.copy(
                    gridVersion = it.gridVersion + 1,
                    issueCountUnmappableConvo =
                        (it.issueCountUnmappableConvo - 1).coerceAtLeast(0),
                )
            }
        }
        // Pace the visual flip so it's visible even when SQL UPDATEs return
        // in microseconds. Skipped entirely on cancel — repairJob is cancelled.
        delay(REPAIR_PER_CELL_PACING_MS)
    }

    /**
     * Launch VACUUM in the background. The UI stays in [DefragmenterPhase.Vacuuming]
     * (blocks painted green) until vacuum returns, with a small floor so the
     * green celebration is visible on fast DBs.
     */
    private fun kickOffVacuum() {
        viewModelScope.launch {
            val mark = TimeSource.Monotonic.markNow()
            runCatching { source.vacuum() }
                .onFailure { Logger.w(tag = tag, throwable = it) { "vacuum failed" } }
            val elapsedMs = mark.elapsedNow().inWholeMilliseconds
            val floor = MIN_VACUUM_GREEN_MS - elapsedMs
            if (floor > 0) delay(floor)
            _uiState.update { it.copy(phase = DefragmenterPhase.Complete) }
        }
    }

    /** Shortcut path when Analyze finds zero gaps: still vacuum + paint green. */
    private fun enterVacuumAndComplete() {
        _uiState.update {
            it.copy(
                phase = DefragmenterPhase.Vacuuming,
                progressFraction = 1f,
                inFlight = emptyList(),
                targetHighlights = IntArray(0),
                estRemainingMs = 0L,
            )
        }
        kickOffVacuum()
    }

    private fun resetCursors() {
        nextFilledCursor = Int.MAX_VALUE
        remainingGaps.clear()
        remainingGaps.addAll(gapMap.keys)
        remainingGaps.shuffle()
    }

    // Pick a random remaining gap whose position is < upperExclusive (i.e.,
    // a productive target for the source block we're about to move). Returns
    // -1 when no remaining gap satisfies the bound — the spawn loop reads
    // that as "exhausted" and transitions to cleanup.
    private fun nextGapBelow(upperExclusive: Int): Int {
        val it = remainingGaps.iterator()
        while (it.hasNext()) {
            val pos = it.next()
            if (pos < upperExclusive) {
                it.remove()
                return pos
            }
        }
        return -1
    }

    // Pick the largest remaining HEALTHY filled block as the source for the
    // next compaction move. Bad-coded cells (LegacyUserDateZero, ArchivalMismatch,
    // CorruptJsonHeader, UnmappableConversation) are skipped: they get healed
    // in place by the repair phase, and moving them desyncs cellStates from
    // the visual grid (the colour-flip would land on an empty cell instead of
    // on the block that visually represents the row).
    private fun nextFilledToMove(grid: BlockGrid, cellStates: ByteArray): Int {
        var start = min(nextFilledCursor, grid.totalBlocks - 1)
        while (start >= 0) {
            val idx = grid.prevFilledFrom(start)
            if (idx < 0) return -1
            val healthy = idx >= cellStates.size || cellStates[idx] == CELL_HEALTHY
            if (healthy) {
                nextFilledCursor = idx - 1
                return idx
            }
            start = idx - 1
        }
        return -1
    }

    /**
     * Fire hard-deletes for gap positions that never got animated — i.e.
     * soft-deleted files already in the compacted tail when Analyze ran. These
     * are detected as `gapMap` entries whose grid bit is still 0 at Complete.
     */
    private fun cleanupUnreachableGaps(grid: BlockGrid) {
        if (gapMap.isEmpty()) return
        var count = 0
        for ((pos, ref) in gapMap) {
            if (pos in 0 until grid.totalBlocks && !grid.isFilled(pos)) {
                count++
                viewModelScope.launch {
                    source.hardDelete(ref.driveId, ref.fileId)
                }
            }
        }
        if (count > 0) {
            Logger.d(tag = tag) { "Cleanup: $count unreachable gap(s) hard-deleted" }
        }
    }

    private fun collectTargets(inFlight: List<InFlightMove>): IntArray {
        if (inFlight.isEmpty()) return IntArray(0)
        val arr = IntArray(inFlight.size)
        for (i in inFlight.indices) arr[i] = inFlight[i].toIndex
        return arr
    }

    // region Corrupt-file review walk
    //
    // The review queue is the concatenation of corruptHeaderCandidates +
    // corruptMessageCandidates, in that order. reviewIndex points into the
    // combined sequence; helpers below translate between index and queue
    // segment so the dialog can render the right kind of card.

    private fun reviewQueueSize(s: DefragmenterUiState): Int =
        s.corruptHeaderCandidates.size + s.corruptMessageCandidates.size

    private fun openReview() {
        val s = _uiState.value
        if (reviewQueueSize(s) == 0) return
        _uiState.update { it.copy(showReviewDialog = true, reviewIndex = 0) }
    }

    private fun dismissReview() {
        _uiState.update { it.copy(showReviewDialog = false) }
    }

    private fun reviewSkip() {
        advanceReviewIndex()
    }

    private fun reviewDelete() {
        val s = _uiState.value
        val driveId: Uuid
        val fileId: Uuid
        val isMessageContent: Boolean
        val headerSize = s.corruptHeaderCandidates.size
        when {
            s.reviewIndex < headerSize -> {
                val c = s.corruptHeaderCandidates[s.reviewIndex]
                driveId = c.driveId
                fileId = c.fileId
                isMessageContent = false
            }
            s.reviewIndex < headerSize + s.corruptMessageCandidates.size -> {
                val c = s.corruptMessageCandidates[s.reviewIndex - headerSize]
                driveId = c.driveId
                fileId = c.fileId
                isMessageContent = true
            }
            else -> return
        }
        viewModelScope.launch {
            val ok = runCatching { source.hardDelete(driveId, fileId) }
                .getOrElse {
                    Logger.w(tag = tag, throwable = it) {
                        "review hardDelete threw drive=$driveId file=$fileId"
                    }
                    false
                }
            if (!ok) {
                Logger.w(tag = tag) {
                    "review hardDelete returned false drive=$driveId file=$fileId — skipping"
                }
            }
        }
        // Best-effort cell-byte flip: scan cellStates for any position with the
        // matching code (we don't track fileId↔position) and clear the FIRST
        // one matching the candidate's kind. The next Analyze fully reconciles.
        val targetCode: Byte =
            if (isMessageContent) CELL_CORRUPT_MESSAGE_CONTENT else CELL_CORRUPT_JSON_HEADER
        val states = s.cellStates
        for (i in states.indices) {
            if (states[i] == targetCode) {
                states[i] = CELL_HEALTHY
                break
            }
        }
        _uiState.update {
            val tally = if (isMessageContent) {
                it.copy(
                    issueCountCorruptMessageContent =
                        (it.issueCountCorruptMessageContent - 1).coerceAtLeast(0),
                )
            } else {
                it.copy(
                    issueCountCorruptJson = (it.issueCountCorruptJson - 1).coerceAtLeast(0),
                )
            }
            tally.copy(gridVersion = it.gridVersion + 1)
        }
        advanceReviewIndex()
    }

    private fun advanceReviewIndex() {
        _uiState.update {
            val next = it.reviewIndex + 1
            if (next >= reviewQueueSize(it)) {
                it.copy(reviewIndex = 0, showReviewDialog = false)
            } else {
                it.copy(reviewIndex = next)
            }
        }
    }

    // endregion

    companion object {
        const val BASE_MOVES_PER_SECOND: Float = 6f
        const val MAX_CONCURRENT_MOVES: Int = 32
        // Move animation lasts ~1.5× the time between move spawns at the
        // current speed — blocks overlap in flight, giving a flowing feel.
        const val MOVE_DURATION_FACTOR: Float = 1.5f
        // Minimum time the "green + Vacuuming" finale is displayed, even if
        // VACUUM returns in under a second on tiny DBs.
        const val MIN_VACUUM_GREEN_MS: Long = 2_000L
        // Per-cell delay during the repair animation so the issue→healthy
        // colour flip is actually visible. The underlying SQL UPDATE is
        // sub-millisecond on small Ns; without pacing the user would see
        // every cell flip on the same frame.
        const val REPAIR_PER_CELL_PACING_MS: Long = 200L
    }
}
