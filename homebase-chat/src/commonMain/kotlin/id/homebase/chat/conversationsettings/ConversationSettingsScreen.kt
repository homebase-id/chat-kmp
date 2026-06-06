package id.homebase.chat.conversationsettings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.chat.widget.AvatarNameDisplay
import id.homebase.chat.widget.ChatMediaFullScreenHost
import id.homebase.chat.widget.ErrorInfoItem
import id.homebase.chat.widget.LoadingListItem
import id.homebase.chat.widget.MediaItem
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.ConversationAvatar
import id.homebase.core.config.chatTargetDrive
import id.homebase.core.image.ImageSize
import id.homebase.core.util.formatMediumDate
import id.homebase.resources.MR
import id.homebase.resources.contact_info_chatting_since
import id.homebase.resources.contact_info_messages_count
import id.homebase.resources.contact_info_messages_count_truncated
import id.homebase.resources.contact_info_recent_media
import id.homebase.resources.contact_info_summary_recent_note
import id.homebase.resources.conversation_groups_in_common
import id.homebase.resources.conversation_media_see_all
import id.homebase.resources.error_no_group_loaded
import id.homebase.resources.menu_back
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid

@Composable
fun ConversationSettingsScreen(
    viewModel: ConversationSettingsViewModel,
    onNavigateBack: () -> Unit,
    onSeeAllMedia: (conversationId: String) -> Unit,
    onOpenConversation: (conversationId: Uuid) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.uiEvent) {
        when (uiState.uiEvent) {
            is ConversationSettingsUiEvent.Back -> {
                viewModel.eventConsumed()
                onNavigateBack()
            }

            null -> {}
        }
    }

    ConversationSettingsUi(
        uiState = uiState,
        conversationId = viewModel.route.conversationId,
        onUiAction = viewModel::onUiAction,
        onSeeAllMedia = onSeeAllMedia,
        onOpenConversation = onOpenConversation,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationSettingsUi(
    uiState: ConversationSettingsUiState,
    conversationId: String,
    onUiAction: (ConversationSettingsUiAction) -> Unit,
    onSeeAllMedia: (conversationId: String) -> Unit,
    onOpenConversation: (conversationId: Uuid) -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var fullScreenItem by remember { mutableStateOf<SharedMediaItem?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = { onUiAction(ConversationSettingsUiAction.BackClicked)  }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(MR.string.menu_back)
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                if (uiState.conversation == null) {
                    if (uiState.isLoading) {
                        LoadingListItem()
                    } else {
                        ErrorInfoItem(stringResource(MR.string.error_no_group_loaded))
                    }
                }

                uiState.conversation?.let { conversation ->
                    val isWithSelf = conversation.isWithSelf
                    AvatarNameDisplay(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 16.dp),
                        displayName = if (isWithSelf) {
                            uiState.ownerSession?.displayName ?: conversation.name
                        } else {
                            conversation.name
                        },
                        avatarModel = conversation.avatarModel,
                        // No drill-in: the overview below already shows everything
                        // ContactInfo would, for this conversation.
                        onClick = null,
                    )

                    uiState.overview?.let { overview ->
                        OverviewSection(
                            overview = overview,
                            conversationTitle = conversation.name,
                            onMediaClick = { fullScreenItem = it },
                            onSeeAll = { onSeeAllMedia(conversationId) },
                        )
                    }

                    if (uiState.groupsInCommon.isNotEmpty()) {
                        GroupsInCommonSection(
                            groups = uiState.groupsInCommon,
                            onOpenConversation = onOpenConversation,
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }

            ChatMediaFullScreenHost(
                item = fullScreenItem,
                driveId = chatTargetDrive.alias,
                title = uiState.conversation?.name.orEmpty(),
                snackbarHostState = snackbarHostState,
                onDismiss = { fullScreenItem = null },
            )
        }
    }
}

@Composable
private fun OverviewSection(
    overview: ConversationOverview,
    conversationTitle: String,
    onMediaClick: (SharedMediaItem) -> Unit,
    onSeeAll: () -> Unit,
) {
    // Facts
    val messagesText =
        if (overview.isTruncated) {
            stringResource(MR.string.contact_info_messages_count_truncated, overview.totalMessages)
        } else {
            stringResource(MR.string.contact_info_messages_count, overview.totalMessages)
        }
    Text(
        text = messagesText,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        textAlign = TextAlign.Center,
    )
    overview.firstMessageDate?.let { date ->
        Text(
            text = stringResource(MR.string.contact_info_chatting_since, formatMediumDate(date)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            textAlign = TextAlign.Center,
        )
    }

    val hasAnything = overview.media.isNotEmpty() || overview.files.isNotEmpty() ||
            overview.audio.isNotEmpty() || overview.diceRolls.isNotEmpty() ||
            overview.locations.isNotEmpty()

    if (hasAnything) {
        Spacer(modifier = Modifier.height(20.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(MR.string.contact_info_recent_media),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onSeeAll) {
                Text(text = stringResource(MR.string.conversation_media_see_all))
            }
        }
    }

    if (overview.media.isNotEmpty()) {
        val strip = remember(overview.media) { overview.media.take(50) }
        Spacer(modifier = Modifier.height(4.dp))
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(strip) { item ->
                MediaItem(
                    payload = item.payload,
                    fileId = item.fileId,
                    driveId = chatTargetDrive.alias,
                    previewThumbnail = item.previewThumbnail,
                    keyHeader = item.keyHeader,
                    imageSize = ImageSize.THUMB_MEDIUM,
                    isSticker = item.isSticker,
                    modifier = Modifier.size(76.dp),
                    shape = RoundedCornerShape(12.dp),
                    onClick = { onMediaClick(item) },
                    sharedTransitionScope = null,
                    animatedVisibilityScope = null,
                )
            }
        }
    }

    if (overview.isTruncated) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(MR.string.contact_info_summary_recent_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun GroupsInCommonSection(
    groups: List<GroupInCommonItem>,
    onOpenConversation: (Uuid) -> Unit,
) {
    Spacer(modifier = Modifier.height(24.dp))
    Text(
        text = stringResource(MR.string.conversation_groups_in_common),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
    Spacer(modifier = Modifier.height(8.dp))
    groups.forEach { group ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenConversation(group.conversationId) }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ConversationAvatar(
                avatarModel = group.avatarModel,
                options = AvatarOptions(size = 40.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = group.name,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}
