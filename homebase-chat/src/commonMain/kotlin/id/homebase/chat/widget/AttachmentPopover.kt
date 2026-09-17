package id.homebase.chat.widget

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.collections.immutable.ImmutableList
import org.jetbrains.compose.resources.stringResource

private const val PopoverColumns = 4
private val TileWidth = 76.dp
private val TileBlobSize = 64.dp
private val TileBlobRestCorner = TileBlobSize / 2
private val TileBlobActiveCorner = 22.dp

// MotionScheme.expressive() is internal in material3 1.9.0; these are its spring values.
private fun <T> expressiveDefaultSpatial(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.8f, stiffness = 380f)
private fun <T> expressiveFastSpatial(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.6f, stiffness = 800f)
private fun <T> expressiveDefaultEffects(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 1600f)
private fun <T> expressiveFastEffects(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 3800f)

@Composable
fun AttachmentPopover(
    expanded: Boolean,
    actions: ImmutableList<AttachmentAction>,
    onDismissRequest: () -> Unit,
) {
    val visibleState = remember { MutableTransitionState(false) }
    visibleState.targetState = expanded
    if (!visibleState.currentState && !visibleState.targetState) return

    Popup(
        popupPositionProvider = rememberAboveBubblePositionProvider(alignToEnd = true),
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true),
    ) {
        val origin = if (LocalLayoutDirection.current == LayoutDirection.Ltr) {
            TransformOrigin(1f, 1f)
        } else {
            TransformOrigin(0f, 1f)
        }
        AnimatedVisibility(
            visibleState = visibleState,
            modifier = Modifier.padding(bottom = 8.dp),
            enter = scaleIn(expressiveDefaultSpatial(), initialScale = 0.7f, transformOrigin = origin) +
                fadeIn(expressiveDefaultEffects()),
            exit = scaleOut(expressiveFastEffects(), targetScale = 0.9f, transformOrigin = origin) +
                fadeOut(expressiveFastEffects()),
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shadowElevation = 8.dp,
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    actions.chunked(PopoverColumns).forEachIndexed { rowIndex, row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            row.forEachIndexed { columnIndex, action ->
                                val tone = tileTone(rowIndex * PopoverColumns + columnIndex)
                                AttachmentTile(
                                    action = action,
                                    container = tone.first,
                                    content = tone.second,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun tileTone(index: Int): Pair<Color, Color> = with(MaterialTheme.colorScheme) {
    when (index % 5) {
        0 -> primaryContainer to onPrimaryContainer
        1 -> tertiaryContainer to onTertiaryContainer
        2 -> secondaryContainer to onSecondaryContainer
        3 -> primary to onPrimary
        else -> errorContainer to onErrorContainer
    }
}

@Composable
private fun AttachmentTile(
    action: AttachmentAction,
    container: Color,
    content: Color,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val corner: Dp by animateDpAsState(
        targetValue = if (hovered || pressed) TileBlobActiveCorner else TileBlobRestCorner,
        animationSpec = expressiveFastSpatial(),
    )
    val scale by animateFloatAsState(
        targetValue = when {
            pressed -> 0.9f
            hovered -> 1.06f
            else -> 1f
        },
        animationSpec = expressiveFastSpatial(),
    )
    val blobShape = RoundedCornerShape(corner)

    Column(
        modifier = Modifier
            .testTag(action.testTag)
            .width(TileWidth)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = action.onClick,
            )
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(TileBlobSize)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(blobShape)
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
