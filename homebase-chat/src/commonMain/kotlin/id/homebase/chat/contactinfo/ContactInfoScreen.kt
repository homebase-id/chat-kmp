package id.homebase.chat.contactinfo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Poll
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.chat.widget.ErrorInfoItem
import id.homebase.chat.widget.LoadingListItem
import id.homebase.chat.widget.MediaItem
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.ContactAvatar
import id.homebase.core.config.chatTargetDrive
import id.homebase.core.image.ImageSize
import id.homebase.core.util.formatMediumDate
import id.homebase.resources.MR
import id.homebase.resources.contact_info_chatting_since
import id.homebase.resources.contact_info_messages_count
import id.homebase.resources.contact_info_messages_count_truncated
import id.homebase.resources.contact_info_recent_media
import id.homebase.resources.contact_info_stat_audio
import id.homebase.resources.contact_info_stat_dice
import id.homebase.resources.contact_info_stat_events
import id.homebase.resources.contact_info_stat_files
import id.homebase.resources.contact_info_stat_links
import id.homebase.resources.contact_info_stat_locations
import id.homebase.resources.contact_info_stat_photos
import id.homebase.resources.contact_info_stat_polls
import id.homebase.resources.contact_info_stat_stickers
import id.homebase.resources.contact_info_stat_videos
import id.homebase.resources.contact_info_summary_recent_note
import id.homebase.resources.error_no_contact_loaded
import id.homebase.resources.menu_back
import org.jetbrains.compose.resources.stringResource

@Composable
fun ContactInfoScreen(
    viewModel: ContactInfoViewModel,
    onNavigateBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    when (uiState.uiEvent) {
        is ContactInfoUiEvent.Back -> {
            viewModel.eventConsumed()
            onNavigateBack()
        }

        null -> {}
    }

    ContactInfoUi(
        uiState = uiState,
        onUiAction = viewModel::onUiAction
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactInfoUi(
    uiState: ContactInfoUiState,
    onUiAction: (ContactInfoUiAction) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = { onUiAction(ContactInfoUiAction.BackClicked) }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(MR.string.menu_back)
                        )
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (uiState.contact == null) {
                if (uiState.isLoading) {
                    LoadingListItem()
                } else {
                    ErrorInfoItem(stringResource(MR.string.error_no_contact_loaded))
                }
            }

            uiState.contact?.let { contact ->
                ContactAvatar(
                    odinId = contact.odinId,
                    profileImageData = null,
                    initials = contact.avatarInitials,
                    options = AvatarOptions(
                        size = 72.dp,
                        fontSize = 24.sp,
                    ),
                    sharedTransitionScope = null,
                    animatedVisibilityScope = null
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = contact.name,
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = contact.odinId.domainName,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            uiState.summary?.let { summary ->
                ChatSummarySection(summary)
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ChatSummarySection(summary: ChatSummaryUiModel) {
    Spacer(modifier = Modifier.height(20.dp))

    // Relationship facts
    val messagesText =
        if (summary.isTruncated) {
            stringResource(MR.string.contact_info_messages_count_truncated, summary.totalMessages)
        } else {
            stringResource(MR.string.contact_info_messages_count, summary.totalMessages)
        }
    Text(
        text = messagesText,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    summary.firstMessageDate?.let { date ->
        Text(
            text = stringResource(MR.string.contact_info_chatting_since, formatMediumDate(date)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }

    // Stat chips — only the categories that actually occur
    if (summary.hasAnyStat) {
        val stats = buildList {
            if (summary.photoCount > 0) {
                add(StatChipData(Icons.Default.Photo, summary.photoCount, stringResource(MR.string.contact_info_stat_photos)))
            }
            if (summary.stickerCount > 0) {
                add(StatChipData(Icons.Default.EmojiEmotions, summary.stickerCount, stringResource(MR.string.contact_info_stat_stickers)))
            }
            if (summary.videoCount > 0) {
                add(StatChipData(Icons.Default.Videocam, summary.videoCount, stringResource(MR.string.contact_info_stat_videos)))
            }
            if (summary.audioCount > 0) {
                add(StatChipData(Icons.Default.GraphicEq, summary.audioCount, stringResource(MR.string.contact_info_stat_audio)))
            }
            if (summary.fileCount > 0) {
                add(StatChipData(Icons.AutoMirrored.Filled.InsertDriveFile, summary.fileCount, stringResource(MR.string.contact_info_stat_files)))
            }
            if (summary.linkCount > 0) {
                add(StatChipData(Icons.Default.Link, summary.linkCount, stringResource(MR.string.contact_info_stat_links)))
            }
            if (summary.locationCount > 0) {
                add(StatChipData(Icons.Default.LocationOn, summary.locationCount, stringResource(MR.string.contact_info_stat_locations)))
            }
            if (summary.diceRollCount > 0) {
                add(StatChipData(Icons.Default.Casino, summary.diceRollCount, stringResource(MR.string.contact_info_stat_dice)))
            }
            if (summary.eventCount > 0) {
                add(StatChipData(Icons.Default.Event, summary.eventCount, stringResource(MR.string.contact_info_stat_events)))
            }
            if (summary.pollCount > 0) {
                add(StatChipData(Icons.Default.Poll, summary.pollCount, stringResource(MR.string.contact_info_stat_polls)))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        StatChipFlow(stats)

        if (summary.isTruncated) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(MR.string.contact_info_summary_recent_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }

    // Recent media strip
    if (summary.recentMedia.isNotEmpty()) {
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = stringResource(MR.string.contact_info_recent_media),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )
        Spacer(modifier = Modifier.height(8.dp))
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(summary.recentMedia) { item ->
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
                    sharedTransitionScope = null,
                    animatedVisibilityScope = null,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatChipFlow(stats: List<StatChipData>) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        stats.forEach { stat ->
            StatChip(icon = stat.icon, count = stat.count, label = stat.label)
        }
    }
}

@Composable
private fun StatChip(icon: ImageVector, count: Int, label: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private data class StatChipData(
    val icon: ImageVector,
    val count: Int,
    val label: String,
)
