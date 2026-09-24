package id.homebase.chat.widget

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import org.jetbrains.compose.resources.stringResource

private const val POPOVER_COLUMNS = 4
private val TILE_WIDTH = 76.dp
private val TILE_BLOB_SIZE = 64.dp
private val TILE_REST_CORNER = TILE_BLOB_SIZE / 2
private val TILE_ACTIVE_CORNER = 22.dp

@Composable
internal fun AttachmentPopoverButton(
    actions: ImmutableList<AttachmentAction>?,
    alignToEnd: Boolean,
    onClick: () -> Unit,
    onPopoverDismissed: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    content: @Composable () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val anchor = remember { PopoverAnchor() }
    IconButton(
        onClick = if (actions == null) onClick else { { open = !open } },
        modifier = modifier.popoverAnchor(anchor),
        enabled = enabled,
        colors = colors,
    ) {
        content()
        if (open && actions != null) {
            ComposerPopover(
                anchor = anchor,
                alignToEnd = alignToEnd,
                onDismissRequest = {
                    open = false
                    onPopoverDismissed()
                },
            ) {
                AttachmentGrid(actions = actions, onPicked = { open = false })
            }
        }
    }
}

@Composable
private fun AttachmentGrid(
    actions: ImmutableList<AttachmentAction>,
    onPicked: () -> Unit,
) {
    val tones = with(MaterialTheme.colorScheme) {
        listOf(
            primaryContainer to onPrimaryContainer,
            secondaryContainer to onSecondaryContainer,
            tertiaryContainer to onTertiaryContainer,
        )
    }
    FlowRow(
        modifier = Modifier.verticalScroll(rememberScrollState()).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        maxItemsInEachRow = POPOVER_COLUMNS,
    ) {
        actions.forEachIndexed { index, action ->
            val (container, content) = tones[index % tones.size]
            AttachmentTile(action = action, container = container, content = content, onPicked = onPicked)
        }
    }
}

@Composable
private fun AttachmentTile(
    action: AttachmentAction,
    container: Color,
    content: Color,
    onPicked: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = when {
            pressed -> 0.9f
            hovered -> 1.06f
            else -> 1f
        },
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
    )
    val corner by animateDpAsState(
        targetValue = if (hovered || pressed) TILE_ACTIVE_CORNER else TILE_REST_CORNER,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
    )

    Column(
        modifier = Modifier
            .testTag(action.testTag)
            .width(TILE_WIDTH)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = {
                    onPicked()
                    action.onClick()
                },
            )
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(TILE_BLOB_SIZE)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    shape = RoundedCornerShape(corner)
                    clip = true
                }
                .background(container)
                .indication(interactionSource, ripple(color = content)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = action.icon,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(28.dp),
            )
        }
        Text(
            text = stringResource(action.label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
