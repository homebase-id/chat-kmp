package id.homebase.imageeditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.imageeditor.core.draw.BrushType
import id.homebase.imageeditor.ui.widget.BrushWidthSlider
import id.homebase.imageeditor.ui.widget.DrawBottomBar
import id.homebase.imageeditor.ui.widget.DrawGestureCallbacks
import id.homebase.imageeditor.ui.widget.DrawImageCanvas
import id.homebase.imageeditor.ui.widget.DrawStrokesOverlay
import id.homebase.imageeditor.ui.widget.DrawTopBar
import id.homebase.imageeditor.ui.widget.drawGestures
import org.jetbrains.compose.resources.stringResource

/**
 * Hosts the freehand draw editor. Mirrors [CropScreen]'s shape: dark-theme
 * Scaffold, top/bottom bars, full-bleed canvas + stroke overlay, single
 * navigation arg [DrawEditorViewModel.requestId] keying source bytes via
 * [DrawResultBus].
 */
@Composable
fun DrawScreen(
    viewModel: DrawEditorViewModel,
    onEvent: (DrawEditorUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.load()
    }

    LaunchedEffect(uiState.uiEvent) {
        uiState.uiEvent?.let {
            onEvent(it)
            viewModel.eventConsumed()
        }
    }

    HomebaseTheme(darkTheme = true) {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            topBar = {
                DrawTopBar(
                    onBack = { viewModel.onUiAction(DrawEditorUiAction.BackClicked) },
                    onSave = { viewModel.onUiAction(DrawEditorUiAction.SaveClicked) },
                    saveEnabled = !uiState.isLoading && !uiState.isSaving,
                )
            },
            bottomBar = {
                DrawBottomBar(
                    selectedBrush = uiState.selectedBrush,
                    canUndo = uiState.canUndo,
                    canRedo = uiState.canRedo,
                    colorPosition = uiState.colorPosition,
                    currentColorArgb = uiState.colorArgb,
                    onBrushSelected = { viewModel.onUiAction(DrawEditorUiAction.BrushSelected(it)) },
                    onColorPositionChange = { viewModel.onUiAction(DrawEditorUiAction.ColorChanged(it)) },
                    onUndo = { viewModel.onUiAction(DrawEditorUiAction.UndoClicked) },
                    onRedo = { viewModel.onUiAction(DrawEditorUiAction.RedoClicked) },
                    onReset = { viewModel.onUiAction(DrawEditorUiAction.ResetClicked) },
                )
            },
            containerColor = Color.Black,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator()
                } else if (uiState.errorMessageKey != null) {
                    Text(
                        text = stringResource(Res.string.draw_error_load),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                } else {
                    val callbacks = DrawGestureCallbacks(
                        onStrokeStart = { x, y -> viewModel.onStrokeStart(x, y) },
                        onStrokeExtend = { x, y -> viewModel.onStrokeExtend(x, y) },
                        onStrokeCommit = { viewModel.onStrokeCommit() },
                        onPan = { dx, dy -> viewModel.panViewport(dx, dy) },
                        onZoom = { scale, cx, cy -> viewModel.zoomViewport(scale, cx to cy) },
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .onSizeChanged { size ->
                                viewModel.setVisibleViewport(size.width.toFloat(), size.height.toFloat())
                            },
                    ) {
                        DrawImageCanvas(
                            bitmap = viewModel.previewBitmap,
                            snapshot = viewModel.snapshot,
                            modifier = Modifier.fillMaxSize(),
                        )
                        DrawStrokesOverlay(
                            snapshot = viewModel.snapshot,
                            modifier = Modifier
                                .fillMaxSize()
                                .drawGestures(callbacks),
                        )
                        BrushWidthSlider(
                            percent = thicknessSliderPositionForCurrentBrush(uiState),
                            onPercentChange = {
                                viewModel.onUiAction(DrawEditorUiAction.ThicknessSliderPositionChanged(it))
                            },
                            // No start padding — the slider's own offset puts
                            // its centerline on the canvas's left edge so
                            // half is visible at rest, matching Signal.
                            modifier = Modifier.align(Alignment.CenterStart),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Position (0..1) of the unified width slider for whichever brush is
 * active. Uses [BrushType.MIN_THICKNESS_FRACTION]..[BrushType.MAX_THICKNESS_FRACTION]
 * as the slider's full travel — both brushes share the same scale, with
 * each brush's last-set thickness restored on toggle.
 */
private fun thicknessSliderPositionForCurrentBrush(uiState: DrawEditorUiState): Float {
    val fraction = when (uiState.selectedBrush) {
        BrushType.Pen -> uiState.penThicknessFraction
        BrushType.Highlighter -> uiState.highlighterThicknessFraction
        BrushType.Blur -> uiState.blurThicknessFraction
    }
    val span = BrushType.MAX_THICKNESS_FRACTION - BrushType.MIN_THICKNESS_FRACTION
    return ((fraction - BrushType.MIN_THICKNESS_FRACTION) / span).coerceIn(0f, 1f)
}
