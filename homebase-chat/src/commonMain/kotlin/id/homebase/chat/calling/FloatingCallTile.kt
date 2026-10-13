package id.homebase.chat.calling

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import id.homebase.chat.services.calling.CallPhase
import id.homebase.chat.services.calling.RemoteVideoSize
import id.homebase.resources.MR
import id.homebase.resources.call_hang_up
import id.homebase.resources.call_mute
import id.homebase.resources.call_tile_description
import id.homebase.resources.call_unmute
import id.homebase.resources.call_you
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

internal val FloatingTileMargin = 12.dp

// Keeps the top app bar's actions, and the bottom bar or a chat composer, reachable under a resting tile.
internal val FloatingTileTopClearance = 64.dp
internal val FloatingTileBottomClearance = 88.dp

private val TileCorner = 20.dp
private val SelfCorner = 8.dp
private const val FlingProjectionSeconds = 0.15f
internal const val FloatingTileTag = "call-floating-tile"

/** Portrait unless the remote video is wider than tall, so the tile shows it whole without bars. */
internal fun floatingTileSize(remote: Pair<Int, Int>?): DpSize {
    val landscape = remote != null && remote.first > remote.second
    return if (landscape) DpSize(168.dp, 112.dp) else DpSize(112.dp, 168.dp)
}

/** The region a resting tile's top-left may sit in, after the system insets (status, navigation, cutout, keyboard) and the clearances. */
internal fun floatingTileArea(width: Float, height: Float, insetLeft: Float, insetTop: Float, insetRight: Float, insetBottom: Float): Rect =
    Rect(insetLeft, insetTop, width - insetRight, height - insetBottom)

/** Top-left positions of the four resting corners: top-left, top-right, bottom-left, bottom-right on screen. A region too small for the tile pins it top-left. */
internal fun floatingTileCorners(area: Rect, tile: Size): List<Offset> {
    val right = (area.right - tile.width).coerceAtLeast(area.left)
    val bottom = (area.bottom - tile.height).coerceAtLeast(area.top)
    return listOf(Offset(area.left, area.top), Offset(right, area.top), Offset(area.left, bottom), Offset(right, bottom))
}

/** The corner a tile let go at [position] with [velocity] (px/s) comes to rest in: the one nearest where the fling would carry it. */
internal fun floatingTileRestingCorner(corners: List<Offset>, position: Offset, velocity: Offset): Int {
    val projected = position + velocity * FlingProjectionSeconds
    return corners.indices.minBy { (corners[it] - projected).getDistance() }
}

