package id.homebase.chat.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichText
import id.homebase.api.common.OdinId
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.core.util.applyDefaultStyling
import id.homebase.core.util.formatTimestamp
import id.homebase.core.widget.AvatarImage
import kotlin.time.Instant

@Composable
fun ConversationItem(
    //conversation: ConversationUiModel,
    groupName: String,
    message: String,
    unreadCount: Int,
    avatarUrl: String,
    avatarInitials: String,
    contactOdinId: OdinId?,
    timestamp: Instant,
    onClick: () -> Unit,
    onContactClick: (odinId: OdinId) -> Unit,
    isSelected: Boolean = false,
) {
    val textState = RichTextState().applyDefaultStyling()
    textState.setMarkdown(message)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
                else MaterialTheme.colorScheme.surfaceContainerLow
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AvatarImage(
            avatarUrl = avatarUrl,
            avatarInitials = avatarInitials,
            onClick = {
                contactOdinId?.let {
                    onContactClick(it)
                }
            }
        )

        Spacer(modifier = Modifier.width(12.dp))

        // Content
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = groupName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (unreadCount > 0) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = formatTimestamp(timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (unreadCount > 0)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (unreadCount > 0) FontWeight.SemiBold else FontWeight.Normal
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                RichText(
                    state = textState,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                if (unreadCount > 0) {
                    Spacer(modifier = Modifier.width(8.dp))

                    Badge(
                        containerColor = HomebaseTheme.extendedColors.bubbleSentSurface,
                        contentColor = HomebaseTheme.extendedColors.bubbleSentOnSurface,
                    ) {
                        Text(
                            modifier = Modifier.padding(4.dp),
                            text = unreadCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ConversationAvatarItem(
    avatarUrl: String,
    avatarInitials: String,
    onClick: () -> Unit,
    isSelected: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
                else MaterialTheme.colorScheme.surfaceContainerLow
            )
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        AvatarImage(
            modifier = Modifier.padding(8.dp),
            avatarUrl = avatarUrl,
            avatarInitials = avatarInitials,
        )
    }
}