package id.homebase.core.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog


@Composable
fun EmojiSummary(
    list: List<EmojiReaction>,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        DialogCard(
            modifier = Modifier.height(300.dp),
            bottomPadding = 0.dp
        ) {

            val grouped = remember(list) {
                list.groupBy { it.emoji }
                    .map { (emoji, items) ->
                        TempEmojiSummary(
                            emoji = emoji,
                            users = items.map {
                                TempEmojiUser(
                                    emoji = emoji,
                                    user = it.odinId.toString() // placeholder display name
                                )
                            }
                        )
                    }
            }

            var selectedReaction by remember { mutableStateOf<String?>(null) }
            val totalCount = list.size

            // --- Horizontal Emoji Pills ---
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                item {
                    Surface(
                        shape = CircleShape,
                        color = if (selectedReaction == null)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.clickable { selectedReaction = null }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "All・", fontSize = 16.sp)
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = totalCount.toString(),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }

                items(grouped) { entry ->
                    val isSelected = selectedReaction == entry.emoji

                    Surface(
                        shape = CircleShape,
                        color = if (isSelected)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.clickable {
                            selectedReaction = entry.emoji
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = entry.emoji, fontSize = 16.sp)
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = entry.users.size.toString(),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            val visibleUsers = if (selectedReaction == null) {
                grouped.flatMap { it.users }
            } else {
                grouped.firstOrNull { it.emoji == selectedReaction }?.users ?: emptyList()
            }

            LazyColumn(modifier = Modifier.height(200.dp)) {
                items(visibleUsers) { user ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {

                        // Placeholder Avatar
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(Color.Gray, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = user.user.take(2).uppercase(),
                                color = Color.White,
                                fontSize = 12.sp
                            )
                        }

                        Spacer(Modifier.width(12.dp))

                        Text(
                            text = user.user,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )

                        Text(
                            text = user.emoji,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

data class TempEmojiUser(
    val emoji: String,
    val user: String,
)

data class TempEmojiSummary(
    val emoji: String,
    val users: List<TempEmojiUser>,
)
