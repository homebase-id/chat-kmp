package id.homebase.core.ui.screens.defragmenter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.core.ui.screens.defragmenter.model.BlockGrid
import id.homebase.core.ui.screens.defragmenter.service.DefragAnalyzeEvent
import id.homebase.core.ui.screens.defragmenter.service.DefragSource
import id.homebase.core.ui.screens.defragmenter.service.DeletedFileRef
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
    private var nextGapCursor: Int = 0
    private var nextFilledCursor: Int = Int.MAX_VALUE
    private var moveIdCounter: Long = 0L
    private var smoothedMovesPerSecond: Float = BASE_MOVES_PER_SECOND

    private var analyzeJob: Job? = null

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
                )
            }
            val accGaps = HashMap<Int, DeletedFileRef>()
            var liveGrid: BlockGrid = BlockGrid.EMPTY
            source.analyze().collect { ev ->
                when (ev) {
                    is DefragAnalyzeEvent.Sized -> {
                        liveGrid = BlockGrid.createEmpty(ev.totalBlocks)
                        _uiState.update {
                            it.copy(
                                grid = liveGrid,
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
                        _uiState.update {
                            it.copy(
                                analyzedUpto = ev.analyzedUpto,
                                gridVersion = it.gridVersion + 1,
                            )
                        }
                    }

                    is DefragAnalyzeEvent.Done -> {
                        gapMap = accGaps
                        resetCursors()
                        val grid = liveGrid
                        val total = ev.totalBlocks
                        Logger.d(tag = tag) {
                            "Analyze complete: total=$total, gaps=${accGaps.size}"
                        }
                        _uiState.update {
                            it.copy(
                                phase = DefragmenterPhase.Ready,
                                grid = grid,
                                gridVersion = it.gridVersion + 1,
                                totalBlocks = total,
                                initialGaps = accGaps.size,
                                gapsRemaining = accGaps.size,
                                analyzedUpto = total,
                                analyzeTotal = total,
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
            // Nothing to animate — jump straight to vacuum+green finale.
            enterVacuumAndComplete()
            return
        }
        runStartMark = TimeSource.Monotonic.markNow()
        elapsedMsAtPause = 0L
        pendingMoves = 0f
        lastTickNanos = 0L
        smoothedMovesPerSecond = BASE_MOVES_PER_SECOND
        _uiState.update { it.copy(phase = DefragmenterPhase.Defragmenting) }
    }

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
                commits++
                // The gap (toIndex) represents a soft-deleted row. Fire the
                // real hard-delete POST against its backing fileId.
                val ref = gapMap[move.toIndex]
                if (ref != null) {
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
            val fromIdx = nextFilledToMove(grid)
            val toIdx = nextGapToFill(grid)
            if (fromIdx < 0 || toIdx < 0 || fromIdx <= toIdx) {
                // No more productive moves — the grid is already compacted or
                // every remaining gap is past the last filled block. Any gap
                // still in gapMap whose grid bit is 0 is an unreachable
                // soft-delete and will be cleaned up on Complete transition.
                pendingMoves = 0f
                spawnExhausted = true
                break
            }
            // Preemptively clear fromIdx so the cached filled-blocks path stops
            // drawing it — the animated in-flight sprite takes over. We DO NOT
            // touch toIdx: the target must appear empty (dark) until the block
            // physically arrives on commit. The cursor is monotonic so we
            // won't re-pick either index before this move completes.
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
            // Hard-delete any soft-deleted files that never got animated
            // (gaps whose grid bit is still 0 at defrag end).
            cleanupUnreachableGaps(grid)
            DefragmenterPhase.Vacuuming
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

        if (reachedEnd) kickOffVacuum()
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
        nextGapCursor = 0
        nextFilledCursor = Int.MAX_VALUE
    }

    private fun nextGapToFill(grid: BlockGrid): Int {
        val idx = grid.nextGapFrom(nextGapCursor)
        if (idx < 0) return -1
        nextGapCursor = idx + 1
        return idx
    }

    private fun nextFilledToMove(grid: BlockGrid): Int {
        val start = min(nextFilledCursor, grid.totalBlocks - 1)
        val idx = grid.prevFilledFrom(start)
        if (idx < 0) return -1
        nextFilledCursor = idx - 1
        return idx
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

    companion object {
        const val BASE_MOVES_PER_SECOND: Float = 6f
        const val MAX_CONCURRENT_MOVES: Int = 32
        // Move animation lasts ~1.5× the time between move spawns at the
        // current speed — blocks overlap in flight, giving a flowing feel.
        const val MOVE_DURATION_FACTOR: Float = 1.5f
        // Minimum time the "green + Vacuuming" finale is displayed, even if
        // VACUUM returns in under a second on tiny DBs.
        const val MIN_VACUUM_GREEN_MS: Long = 2_000L
    }
}
