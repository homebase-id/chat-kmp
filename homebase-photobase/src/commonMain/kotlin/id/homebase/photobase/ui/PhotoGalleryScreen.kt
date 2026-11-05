package id.homebase.photobase.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.client.KeyHeader
import id.homebase.chat.conversationlist.FullScreenOverlay
import id.homebase.chat.widget.FullScreenMediaViewer
import id.homebase.chat.widget.FullScreenVideoPlayer
import id.homebase.core.image.HomebaseImage
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.image.ImageSize
import id.homebase.photobase.gallery.PhotoGalleryItem
import id.homebase.resources.MR
import id.homebase.resources.photobase_backup_title
import id.homebase.resources.photobase_gallery_empty
import id.homebase.resources.photobase_gallery_load_failed
import id.homebase.resources.photobase_gallery_photo
import id.homebase.resources.photobase_gallery_refresh
import id.homebase.resources.photobase_gallery_retry
import id.homebase.resources.photobase_gallery_video
import id.homebase.resources.photobase_label
import org.jetbrains.compose.resources.stringResource

private const val LOAD_MORE_WHEN_WITHIN = 30

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PhotoGalleryScreen(viewModel: PhotoGalleryViewModel, onOpenBackupSettings: (() -> Unit)?) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val onAction = viewModel::onAction
    val fadeSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = uiState.viewer,
            contentKey = { it?.let { overlay -> overlay::class } },
            transitionSpec = { fadeIn(fadeSpec) togetherWith fadeOut(fadeSpec) },
        ) { viewer ->
            when (viewer) {
                is FullScreenOverlay.ViewMessageData -> FullScreenMediaViewer(
                    data = viewer,
                    onDismiss = { onAction(PhotoGalleryUiAction.CloseViewer) },
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this@AnimatedContent,
                )
                is FullScreenOverlay.VideoPlayerData -> FullScreenVideoPlayer(
                    data = viewer,
                    onDismiss = { onAction(PhotoGalleryUiAction.CloseViewer) },
                )
                else -> GalleryGrid(uiState, onAction, onOpenBackupSettings)
            }
        }
    }
}

@Composable
private fun GalleryGrid(
    uiState: PhotoGalleryUiState,
    onAction: (PhotoGalleryUiAction) -> Unit,
    onOpenBackupSettings: (() -> Unit)?,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(MR.string.photobase_label)) },
                actions = {
                    IconButton(onClick = { onAction(PhotoGalleryUiAction.Refresh) }, enabled = !uiState.isLoading) {
                        Icon(Icons.Outlined.Refresh, contentDescription = stringResource(MR.string.photobase_gallery_refresh))
                    }
                    if (onOpenBackupSettings != null) {
                        IconButton(onClick = onOpenBackupSettings) {
                            Icon(Icons.Outlined.Settings, contentDescription = stringResource(MR.string.photobase_backup_title))
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding),
        ) {
            when {
                uiState.days.isNotEmpty() -> PhotoGrid(uiState, onAction)
                uiState.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                uiState.loadFailed -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(MR.string.photobase_gallery_load_failed),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = { onAction(PhotoGalleryUiAction.Refresh) }) {
                        Text(stringResource(MR.string.photobase_gallery_retry))
                    }
                }
                else -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.PhotoLibrary,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(MR.string.photobase_gallery_empty),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun PhotoGrid(uiState: PhotoGalleryUiState, onAction: (PhotoGalleryUiAction) -> Unit) {
    val gridState = rememberLazyGridState()
    LaunchedEffect(gridState) {
        snapshotFlow {
            val info = gridState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - LOAD_MORE_WHEN_WITHIN
        }.collect { nearEnd -> if (nearEnd) onAction(PhotoGalleryUiAction.LoadMore) }
    }
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = 96.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        contentPadding = PaddingValues(4.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        for (day in uiState.days) {
            item(key = "day-${day.date}", span = { GridItemSpan(maxLineSpan) }, contentType = "day") {
                Text(
                    text = day.date.toString(),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
                )
            }
            items(day.items, key = { it.fileId.toString() }, contentType = { "photo" }) { item ->
                PhotoCell(item) { onAction(PhotoGalleryUiAction.Open(item)) }
            }
        }
        if (uiState.isLoadingMore) {
            item(key = "more", span = { GridItemSpan(maxLineSpan) }, contentType = "more") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
private fun PhotoCell(item: PhotoGalleryItem, onClick: () -> Unit) {
    val iv = item.payload.iv?.bytes
    val isVideo = item.payload.isVideo()
    val description = stringResource(if (isVideo) MR.string.photobase_gallery_video else MR.string.photobase_gallery_photo)
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onClick),
    ) {
        if (iv != null) {
            val imageData = remember(item.fileId, item.payload.key) {
                HomebaseImageData.from(
                    driveId = item.driveId,
                    fileId = item.fileId,
                    descriptor = item.payload,
                    previewThumbnail = item.tinyPreview ?: item.payload.previewThumbnail?.toEmbeddedThumb(),
                    requestedSize = ImageSize.THUMB_SMALL,
                    keyHeader = KeyHeader(iv = iv, aesKey = item.keyHeader.aesKey),
                )
            }
            HomebaseImage(
                imageData = imageData,
                contentDescription = description,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (isVideo) {
            Icon(
                imageVector = Icons.Filled.PlayCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.align(Alignment.Center).size(32.dp),
            )
        }
    }
}
