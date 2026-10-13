package id.homebase.chat.calling

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
val RingWindowHeight = 200.dp

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
        modifier = modifier.fillMaxSize(),
        shape = shape,
        color = scheme.surfaceContainerHigh,
        contentColor = scheme.onSurface,
        border = if (shape == RectangleShape) null else BorderStroke(1.dp, scheme.outlineVariant),
    ) {
        Column(
            Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
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
                        maxLines = 1,
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RingAction(
                    icon = Icons.Default.CallEnd,
                    label = stringResource(MR.string.call_decline),
                    description = stringResource(MR.string.call_decline_from, name),
                    container = endCallContainer(),
                    content = endCallContent(),
                    onClick = onDecline,
                    modifier = Modifier.weight(1f),
                )
                RingAction(
                    icon = Icons.Default.Call,
                    label = stringResource(MR.string.call_ring_accept_voice),
                    container = if (video) scheme.secondaryContainer else scheme.primary,
                    content = if (video) scheme.onSecondaryContainer else scheme.onPrimary,
                    onClick = onAcceptVoice,
                    modifier = Modifier.weight(1f),
                )
                RingAction(
                    icon = Icons.Default.Videocam,
                    label = stringResource(MR.string.call_ring_accept_video),
                    container = if (video) scheme.primary else scheme.secondaryContainer,
                    content = if (video) scheme.onPrimary else scheme.onSecondaryContainer,
                    onClick = onAcceptVideo,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun RingAction(
    icon: ImageVector,
    label: String,
    container: Color,
    content: Color,
    onClick: () -> Unit,
    modifier: Modifier,
    description: String? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(if (pressed) 12.dp else 20.dp)
    val tint = when {
        pressed -> content.copy(alpha = 0.16f).compositeOver(container)
        hovered -> content.copy(alpha = 0.08f).compositeOver(container)
        else -> container
    }
    Surface(
        onClick = onClick,
        shape = shape,
        color = tint,
        contentColor = content,
        interactionSource = interaction,
        modifier = modifier.height(68.dp).buttonChrome(shape).semantics {
            role = Role.Button
            if (description != null) contentDescription = description
        },
    ) {
        Column(
            Modifier.padding(horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        ) {
            Icon(icon, null, Modifier.size(24.dp))
            // The ring window has a fixed size, so large fonts shrink the label rather than cut it.
            BasicText(
                label,
                style = MaterialTheme.typography.labelLarge.copy(color = content),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = MaterialTheme.typography.labelLarge.fontSize),
            )
        }
    }
}
