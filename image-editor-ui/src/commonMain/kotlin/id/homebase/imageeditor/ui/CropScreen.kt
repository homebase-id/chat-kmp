package id.homebase.imageeditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import id.homebase.imageeditor.core.PointF
import id.homebase.imageeditor.core.session.EditSession
import id.homebase.imageeditor.ui.widget.CropBottomBar
import id.homebase.imageeditor.ui.widget.CropImageCanvas
import id.homebase.imageeditor.ui.widget.CropOverlay
import id.homebase.imageeditor.ui.widget.CropTopBar
import id.homebase.imageeditor.ui.widget.RotationDial
import id.homebase.imageeditor.ui.widget.THUMB_HIT_RADIUS
import id.homebase.imageeditor.ui.widget.cropGestures
import id.homebase.imageeditor.ui.widget.rememberCropGridState
import id.homebase.imageeditor.ui.widget.CropGestureCallbacks
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid

/**
 * Hosts the cropper editor. Drives [CropEditorViewModel] from a single
 * navigation argument [requestId] which keys the source bytes via
 * [CropResultBus]. The screen emits [CropEditorUiEvent.CropConfirmed] /
 * [CropEditorUiEvent.Cancelled] to its caller via [onEvent].
 */
@Composable
fun CropScreen(
    viewModel: CropEditorViewModel,
    onEvent: (CropEditorUiEvent) -> Unit,
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

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CropTopBar(
                onBack = { viewModel.onUiAction(CropEditorUiAction.BackClicked) },
                onSave = { viewModel.onUiAction(CropEditorUiAction.SaveClicked) },
                saveEnabled = !uiState.isLoading && !uiState.isSaving,
            )
        },
        bottomBar = {
            Column {
                RotationDial(
                    valueDegrees = uiState.freeRotationDegrees,
                    onValueChange = { viewModel.onUiAction(CropEditorUiAction.FreeRotationChanged(it)) },
                    onRelease = { viewModel.onUiAction(CropEditorUiAction.FreeRotationReleased) },
                    label = stringResource(Res.string.crop_rotation_dial_label),
                )
                CropBottomBar(
                    aspectMode = uiState.aspectMode,
                    canUndo = uiState.canUndo,
                    canRedo = uiState.canRedo,
                    onAspectChange = { viewModel.onUiAction(CropEditorUiAction.AspectChanged(it)) },
                    onRotate90 = { viewModel.onUiAction(CropEditorUiAction.Rotate90ClockwiseClicked) },
                    onReset = { viewModel.onUiAction(CropEditorUiAction.ResetClicked) },
                    onUndo = { viewModel.onUiAction(CropEditorUiAction.UndoClicked) },
                    onRedo = { viewModel.onUiAction(CropEditorUiAction.RedoClicked) },
                )
            }
        },
        containerColor = Color.Black,
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
                    text = stringResource(Res.string.crop_error_load),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            } else {
                val gridState = rememberCropGridState()
                val callbacks = CropGestureCallbacks(
                    onThumbDragStart = { cp, pos -> viewModel.startThumbDrag(cp, pos) },
                    onThumbDragMove = { session, pos -> viewModel.onThumbDragMove(session, pos) },
                    onThumbDragCommit = { _ -> viewModel.commitThumbGesture() },
                    onPanImage = { dx, dy -> viewModel.panMainImage(dx, dy) },
                    onZoomImage = { scale, cx, cy -> viewModel.zoomMainImage(scale, cx to cy) },
                    onCommitImage = { viewModel.commitMainImageGesture() },
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { size ->
                            viewModel.setVisibleViewport(size.width.toFloat(), size.height.toFloat())
                        },
                ) {
                    CropImageCanvas(
                        bitmap = viewModel.previewBitmap,
                        snapshot = viewModel.matrixSnapshot,
                        modifier = Modifier.fillMaxSize(),
                    )
                    CropOverlay(
                        snapshot = viewModel.matrixSnapshot,
                        showGrid = gridState.value,
                        modifier = Modifier
                            .fillMaxSize()
                            .cropGestures(
                                snapshot = viewModel.matrixSnapshot,
                                thumbHitRadius = THUMB_HIT_RADIUS,
                                callbacks = callbacks,
                                isShowingGridState = gridState,
                            ),
                    )
                }
            }
        }
    }
}
