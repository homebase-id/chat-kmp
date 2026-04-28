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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.imageeditor.core.PointF
import id.homebase.imageeditor.core.session.EditSession
import id.homebase.imageeditor.ui.widget.CropBottomBar
import id.homebase.imageeditor.ui.widget.CropImageCanvas
import id.homebase.imageeditor.ui.widget.CropOverlay
import id.homebase.imageeditor.ui.widget.CropTopBar
import id.homebase.imageeditor.ui.widget.RotationDial
import id.homebase.imageeditor.ui.widget.THUMB_HIT_RADIUS
import id.homebase.imageeditor.ui.widget.cropGestures
import id.homebase.imageeditor.ui.widget.rememberAnimatedSnapshot
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

    // Photo-editor convention: always render the cropper in dark mode so the
    // black canvas, white frame stroke, and toolbar icons read correctly,
    // regardless of the surrounding app's theme (which may be light).
    HomebaseTheme(darkTheme = true) {
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
                    aspectLocked = uiState.cropAspectLocked,
                    canUndo = uiState.canUndo,
                    canRedo = uiState.canRedo,
                    onAspectChange = { viewModel.onUiAction(CropEditorUiAction.AspectChanged(it)) },
                    onAspectLockToggle = { viewModel.onUiAction(CropEditorUiAction.AspectLockToggled) },
                    onRotate90 = { viewModel.onUiAction(CropEditorUiAction.Rotate90ClockwiseClicked) },
                    onFlipHorizontal = { viewModel.onUiAction(CropEditorUiAction.FlipHorizontalClicked) },
                    onReset = { viewModel.onUiAction(CropEditorUiAction.ResetClicked) },
                    onUndo = { viewModel.onUiAction(CropEditorUiAction.UndoClicked) },
                    onRedo = { viewModel.onUiAction(CropEditorUiAction.RedoClicked) },
                )
            }
        },
        containerColor = Color.Black,
        // contentColorFor(Color.Black) returns Unspecified (Color.Black isn't
        // a known scheme role), which falls through to the *outer* theme's
        // LocalContentColor — i.e. the surrounding app's light-theme text
        // color, on a black background. Pin it to the dark theme's onSurface
        // explicitly so IconButton (which reads LocalContentColor) renders
        // its icons visibly. AssistChip already reads MaterialTheme.onSurface
        // directly so it was unaffected.
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
                // Animate viewLocal smoothly so the post-release reflow after a
                // thumb drag commit doesn't read as a "snap-zoom". Hit-test must
                // use the *animated* (= visible) snapshot, otherwise during the
                // 250 ms reflow the visible corner sits at the interpolated
                // viewLocal while the hit zone is at the target — a touch on the
                // visible corner can miss the 36 dp radius and the drag is
                // mis-classified as an image transform. The drag handler itself
                // reads the live `model.hierarchy.*.localMatrix` (not the
                // snapshot), so using the animated copy here only affects which
                // corners are hittable, not the math that follows.
                val rawSnapshot = viewModel.matrixSnapshot
                val animatedSnapshot = rememberAnimatedSnapshot(rawSnapshot)
                val snapshotState = rememberUpdatedState(animatedSnapshot)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { size ->
                            viewModel.setVisibleViewport(size.width.toFloat(), size.height.toFloat())
                        },
                ) {
                    CropImageCanvas(
                        bitmap = viewModel.previewBitmap,
                        snapshot = animatedSnapshot,
                        modifier = Modifier.fillMaxSize(),
                    )
                    CropOverlay(
                        snapshot = animatedSnapshot,
                        showGrid = gridState.value,
                        modifier = Modifier
                            .fillMaxSize()
                            .cropGestures(
                                snapshotState = snapshotState,
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
}
