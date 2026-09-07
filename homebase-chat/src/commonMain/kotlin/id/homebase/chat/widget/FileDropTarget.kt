package id.homebase.chat.widget

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import id.homebase.resources.MR
import id.homebase.resources.chat_drop_files_count
import id.homebase.resources.chat_drop_files_subtitle
import id.homebase.resources.chat_drop_files_title
import id.homebase.resources.chat_drop_folders_subtitle
import id.homebase.resources.chat_drop_folders_title
import id.homebase.resources.chat_drop_images_count
import io.github.vinceglb.filekit.PlatformFile
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Immutable
sealed interface FileDropPreview {
    /** Over the target, but this platform only surrenders the payload on release (X11 does this). */
    data object Unreadable : FileDropPreview

    data class Attachable(val total: Int, val images: Int) : FileDropPreview

    /** Readable, and nothing in it can be sent — folders, or entries we can't open. */
    data object Rejected : FileDropPreview
}

// Desktop only; every other target has no external file-drag source and returns Modifier unchanged.
@Composable
expect fun Modifier.fileDropTarget(
    enabled: Boolean,
    onDragPreviewChanged: (FileDropPreview?) -> Unit,
    onFilesDropped: (List<PlatformFile>) -> Unit,
): Modifier

@Composable
fun FileDropOverlay(preview: FileDropPreview?, modifier: Modifier = Modifier) {
    // Retained so the card keeps its copy through the exit fade, after the drag reports null.
    var shown by remember { mutableStateOf<FileDropPreview>(FileDropPreview.Unreadable) }
    if (preview != null && preview != shown) shown = preview

    val cardScale by animateFloatAsState(
        targetValue = if (preview != null) 1f else 0.92f,
        animationSpec = tween(durationMillis = 220, easing = EaseOutBack),
        label = "fileDropCardScale",
    )

    val rejected = shown is FileDropPreview.Rejected
    val accent = if (rejected) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    val boundaryShape = MaterialTheme.shapes.extraLarge

    AnimatedVisibility(
        visible = preview != null,
        enter = fadeIn(tween(durationMillis = 140, easing = LinearOutSlowInEasing)),
        exit = fadeOut(tween(durationMillis = 90, easing = FastOutLinearInEasing)),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)),
        ) {
            Box(
                modifier = Modifier.fillMaxSize()
                    .padding(24.dp)
                    .background(accent.copy(alpha = 0.07f), boundaryShape)
                    .dashedBoundary(accent, boundaryShape, 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                FileDropCard(
                    preview = shown,
                    modifier = Modifier.padding(24.dp).graphicsLayer {
                        scaleX = cardScale
                        scaleY = cardScale
                    },
                )
            }
        }
    }
}

@Composable
private fun FileDropCard(preview: FileDropPreview, modifier: Modifier = Modifier) {
    val rejected = preview is FileDropPreview.Rejected
    val badgeColor = if (rejected) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    val onBadgeColor = if (rejected) {
        MaterialTheme.colorScheme.onError
    } else {
        MaterialTheme.colorScheme.onPrimary
    }

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier.widthIn(min = 280.dp, max = 400.dp)
                .padding(horizontal = 32.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier.size(64.dp).background(badgeColor, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = preview.icon(),
                    contentDescription = null,
                    tint = onBadgeColor,
                    modifier = Modifier.size(30.dp),
                )
            }
            Text(
                text = stringResource(
                    if (rejected) MR.string.chat_drop_folders_title else MR.string.chat_drop_files_title
                ),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                text = preview.supportingText(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun FileDropPreview.icon(): ImageVector = when {
    this is FileDropPreview.Rejected -> Icons.Outlined.FolderOff
    this is FileDropPreview.Attachable && images == total -> Icons.Outlined.Image
    else -> Icons.Outlined.UploadFile
}

@Composable
private fun FileDropPreview.supportingText(): String = when {
    this is FileDropPreview.Rejected -> stringResource(MR.string.chat_drop_folders_subtitle)
    this is FileDropPreview.Attachable && images == total ->
        pluralStringResource(MR.plurals.chat_drop_images_count, total, total)
    this is FileDropPreview.Attachable ->
        pluralStringResource(MR.plurals.chat_drop_files_count, total, total)
    else -> stringResource(MR.string.chat_drop_files_subtitle)
}

private fun Modifier.dashedBoundary(color: Color, shape: Shape, width: Dp) = drawBehind {
    drawOutline(
        outline = shape.createOutline(size, layoutDirection, this),
        color = color,
        style = Stroke(
            width = width.toPx(),
            pathEffect = PathEffect.dashPathEffect(
                floatArrayOf(14.dp.toPx(), 10.dp.toPx()),
            ),
        ),
    )
}
