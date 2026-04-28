package id.homebase.core.ui.screens.defragmenter

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.core.ui.screens.defragmenter.ui.DefragGridCanvas
import id.homebase.core.ui.screens.defragmenter.ui.Win98BeveledPanel
import id.homebase.core.ui.screens.defragmenter.ui.Win98Button
import id.homebase.core.ui.screens.defragmenter.ui.Win98Palette
import id.homebase.core.ui.screens.defragmenter.ui.Win98ProgressBar
import id.homebase.core.ui.screens.defragmenter.ui.Win98WindowFrame
import id.homebase.resources.MR
import id.homebase.resources.defragmenter_analyze
import id.homebase.resources.defragmenter_cancel
import id.homebase.resources.defragmenter_close
import id.homebase.resources.defragmenter_drive_label_all
import id.homebase.resources.defragmenter_grid_description
import id.homebase.resources.defragmenter_pause
import id.homebase.resources.defragmenter_resume
import id.homebase.resources.defragmenter_start
import id.homebase.resources.defragmenter_stat_elapsed
import id.homebase.resources.defragmenter_stat_eta
import id.homebase.resources.defragmenter_stat_gaps
import id.homebase.resources.defragmenter_stat_moves
import id.homebase.resources.defragmenter_stat_total
import id.homebase.resources.defragmenter_status_analyzing
import id.homebase.resources.defragmenter_status_cancelled
import id.homebase.resources.defragmenter_status_complete
import id.homebase.resources.defragmenter_status_idle
import id.homebase.resources.defragmenter_status_paused
import id.homebase.resources.defragmenter_status_ready
import id.homebase.resources.defragmenter_status_running
import id.homebase.resources.defragmenter_status_vacuuming
import id.homebase.resources.defragmenter_title_format
import org.jetbrains.compose.resources.stringResource

private const val CELEBRATORY_SECONDS: Float = 5f

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DefragmenterScreen(
    viewModel: DefragmenterViewModel,
    onClose: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isInspection = LocalInspectionMode.current

    // Consume one-shot events (Close).
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                DefragmenterUiEvent.CloseRequested -> onClose()
            }
        }
    }

    // Back = Close (mirrors the window X button).
    @Suppress("DEPRECATION")
    BackHandler(enabled = true) { viewModel.onAction(DefragmenterUiAction.Close) }

    // Per-frame tick while defragmenting. frameTimeNanos feeds the canvas so
    // in-flight sprites are rendered against the exact same clock the VM used.
    var frameTimeNanos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state.phase, isInspection) {
        if (state.phase is DefragmenterPhase.Defragmenting && !isInspection) {
            while (true) {
                val t = withFrameNanos { it }
                frameTimeNanos = t
                viewModel.tick(t)
            }
        }
    }

    // Celebratory sweep: when the defrag enters Vacuuming/Complete, paint all
    // blocks green progressively from left to right over CELEBRATORY_SECONDS.
    // The effect is keyed on `state.celebratory` so Vacuuming → Complete keeps
    // progress monotonic (no reset), while restart-on-Analyze wipes it.
    var celebratoryProgress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(state.celebratory, isInspection) {
        if (state.celebratory && !isInspection) {
            val startNanos = withFrameNanos { it }
            while (celebratoryProgress < 1f) {
                val now = withFrameNanos { it }
                val elapsedSec = (now - startNanos) / 1_000_000_000f
                celebratoryProgress = (elapsedSec / CELEBRATORY_SECONDS).coerceIn(0f, 1f)
            }
        } else {
            celebratoryProgress = 0f
        }
    }

    val driveLabel = stringResource(MR.string.defragmenter_drive_label_all)
    val title = stringResource(MR.string.defragmenter_title_format, driveLabel)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF2A6F9F))
            .padding(16.dp),
    ) {
        Win98WindowFrame(
            title = title,
            onClose = { viewModel.onAction(DefragmenterUiAction.Close) },
            modifier = Modifier.fillMaxSize(),
        ) {
            DefragmenterContent(
                state = state,
                driveLabel = driveLabel,
                frameTimeNanos = frameTimeNanos,
                celebratoryProgress = celebratoryProgress,
                onAction = viewModel::onAction,
            )
        }
    }
}

@Composable
private fun DefragmenterContent(
    state: DefragmenterUiState,
    driveLabel: String,
    frameTimeNanos: Long,
    celebratoryProgress: Float,
    onAction: (DefragmenterUiAction) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Win98BeveledPanel(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = true),
            sunken = true,
            background = Win98Palette.CanvasBackground,
        ) {
            val pct = (state.progressFraction * 100).toInt()
            val gridDescription = stringResource(
                MR.string.defragmenter_grid_description, driveLabel, pct,
            )
            DefragGridCanvas(
                grid = state.grid,
                gridVersion = state.gridVersion,
                inFlight = state.inFlight,
                targetHighlights = state.targetHighlights,
                frameTimeNanos = frameTimeNanos,
                celebratoryProgress = celebratoryProgress,
                analyzedUpto = if (state.phase is DefragmenterPhase.Analyzing)
                    state.analyzedUpto else Int.MAX_VALUE,
                scanHeadIndex = state.scanHeadIndex,
                cellStates = state.cellStates,
                modifier = Modifier
                    .fillMaxSize()
                    .semantics {
                        contentDescription = gridDescription
                        liveRegion = LiveRegionMode.Polite
                    },
            )
        }

        val barFraction = if (state.phase is DefragmenterPhase.Analyzing && state.analyzeTotal > 0) {
            state.analyzedUpto.toFloat() / state.analyzeTotal.toFloat()
        } else {
            state.progressFraction
        }
        Win98ProgressBar(
            fraction = barFraction,
            modifier = Modifier.fillMaxWidth(),
        )

        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val isWide = maxWidth >= 600.dp
            if (isWide) WideControls(state, onAction) else NarrowControls(state, onAction)
        }
    }
}

