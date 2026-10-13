package id.homebase.chat.calling

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.resources.MR
import id.homebase.resources.call_decline
import id.homebase.resources.call_decline_from
import id.homebase.resources.call_incoming_video
import id.homebase.resources.call_incoming_voice
import id.homebase.resources.call_ring_accept_video
import id.homebase.resources.call_ring_accept_voice
import org.jetbrains.compose.resources.stringResource

val RingWindowWidth = 360.dp

/** The height at the default font size; the window takes its real height from the content, so large fonts grow it. */
val RingWindowHeight = 200.dp

const val RingAcceptKey = "A"
const val RingDeclineKey = "H"

@Composable
fun CallRingWindowContent(model: CallWindowModel, modifier: Modifier = Modifier) {
    val ui by model.uiState.collectAsStateWithLifecycle()
    val name = model.peerName(ui.peer)
    CallRingContent(
        video = ui.isVideoCall,
        name = name,
        avatar = { size -> ui.peer?.let { PeerAvatar(it, name, size) } },
        onAcceptVoice = model::acceptVoice,
        onAcceptVideo = model::acceptVideo,
        onDecline = model::decline,
        modifier = modifier,
        shape = LocalMiniWindowShape.current,
    )
}

private val RingAvatarSize = 48.dp

@Composable
fun CallRingContent(
    video: Boolean,
    name: String,
    avatar: @Composable (Dp) -> Unit,
    onAcceptVoice: () -> Unit,
    onAcceptVideo: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = scheme.surfaceContainerHigh,
        contentColor = scheme.onSurface,
        border = if (shape == RectangleShape) null else BorderStroke(1.dp, scheme.outlineVariant),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                Modifier.semantics(mergeDescendants = true) { heading() },
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(RingAvatarSize * 1.3f), contentAlignment = Alignment.Center) {
                    AvatarHalo(RingAvatarSize, HaloMode.Ringing)
                    avatar(RingAvatarSize)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        name,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (video) Icons.Default.Videocam else Icons.Default.Call, null, Modifier.size(16.dp), tint = scheme.primary)
                        Text(
                            stringResource(if (video) MR.string.call_incoming_video else MR.string.call_incoming_voice),
                            style = MaterialTheme.typography.labelLarge,
                            color = scheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
            }
            val acceptKeys = callShortcutKeys(RingAcceptKey, shift = true)
            val declineKeys = callShortcutKeys(RingDeclineKey, shift = true)
            val voiceLabel = stringResource(MR.string.call_ring_accept_voice)
            val videoLabel = stringResource(MR.string.call_ring_accept_video)
            val actions = listOf(
                RingActionSpec(
                    icon = Icons.Default.CallEnd,
                    label = stringResource(MR.string.call_decline),
                    tooltip = shortcutTip(stringResource(MR.string.call_decline), declineKeys),
                    description = shortcutTip(stringResource(MR.string.call_decline_from, name), declineKeys),
                    container = scheme.error,
                    content = scheme.onError,
                    onClick = onDecline,
                ),
                RingActionSpec(
                    icon = Icons.Default.Call,
                    label = voiceLabel,
                    tooltip = if (video) voiceLabel else shortcutTip(voiceLabel, acceptKeys),
                    container = if (video) scheme.secondaryContainer else scheme.primary,
                    content = if (video) scheme.onSecondaryContainer else scheme.onPrimary,
                    onClick = onAcceptVoice,
                ),
                RingActionSpec(
                    icon = Icons.Default.Videocam,
                    label = videoLabel,
                    tooltip = if (video) shortcutTip(videoLabel, acceptKeys) else videoLabel,
                    container = if (video) scheme.primary else scheme.secondaryContainer,
                    content = if (video) scheme.onPrimary else scheme.onSecondaryContainer,
                    onClick = onAcceptVideo,
                ),
            )
            val fontScale = LocalDensity.current.fontScale
            if (fontScale >= StackedFontScale) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    actions.forEach { RingAction(it, stacked = true, lines = 1, modifier = Modifier.fillMaxWidth()) }
                }
            } else {
                // Every label reserves the same line count, so a wrap at a large font never leaves one button taller than its neighbours.
                val lines = if (fontScale > 1f) 2 else 1
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    actions.forEach { RingAction(it, stacked = false, lines = lines, modifier = Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** Past this the three side-by-side labels break mid-word, so the actions stack into full-width rows. */
private const val StackedFontScale = 1.6f

private class RingActionSpec(
    val icon: ImageVector,
    val label: String,
    val tooltip: String,
    val container: Color,
    val content: Color,
    val onClick: () -> Unit,
    val description: String? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RingAction(spec: RingActionSpec, stacked: Boolean, lines: Int, modifier: Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(if (pressed) 12.dp else 20.dp)
    val tint = when {
        pressed -> spec.content.copy(alpha = 0.16f).compositeOver(spec.container)
        hovered -> spec.content.copy(alpha = 0.08f).compositeOver(spec.container)
        else -> spec.container
    }
    val label = @Composable {
        Text(
            spec.label,
            style = MaterialTheme.typography.labelLarge,
            color = spec.content,
            textAlign = TextAlign.Center,
            minLines = lines,
            maxLines = lines,
            overflow = TextOverflow.Ellipsis,
        )
    }
    // Row weight is parent data, so it has to sit outside the tooltip's own Box.
    Box(modifier) {
        BarTooltip(spec.tooltip, TooltipAnchorPosition.Above) {
            Surface(
                onClick = spec.onClick,
                shape = shape,
                color = tint,
                contentColor = spec.content,
                interactionSource = interaction,
                modifier = Modifier.fillMaxWidth().heightIn(min = if (stacked) 56.dp else 68.dp).buttonChrome(shape).semantics {
                    role = Role.Button
                    contentDescription = spec.description ?: spec.tooltip
                },
            ) {
                if (stacked) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(spec.icon, null, Modifier.size(24.dp))
                        label()
                    }
                } else {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                    ) {
                        Icon(spec.icon, null, Modifier.size(24.dp))
                        label()
                    }
                }
            }
        }
    }
}
