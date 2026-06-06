package id.homebase.chat.conversationmedia

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.chat.conversationsettings.ConversationOverview
import id.homebase.chat.conversationsettings.DiceRollItem
import id.homebase.chat.conversationsettings.SharedMediaItem
import id.homebase.chat.widget.ChatMediaFullScreenHost
import id.homebase.chat.widget.LoadingListItem
import id.homebase.chat.widget.MediaItem
import id.homebase.chat.widget.rememberSharedMediaSaver
import id.homebase.core.config.chatTargetDrive
import id.homebase.core.image.ImageSize
import id.homebase.core.util.formatMediumDate
import id.homebase.resources.MR
import id.homebase.resources.conversation_media_album_title
import id.homebase.resources.conversation_media_empty
import id.homebase.resources.conversation_media_tab_audio
import id.homebase.resources.conversation_media_tab_dice
import id.homebase.resources.conversation_media_tab_files
import id.homebase.resources.conversation_media_tab_locations
import id.homebase.resources.conversation_media_tab_media
import id.homebase.resources.menu_back
import org.jetbrains.compose.resources.stringResource

private enum class MediaTab { MEDIA, FILES, AUDIO, DICE, LOCATIONS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationMediaScreen(
    viewModel: ConversationMediaViewModel,
    onNavigateBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var fullScreenItem by remember { mutableStateOf<SharedMediaItem?>(null) }
    var selectedTab by remember { mutableStateOf(MediaTab.MEDIA) }
    val saveItem = rememberSharedMediaSaver(chatTargetDrive.alias, snackbarHostState)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(MR.string.conversation_media_album_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(MR.string.menu_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            val overview = uiState.overview
            if (overview == null) {
                if (uiState.isLoading) LoadingListItem()
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    ScrollableTabRow(
                        selectedTabIndex = selectedTab.ordinal,
                        edgePadding = 0.dp,
                    ) {
                        Tab(
                            selected = selectedTab == MediaTab.MEDIA,
                            onClick = { selectedTab = MediaTab.MEDIA },
                            text = { Text(stringResource(MR.string.conversation_media_tab_media)) },
                        )
                        Tab(
                            selected = selectedTab == MediaTab.FILES,
                            onClick = { selectedTab = MediaTab.FILES },
                            text = { Text(stringResource(MR.string.conversation_media_tab_files)) },
                        )
                        Tab(
                            selected = selectedTab == MediaTab.AUDIO,
                            onClick = { selectedTab = MediaTab.AUDIO },
                            text = { Text(stringResource(MR.string.conversation_media_tab_audio)) },
                        )
                        Tab(
                            selected = selectedTab == MediaTab.DICE,
                            onClick = { selectedTab = MediaTab.DICE },
                            text = { Text(stringResource(MR.string.conversation_media_tab_dice)) },
                        )
                        Tab(
                            selected = selectedTab == MediaTab.LOCATIONS,
                            onClick = { selectedTab = MediaTab.LOCATIONS },
                            text = { Text(stringResource(MR.string.conversation_media_tab_locations)) },
                        )
                    }

                    when (selectedTab) {
                        MediaTab.MEDIA -> MediaGridTab(overview.media) { fullScreenItem = it }
                        MediaTab.FILES -> AttachmentListTab(overview.files, onClick = saveItem)
                        MediaTab.AUDIO -> AttachmentListTab(overview.audio, onClick = null)
                        MediaTab.DICE -> DiceTab(overview.diceRolls)
                        MediaTab.LOCATIONS -> MediaGridTab(overview.locations) { fullScreenItem = it }
                    }
                }
            }

            ChatMediaFullScreenHost(
                item = fullScreenItem,
                driveId = chatTargetDrive.alias,
                title = stringResource(MR.string.conversation_media_album_title),
                snackbarHostState = snackbarHostState,
                onDismiss = { fullScreenItem = null },
            )
        }
    }
}

@Composable
private fun MediaGridTab(items: List<SharedMediaItem>, onClick: (SharedMediaItem) -> Unit) {
    if (items.isEmpty()) {
        EmptyTab()
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 110.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(items) { item ->
            MediaItem(
                payload = item.payload,
                fileId = item.fileId,
                driveId = chatTargetDrive.alias,
                previewThumbnail = item.previewThumbnail,
                keyHeader = item.keyHeader,
                imageSize = ImageSize.THUMB_MEDIUM,
                isSticker = item.isSticker,
                modifier = Modifier.aspectRatio(1f),
                shape = RoundedCornerShape(8.dp),
                onClick = { onClick(item) },
                sharedTransitionScope = null,
                animatedVisibilityScope = null,
            )
        }
    }
}

@Composable
private fun AttachmentListTab(items: List<SharedMediaItem>, onClick: ((SharedMediaItem) -> Unit)?) {
    if (items.isEmpty()) {
        EmptyTab()
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items) { item ->
            MediaItem(
                payload = item.payload,
                fileId = item.fileId,
                driveId = chatTargetDrive.alias,
                previewThumbnail = item.previewThumbnail,
                keyHeader = item.keyHeader,
                modifier = Modifier.fillMaxWidth(),
                onClick = onClick?.let { { it(item) } },
                sharedTransitionScope = null,
                animatedVisibilityScope = null,
            )
        }
    }
}

@Composable
private fun DiceTab(items: List<DiceRollItem>) {
    if (items.isEmpty()) {
        EmptyTab()
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items) { item ->
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Casino,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 12.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = item.label, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = formatMediumDate(item.date),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyTab() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(MR.string.conversation_media_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
