package id.homebase.feed.share

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.client.auth.OwnerSessionRepository
import id.homebase.api.util.truncateToCodePoints
import id.homebase.chat.services.convo.ConversationEnricher
import id.homebase.chat.services.convo.ConversationStream
import id.homebase.chat.services.convo.EnrichedConversationUiModel
import id.homebase.chat.services.convo.contact.ContactService
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.ConversationAvatar
import id.homebase.core.widget.StyledSearchTextField
import id.homebase.resources.MR
import id.homebase.resources.chat_search_placeholder
import id.homebase.resources.contacts
import id.homebase.resources.conversations_selected
import id.homebase.resources.groups
import id.homebase.resources.number_of_members
import id.homebase.resources.recents
import id.homebase.resources.sending_to_conversations
import id.homebase.resources.share_picker_next
import id.homebase.resources.share_picker_send
import id.homebase.resources.share_to
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val RECENTS_COUNT = 5

@OptIn(ExperimentalMaterial3Api::class, ExperimentalUuidApi::class, ExperimentalFoundationApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun SharePickerScreen(
    conversationStream: ConversationStream,
    contactService: ContactService,
    ownerSessionRepository: OwnerSessionRepository,
    sharedContent: SharedContent,
    hasFiles: Boolean,
    isSending: Boolean,
    onSendToConversations: (Set<Uuid>) -> Unit,
    onCancel: () -> Unit,
) {
    val conversationsData by conversationStream.conversations.collectAsStateWithLifecycle()
    val contactsState by contactService.contacts.collectAsStateWithLifecycle()
    val ownerSession by ownerSessionRepository.user.collectAsStateWithLifecycle()
    val searchFieldState = remember { TextFieldState() }
    var selectedIds by remember { mutableStateOf(emptySet<Uuid>()) }
    val enricher = remember { ConversationEnricher() }

    // Enrich conversations with contact names — same pattern as ConversationListViewModel
    val enrichedConversations by remember {
        derivedStateOf {
            val session = ownerSession ?: return@derivedStateOf emptyList()
            val contactMap = contactsState.associateBy { it.odinId }
            conversationsData.items.map { enricher.enrich(it, contactMap, session) }
        }
    }

    val searchQuery by remember {
        derivedStateOf { searchFieldState.text.toString() }
    }
    val isSearchActive by remember {
        derivedStateOf { searchQuery.isNotBlank() }
    }

    val filteredConversations by remember {
        derivedStateOf {
            if (!isSearchActive) {
                enrichedConversations
            } else {
                enrichedConversations.filter {
                    it.getDisplayName().contains(searchQuery, ignoreCase = true)
                }
            }
        }
    }

    val recents by remember {
        derivedStateOf {
            enrichedConversations
                .sortedByDescending { it.conversation.latestMessageTimestamp }
                .take(RECENTS_COUNT)
        }
    }

    val recentIds by remember {
        derivedStateOf { recents.map { it.conversation.id }.toSet() }
    }

    val contacts by remember {
        derivedStateOf {
            enrichedConversations
                .filter { !it.conversation.isGroupConversation && it.conversation.id !in recentIds }
                .sortedByDescending { it.conversation.latestMessageTimestamp }
                .distinctBy { it.getDisplayName().lowercase() }
                .sortedBy { it.getDisplayName().lowercase() }
        }
    }

    val groups by remember {
        derivedStateOf {
            enrichedConversations
                .filter { it.conversation.isGroupConversation && it.conversation.id !in recentIds }
                .sortedBy { it.getDisplayName().lowercase() }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(MR.string.share_to)) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel")
                    }
                },
            )
        },
        bottomBar = {
            if (selectedIds.isNotEmpty() && !isSending) {
                ShareSendBar(
                    count = selectedIds.size,
                    buttonText = if (hasFiles) stringResource(MR.string.share_picker_next) else stringResource(MR.string.share_picker_send),
                    onSend = { onSendToConversations(selectedIds) },
                )
            }
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Search bar
            StyledSearchTextField(
                textFieldState = searchFieldState,
                placeHolderText = stringResource(MR.string.chat_search_placeholder),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
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
                        Text(stringResource(MR.string.sending_to_conversations, selectedIds.size.toString()))
                    }
                }
            } else if (!conversationsData.dataReady) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else if (isSearchActive) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(
                        items = filteredConversations,
                        key = { it.conversation.id },
                    ) { enriched ->
                        ConversationPickerItem(
                            enriched = enriched,
                            isSelected = enriched.conversation.id in selectedIds,
                            onClick = {
                                selectedIds = if (enriched.conversation.id in selectedIds) {
                                    selectedIds - enriched.conversation.id
                                } else {
                                    selectedIds + enriched.conversation.id
                                }
                            },
                        )
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (recents.isNotEmpty()) {
                        stickyHeader(key = "header_recents") {
                            SectionHeader(stringResource(MR.string.recents))
                        }
                        items(
                            items = recents,
                            key = { "recent_${it.conversation.id}" },
                        ) { enriched ->
                            ConversationPickerItem(
                                enriched = enriched,
                                isSelected = enriched.conversation.id in selectedIds,
                                onClick = {
                                    selectedIds = if (enriched.conversation.id in selectedIds) {
                                        selectedIds - enriched.conversation.id
                                    } else {
                                        selectedIds + enriched.conversation.id
                                    }
                                },
                            )
                        }
                    }

                    if (contacts.isNotEmpty()) {
                        stickyHeader(key = "header_contacts") {
                            SectionHeader(stringResource(MR.string.contacts))
                        }
                        items(
                            items = contacts,
                            key = { "contact_${it.conversation.id}" },
                        ) { enriched ->
                            ConversationPickerItem(
                                enriched = enriched,
                                isSelected = enriched.conversation.id in selectedIds,
                                onClick = {
                                    selectedIds = if (enriched.conversation.id in selectedIds) {
                                        selectedIds - enriched.conversation.id
                                    } else {
                                        selectedIds + enriched.conversation.id
                                    }
                                },
                            )
                        }
                    }

                    if (groups.isNotEmpty()) {
                        stickyHeader(key = "header_groups") {
                            SectionHeader(stringResource(MR.string.groups))
                        }
                        items(
                            items = groups,
                            key = { "group_${it.conversation.id}" },
                        ) { enriched ->
                            ConversationPickerItem(
                                enriched = enriched,
                                isSelected = enriched.conversation.id in selectedIds,
                                onClick = {
                                    selectedIds = if (enriched.conversation.id in selectedIds) {
                                        selectedIds - enriched.conversation.id
                                    } else {
                                        selectedIds + enriched.conversation.id
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShareSendBar(
    count: Int,
    buttonText: String,
    onSend: () -> Unit,
) {
    Column {
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(MR.string.conversations_selected, count.toString()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(onClick = onSend) {
                Text(buttonText)
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun ConversationPickerItem(
    enriched: EnrichedConversationUiModel,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val conversation = enriched.conversation
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .then(
                if (isSelected) Modifier.background(
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                    RoundedCornerShape(8.dp)
                ) else Modifier
            )
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ConversationAvatar(
            avatarModel = conversation.avatarModel,
            options = AvatarOptions(size = 48.dp),
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = enriched.getDisplayName(),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (conversation.isGroupConversation) {
                Text(
                    text = stringResource(MR.string.number_of_members, conversation.participants.size.toString()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (isSelected) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp),
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
                text = sharedContent.text?.truncateToCodePoints(200) ?: "",
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