@Composable
private fun WideControls(state: DefragmenterUiState, onAction: (DefragmenterUiAction) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatsPanel(state = state, modifier = Modifier.weight(1f))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ActionButtons(state, onAction)
        }
    }
}

@Composable
private fun NarrowControls(state: DefragmenterUiState, onAction: (DefragmenterUiAction) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatsPanel(state = state)
        ActionButtons(state, onAction)
    }
}

@Composable
private fun StatsPanel(state: DefragmenterUiState, modifier: Modifier = Modifier) {
    val statusText = when (state.phase) {
        DefragmenterPhase.Idle -> stringResource(MR.string.defragmenter_status_idle)
        DefragmenterPhase.Analyzing -> stringResource(
            MR.string.defragmenter_status_analyzing,
            state.analyzedUpto,
            state.analyzeTotal,
        )
        DefragmenterPhase.Ready -> stringResource(MR.string.defragmenter_status_ready)
        DefragmenterPhase.Defragmenting -> stringResource(MR.string.defragmenter_status_running)
        DefragmenterPhase.Paused -> stringResource(MR.string.defragmenter_status_paused)
        DefragmenterPhase.Vacuuming -> stringResource(MR.string.defragmenter_status_vacuuming)
        DefragmenterPhase.Complete -> stringResource(MR.string.defragmenter_status_complete)
        DefragmenterPhase.Cancelled -> stringResource(MR.string.defragmenter_status_cancelled)
    }
    Win98BeveledPanel(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Win98Label(statusText, bold = true)
            Win98Label(stringResource(MR.string.defragmenter_stat_total, state.totalBlocks))
            Win98Label(stringResource(MR.string.defragmenter_stat_moves, state.movesCompleted))
            Win98Label(stringResource(MR.string.defragmenter_stat_gaps, state.gapsRemaining))
            Win98Label(
                stringResource(MR.string.defragmenter_stat_elapsed, formatDuration(state.elapsedMs))
            )
            Win98Label(
                stringResource(MR.string.defragmenter_stat_eta, formatDuration(state.estRemainingMs))
            )
            // Per-issue tallies + colour swatches so the user can map block
            // colour → meaning at a glance. Hidden when the count is zero so
            // the panel doesn't grow until issues actually exist.
            if (state.issueCountLegacyUserDateZero > 0) {
                Win98Label(
                    text = "Legacy userDate=0: ${state.issueCountLegacyUserDateZero}",
                    color = Win98Palette.IssueLegacyUserDateZeroFill,
                )
            }
            if (state.issueCountArchivalMismatch > 0) {
                Win98Label(
                    text = "Soft-delete drift: ${state.issueCountArchivalMismatch}",
                    color = Win98Palette.IssueArchivalMismatchFill,
                )
            }
            if (state.issueCountCorruptJson > 0) {
                Win98Label(
                    text = "Corrupt JSON: ${state.issueCountCorruptJson}",
                    color = Win98Palette.IssueCorruptJsonFill,
                )
            }
            if (state.issueCountUnmappableConvo > 0) {
                Win98Label(
                    text = "Unmappable conversation: ${state.issueCountUnmappableConvo}",
                    color = Win98Palette.IssueUnmappableConvoFill,
                )
            }
        }
    }
}

@Composable
private fun ActionButtons(state: DefragmenterUiState, onAction: (DefragmenterUiAction) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val phase = state.phase
        val isTerminalUi = phase is DefragmenterPhase.Complete ||
            phase is DefragmenterPhase.Cancelled ||
            phase is DefragmenterPhase.Vacuuming
        Win98Button(
            text = stringResource(MR.string.defragmenter_analyze),
            enabled = phase is DefragmenterPhase.Idle ||
                phase is DefragmenterPhase.Complete ||
                phase is DefragmenterPhase.Cancelled,
            onClick = { onAction(DefragmenterUiAction.Analyze) },
        )
        if (isTerminalUi) {
            // Defrag is done (or we're vacuuming) — only show Close.
            Win98Button(
                text = stringResource(MR.string.defragmenter_close),
                enabled = true,
                onClick = { onAction(DefragmenterUiAction.Close) },
            )
        } else {
            when (phase) {
                DefragmenterPhase.Paused -> Win98Button(
                    text = stringResource(MR.string.defragmenter_resume),
                    enabled = true,
                    onClick = { onAction(DefragmenterUiAction.Resume) },
                )
                DefragmenterPhase.Defragmenting -> Win98Button(
                    text = stringResource(MR.string.defragmenter_pause),
                    enabled = true,
                    onClick = { onAction(DefragmenterUiAction.Pause) },
                )
                else -> Win98Button(
                    text = stringResource(MR.string.defragmenter_start),
                    enabled = phase is DefragmenterPhase.Ready,
                    onClick = { onAction(DefragmenterUiAction.Start) },
                )
            }
            Win98Button(
                text = stringResource(MR.string.defragmenter_cancel),
                enabled = phase is DefragmenterPhase.Defragmenting ||
                    phase is DefragmenterPhase.Paused ||
                    phase is DefragmenterPhase.Analyzing,
                onClick = { onAction(DefragmenterUiAction.Cancel) },
            )
        }
    }
}

@Composable
private fun Win98Label(
    text: String,
    bold: Boolean = false,
    color: Color = Win98Palette.Black,
) {
    Text(
        text = text,
        style = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = color,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        ),
    )
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSec = ms / 1000
    val minutes = totalSec / 60
    val seconds = totalSec % 60
    val secStr = if (seconds < 10) "0$seconds" else seconds.toString()
    return "$minutes:$secStr"
}
