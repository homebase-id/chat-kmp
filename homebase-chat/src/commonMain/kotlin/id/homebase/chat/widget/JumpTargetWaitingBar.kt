package id.homebase.chat.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.homebase.resources.MR
import id.homebase.resources.conversation_jump_waiting_for_message
import org.jetbrains.compose.resources.stringResource

/**
 * Shown while a notification-tap jump is waiting for its message to sync in (#1158).
 *
 * The conversation underneath stays fully usable at the latest page — this bar is the
 * only sign that a jump is still pending, and it replaces the old toast that asserted a
 * deletion nobody had verified. It disappears when the message lands (the list jumps to
 * it) or when the wait gives up (an honest snackbar takes over).
 */
@Composable
fun JumpTargetWaitingBar(
    isWaiting: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!isWaiting) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = stringResource(MR.string.conversation_jump_waiting_for_message),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