/** The minimized video call: a draggable tile over the app that rests in a corner, and expands back to the call on tap. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun FloatingCallTile(
    ui: CallUiState,
    peerName: String,
    avatar: @Composable (Dp) -> Unit,
    onExpand: () -> Unit,
    onToggleMute: () -> Unit,
    onHangup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val remoteSize by RemoteVideoSize.current.collectAsState()
    val tileSize = floatingTileSize(remoteSize)
    val insets = WindowInsets.safeDrawing
    val scope = rememberCoroutineScope()
    val springSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Offset>()
    val description = stringResource(MR.string.call_tile_description, peerName)
    BoxWithConstraints(modifier.fillMaxSize()) {
        val corners = with(density) {
            val margin = FloatingTileMargin.toPx()
            floatingTileCorners(
                floatingTileArea(
                    width = constraints.maxWidth.toFloat(),
                    height = constraints.maxHeight.toFloat(),
                    insetLeft = insets.getLeft(this, LayoutDirection.Ltr) + margin,
                    insetTop = insets.getTop(this) + FloatingTileTopClearance.toPx(),
                    insetRight = insets.getRight(this, LayoutDirection.Ltr) + margin,
                    insetBottom = insets.getBottom(this) + FloatingTileBottomClearance.toPx(),
                ),
                Size(tileSize.width.toPx(), tileSize.height.toPx()),
            )
        }
        val currentCorners by rememberUpdatedState(corners)
        val maxPosition by rememberUpdatedState(with(density) { Offset(constraints.maxWidth - tileSize.width.toPx(), constraints.maxHeight - tileSize.height.toPx()) })
        var corner by rememberSaveable { mutableIntStateOf(if (rtl) 0 else 1) }
        val position = remember { Animatable(corners[corner], Offset.VectorConverter) }
        var drag by remember { mutableStateOf(Offset.Zero) }
        LaunchedEffect(corners[corner]) { if (drag == Offset.Zero) position.animateTo(corners[corner], springSpec) }
        val appear = remember { Animatable(0.8f) }
        val appearSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
        LaunchedEffect(Unit) { appear.animateTo(1f, appearSpec) }
        Surface(
            onClick = onExpand,
            modifier = Modifier
                .absoluteOffset { (position.value + drag).round() }
                .size(tileSize)
                .testTag(FloatingTileTag)
                .graphicsLayer {
                    scaleX = appear.value
                    scaleY = appear.value
                    alpha = ((appear.value - 0.8f) / 0.2f).coerceIn(0f, 1f)
                }
                .semantics { contentDescription = description }
                .pointerInput(Unit) {
                    val tracker = VelocityTracker()
                    fun settle(velocity: Offset) {
                        val landed = position.value + drag
                        val target = floatingTileRestingCorner(currentCorners, landed, velocity)
                        scope.launch {
                            position.snapTo(landed)
                            drag = Offset.Zero
                            corner = target
                            position.animateTo(currentCorners[target], springSpec, initialVelocity = velocity)
                        }
                    }
                    detectDragGestures(
                        onDragStart = {
                            tracker.resetTracking()
                            scope.launch { position.stop() }
                        },
                        onDragEnd = { tracker.calculateVelocity().let { settle(Offset(it.x, it.y)) } },
                        onDragCancel = { settle(Offset.Zero) },
                    ) { change, amount ->
                        change.consume()
                        val next = position.value + drag + amount
                        drag = Offset(next.x.coerceIn(0f, maxPosition.x.coerceAtLeast(0f)), next.y.coerceIn(0f, maxPosition.y.coerceAtLeast(0f))) - position.value
                        tracker.addPosition(change.uptimeMillis, position.value + drag)
                    }
                },
            shape = RoundedCornerShape(TileCorner),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shadowElevation = 8.dp,
        ) {
            Box(Modifier.fillMaxSize()) {
                if (ui.remoteShowing()) {
                    RemoteVideo(ui.videos.remote)
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { avatar(56.dp) }
                }
                val local = ui.videos.local
                if (local != null && ui.localVideo) {
                    val self = stringResource(MR.string.call_you)
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(6.dp).size(33.dp, 44.dp)
                            .clip(RoundedCornerShape(SelfCorner))
                            .semantics { contentDescription = self },
                    ) {
                        CallVideoSurface(local, Modifier.fillMaxSize(), local = true, cornerRadius = SelfCorner)
                    }
                }
                if (ui.phase == CallPhase.Reconnecting) {
                    Box(
                        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        LoadingIndicator(Modifier.size(40.dp), color = MaterialTheme.colorScheme.tertiary)
                    }
                }
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FilledIconButton(
                        onClick = onToggleMute,
                        modifier = Modifier.size(36.dp),
                        colors = if (ui.muted) {
                            IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.inverseSurface,
                                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                            )
                        } else {
                            IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.9f),
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            )
                        },
                    ) {
                        Icon(
                            if (ui.muted) Icons.Default.MicOff else Icons.Default.Mic,
                            stringResource(if (ui.muted) MR.string.call_unmute else MR.string.call_mute),
                            Modifier.size(18.dp),
                        )
                    }
                    FilledIconButton(
                        onClick = onHangup,
                        modifier = Modifier.size(36.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) {
                        Icon(Icons.Default.CallEnd, stringResource(MR.string.call_hang_up), Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}
