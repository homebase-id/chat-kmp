package id.homebase.chat.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import id.homebase.resources.MR
import id.homebase.resources.chat_unknown_message_type
import id.homebase.resources.chat_unknown_message_type_diagnostic
import org.jetbrains.compose.resources.stringResource

/**
 * Fallback bubble for a message whose `appData.dataType` this build doesn't
 * recognize — typically a newer kind sent from a more up-to-date peer. Renders
 * a one-line "update the app" chip plus the dataType number in dimmer text so
 * the user has something concrete to mention if they file a report.
 */
@Composable
fun UnknownMessageBubble(
    dataType: Int,
    modifier: Modifier = Modifier,
) {
    val containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val contentColor = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(containerColor)
            .widthIn(min = 200.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
            contentDescription = null,
            tint = contentColor.copy(alpha = 0.7f),
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(MR.string.chat_unknown_message_type),
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor.copy(alpha = 0.85f),
            )
            Text(
                text = stringResource(MR.string.chat_unknown_message_type_diagnostic, dataType),
                style = MaterialTheme.typography.bodySmall,
                color = contentColor.copy(alpha = 0.55f),
            )
        }
    }
}
