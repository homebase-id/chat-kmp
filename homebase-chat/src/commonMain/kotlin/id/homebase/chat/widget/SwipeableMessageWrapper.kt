package id.homebase.chat.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import id.homebase.core.util.isMobile
import id.homebase.resources.MR
import id.homebase.resources.chat_message_reply
import id.homebase.resources.info
import org.jetbrains.compose.resources.stringResource

@Composable
fun SwipeableMessageWrapper(
    onSwipeRight: (() -> Unit)?,
    onSwipeLeft: (() -> Unit)?,
    enabled: Boolean = isMobile(),
    content: @Composable () -> Unit,
) {
    if (!enabled || (onSwipeRight == null && onSwipeLeft == null)) {
        content()
        return
    }

    SwipeRevealBox(
        onSwipeRight = onSwipeRight,
        onSwipeLeft = onSwipeLeft,
        modifier = Modifier.fillMaxWidth(),
        commitThreshold = SwipeDistance.Fixed(56.dp),
        maxOffset = SwipeDistance.Fixed(96.dp),
        reveal = { state ->
            val revealingReply = state.offsetPx > 0f
            if (if (revealingReply) onSwipeRight != null else onSwipeLeft != null) {
                Box(
                    modifier = Modifier
                        .align(if (revealingReply) AbsoluteAlignment.CenterLeft
                        else AbsoluteAlignment.CenterRight)
                        .padding(horizontal = 12.dp)
                        .size(32.dp)
                        .scale(0.5f + 0.5f * state.progress)
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerHighest,
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (revealingReply) Icons.AutoMirrored.Filled.Reply
                        else Icons.Outlined.Info,
                        contentDescription = stringResource(
                            if (revealingReply) MR.string.chat_message_reply else MR.string.info
                        ),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        content = content,
    )
}
