package id.homebase.chat.widget

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.homebase.api.common.OdinId
import id.homebase.api.util.markdownToPlainPreview
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.ContactAvatar
import id.homebase.core.avatars.FallbackAvatar
import id.homebase.core.util.formatTimestamp
import id.homebase.core.util.initials
import kotlin.time.Instant

@Composable
fun MessageSearchItem(
    memberName: String,
    message: String,
    contactOdinId: OdinId?,
    timestamp: Instant,
    onClick: () -> Unit,
    onContactClick: (odinId: OdinId) -> Unit,
    searchQuery: String = "",
) {
    // Search rows are a single line, so strip the markdown to plain text (the
    // shared AST walk, same grammar the renderer uses) and fold the search
    // highlight into that plain string. Both styling worlds share one
    // AnnotatedString, so the highlight offsets are computed against exactly the
    // string being drawn — the stale-offset crash class cannot occur.
    val highlightColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.35f)
    val previewText = remember(message, searchQuery, highlightColor) {
        val plain = markdownToPlainPreview(message, maxCodePoints = 200)
        buildSearchHighlightedText(
            plain = plain,
            searchQuery = searchQuery,
            highlightColor = highlightColor,
        ) ?: AnnotatedString(plain)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (contactOdinId != null) {
            ContactAvatar(
                odinId = contactOdinId,
                modifier = Modifier.padding(8.dp),
                profileImageData = null,
                initials = memberName.initials(),
                options = AvatarOptions(
                    onClick = {
                        onContactClick(contactOdinId)
                    }
                )
            )
        } else {
            FallbackAvatar(
                initials = memberName.initials(),
                options = AvatarOptions(),
            )
        }
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
                    text = memberName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = formatTimestamp(timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = previewText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
