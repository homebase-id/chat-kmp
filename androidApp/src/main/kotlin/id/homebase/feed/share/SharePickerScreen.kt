package id.homebase.feed.share

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import kotlin.uuid.ExperimentalUuidApi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.services.convo.ConversationStream
import kotlin.uuid.Uuid

@OptIn(ExperimentalMaterial3Api::class, ExperimentalUuidApi::class)
@Composable
fun SharePickerScreen(
    conversationStream: ConversationStream,
    sharedContent: SharedContent,
    isSending: Boolean,
    onConversationSelected: (Uuid) -> Unit,
    onCancel: () -> Unit,
) {
    val conversationsData by conversationStream.conversations.collectAsState()
    var searchQuery by remember { mutableStateOf("") }

    val filteredConversations = remember(conversationsData.items, searchQuery) {
        if (searchQuery.isBlank()) {
            conversationsData.items
        } else {
            conversationsData.items.filter {
                it.getDisplayName().contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Share to...") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel")
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Search bar
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search conversations...") },
                singleLine = true,
            )

            // Shared content preview
            SharedContentPreview(
                sharedContent = sharedContent,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            HorizontalDivider()

            if (isSending) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Sending...")
                    }
                }
            } else if (!conversationsData.dataReady) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(
                        items = filteredConversations,
                        key = { it.id },
                    ) { conversation ->
                        ConversationPickerItem(
                            conversation = conversation,
                            onClick = { onConversationSelected(conversation.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationPickerItem(
    conversation: ConversationUiModel,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Avatar initials circle
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = conversation.avatarInitials.take(2),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = conversation.getDisplayName(),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (conversation.isGroupConversation) {
                Text(
                    text = "${conversation.participants.size} members",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SharedContentPreview(
    sharedContent: SharedContent,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        if (sharedContent.hasText) {
            Text(
                text = sharedContent.text!!.take(200),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (sharedContent.hasFiles) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val fileCount = sharedContent.files.size
                val imageCount = sharedContent.files.count { it.mimeType.startsWith("image/") }
                val videoCount = sharedContent.files.count { it.mimeType.startsWith("video/") }
                val otherCount = fileCount - imageCount - videoCount

                val parts = mutableListOf<String>()
                if (imageCount > 0) parts.add("$imageCount image${if (imageCount > 1) "s" else ""}")
                if (videoCount > 0) parts.add("$videoCount video${if (videoCount > 1) "s" else ""}")
                if (otherCount > 0) parts.add("$otherCount file${if (otherCount > 1) "s" else ""}")

                Text(
                    text = parts.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
