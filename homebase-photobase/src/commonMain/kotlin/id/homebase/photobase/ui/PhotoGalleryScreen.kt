package id.homebase.photobase.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.client.KeyHeader
import id.homebase.core.HomebaseConstants
import id.homebase.chat.conversationlist.FullScreenOverlay
import id.homebase.chat.widget.FullScreenMediaViewer
import id.homebase.chat.widget.FullScreenVideoPlayer
import id.homebase.core.image.HomebaseImage
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.image.ImageSize
import id.homebase.core.media.subsample.fullScreenImageSharedBounds
import id.homebase.photobase.gallery.PhotoGalleryDay
import id.homebase.photobase.gallery.PhotoGalleryItem
import id.homebase.core.util.formatMomentDate
import id.homebase.resources.MR
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import id.homebase.resources.photobase_backup_title
import id.homebase.resources.photobase_gallery_empty
import id.homebase.resources.photobase_gallery_load_failed
import id.homebase.resources.photobase_gallery_photo
import id.homebase.resources.photobase_gallery_refresh
import id.homebase.resources.photobase_gallery_sort_newest
import id.homebase.resources.photobase_gallery_sort_oldest
import id.homebase.resources.photobase_gallery_retry
import id.homebase.resources.photobase_gallery_video
import id.homebase.resources.photobase_label
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid

// Under what's left of the first page after a screenful, or opening would fetch the next one straight away.
private const val LOAD_MORE_WHEN_WITHIN = 15

private const val SETTLE_MILLIS = 150L

// A few swipes' warning, so the next page is usually there before the last photo is reached.
private const val VIEWER_LOAD_MORE_WHEN_WITHIN = 5

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PhotoGalleryScreen(
    viewModel: PhotoGalleryViewModel,
    onOpenBackupSettings: (() -> Unit)?,
    backupIndicator: @Composable () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val gridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }
    val memory = remember(gridState) { GridMemory(gridState) }
    val onAction: (PhotoGalleryUiAction) -> Unit = remember(viewModel) {
        { action ->
            if (action is PhotoGalleryUiAction.CloseViewer) memory.returnTo = viewModel.uiState.value.openedFileId
            viewModel.onAction(action)
        }
    }
    // On the shared-bounds clock, or the tile and viewer fade out of step with the morph.
    val fadeSpec = tween<Float>(HomebaseConstants.Animation.CHAT_IMAGE_FULL_SCREEN_TRANSITION_DURATION)
    SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = uiState.openedFileId != null,
            transitionSpec = { fadeIn(fadeSpec) togetherWith fadeOut(fadeSpec) },
        ) { viewing ->
            if (viewing) {
                GalleryViewer(uiState, onAction, this@SharedTransitionLayout, this@AnimatedContent)
            } else {
                GalleryGrid(uiState, onAction, onOpenBackupSettings, backupIndicator, memory, this@SharedTransitionLayout, this@AnimatedContent)
            }
        }
    }
}

/** Outlives the grid while the viewer covers it. */
private class GridMemory(val state: LazyGridState) {
    var newestFirst: Boolean? = null
    var topId: Uuid? = null
    var returnTo: Uuid? = null
}

private fun gridIndexOf(days: List<PhotoGalleryDay>, fileId: Uuid): Int? {
    var index = 0
    for (day in days) {
        index++
        for (item in day.items) {
            if (item.fileId == fileId) return index
            index++
        }
    }
    return null
}

/** Every photo loaded so far, in the grid's order, one per page; nearing the end loads the next page. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun GalleryViewer(
    uiState: PhotoGalleryUiState,
    onAction: (PhotoGalleryUiAction) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    val items = remember(uiState.days) { uiState.days.flatMap { it.items } }
    val openedFileId by rememberUpdatedState(uiState.openedFileId)
    val initialPage = remember { items.indexOfFirst { it.fileId == uiState.openedFileId }.coerceAtLeast(0) }
    val pagerState = rememberPagerState(initialPage) { items.size }
    val close = { onAction(PhotoGalleryUiAction.CloseViewer) }
    BackHandler(onBack = close)
    val showUI = remember { mutableStateOf(true) }
    LaunchedEffect(items) {
        // Photos arriving above the open one would otherwise leave the pager on a different photo.
        // Against the settled page, not the current one: mid-swipe, current is already the neighbour.
        val opened = items.indexOfFirst { it.fileId == openedFileId }
        if (opened < 0) {
            close()
            return@LaunchedEffect
        }
        if (opened != pagerState.settledPage) pagerState.scrollToPage(opened)
        snapshotFlow { pagerState.settledPage }.collect { page ->
            val item = items.getOrNull(page) ?: return@collect
            if (item.fileId != openedFileId) onAction(PhotoGalleryUiAction.Open(item))
            if (page >= items.size - VIEWER_LOAD_MORE_WHEN_WITHIN) onAction(PhotoGalleryUiAction.LoadMore)
        }
    }
    val shown = items.getOrNull(pagerState.settledPage)
    HorizontalPager(
        state = pagerState,
        key = { items[it].fileId.toString() },
        beyondViewportPageCount = 1,
        pageSpacing = 24.dp,
        // Around the pager, not inside a page: from inside a page the open animation restarts mid-flight and jumps.
        modifier = Modifier.fillMaxSize().fullScreenImageSharedBounds(
            shown?.let { "image-${it.fileId}-${it.payload.key}" },
            sharedTransitionScope,
            animatedVisibilityScope,
        ),
    ) { page ->
        val item = items[page]
        val settled = page == pagerState.settledPage
        when (val viewer = remember(item) { item.toViewer() }) {
            is FullScreenOverlay.ViewMessageData -> FullScreenMediaViewer(
                data = viewer,
                onDismiss = close,
                sharedTransitionScope = null,
                animatedVisibilityScope = null,
                showUIState = showUI,
                isFocused = settled,
            )
            // A player plays as soon as it's composed, so a neighbour shows its still until swiped to.
            is FullScreenOverlay.VideoPlayerData -> if (settled) {
                FullScreenVideoPlayer(data = viewer, onDismiss = close)
            } else {
                VideoStill(item)
            }
            else -> Unit
        }
    }
}

@Composable
private fun VideoStill(item: PhotoGalleryItem) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
        item.tileImageData()?.let { imageData ->
            HomebaseImage(
                imageData = imageData,
                contentDescription = stringResource(MR.string.photobase_gallery_video),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Icon(
            imageVector = Icons.Filled.PlayCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.inverseOnSurface,
            modifier = Modifier.size(64.dp),
        )
    }
}

private fun PhotoGalleryItem.tileImageData(): HomebaseImageData? {
    val iv = payload.iv?.bytes ?: return null
    return HomebaseImageData.from(
        driveId = driveId,
        fileId = fileId,
        descriptor = payload,
        previewThumbnail = tinyPreview ?: payload.previewThumbnail?.toEmbeddedThumb(),
        requestedSize = ImageSize.THUMB_SMALL,
        thumbSizeLimit = ImageSize.THUMB_SMALL,
        keyHeader = KeyHeader(iv = iv, aesKey = keyHeader.aesKey),
    )
}

@Composable
private fun GalleryGrid(
    uiState: PhotoGalleryUiState,
    onAction: (PhotoGalleryUiAction) -> Unit,
    onOpenBackupSettings: (() -> Unit)?,
    backupIndicator: @Composable () -> Unit,
    memory: GridMemory,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(MR.string.photobase_label)) },
                actions = {
                    backupIndicator()
                    IconButton(onClick = { onAction(PhotoGalleryUiAction.ToggleSort) }, enabled = !uiState.isLoading) {
                        Icon(
                            Icons.Outlined.SwapVert,
                            contentDescription = stringResource(
                                if (uiState.newestFirst) MR.string.photobase_gallery_sort_newest else MR.string.photobase_gallery_sort_oldest,
                            ),
                        )
                    }
                    IconButton(onClick = { onAction(PhotoGalleryUiAction.Refresh) }, enabled = !uiState.isLoading && !uiState.isSyncing) {
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
                uiState.days.isNotEmpty() -> PhotoGrid(uiState, onAction, memory, sharedTransitionScope, animatedVisibilityScope)
                // While the server is still being asked, "nothing backed up" may not be true.
                uiState.isLoading || uiState.isSyncing -> CircularProgressIndicator(Modifier.align(Alignment.Center))
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
            if (uiState.isSyncing && uiState.days.isNotEmpty()) {
                LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }
    }
}

@Composable
private fun PhotoGrid(
    uiState: PhotoGalleryUiState,
    onAction: (PhotoGalleryUiAction) -> Unit,
    memory: GridMemory,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    val gridState = memory.state
    val topId = uiState.days.firstOrNull()?.items?.firstOrNull()?.fileId
    LaunchedEffect(uiState.newestFirst, topId) {
        val sortChanged = memory.newestFirst.let { it != null && it != uiState.newestFirst }
        // A new photo on top is brought into view only for someone already at the top; further down stays put.
        val newOnTop = memory.topId.let { it != null && it != topId } && gridState.firstVisibleItemIndex <= 1
        memory.newestFirst = uiState.newestFirst
        memory.topId = topId
        if (sortChanged || newOnTop) gridState.scrollToItem(0)
    }
    LaunchedEffect(Unit) {
        val viewed = memory.returnTo ?: return@LaunchedEffect
        memory.returnTo = null
        val index = gridIndexOf(uiState.days, viewed) ?: return@LaunchedEffect
        if (gridState.layoutInfo.visibleItemsInfo.none { it.index == index }) gridState.scrollToItem(index)
    }
    LaunchedEffect(gridState) {
        snapshotFlow {
            val info = gridState.layoutInfo
            // With the count, so a page that still leaves the end in view asks for the next.
            info.totalItemsCount to ((info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - LOAD_MORE_WHEN_WITHIN)
        }.collect { (_, nearEnd) -> if (nearEnd) onAction(PhotoGalleryUiAction.LoadMore) }
    }
    // Settled rather than isScrollInProgress: a finger held still mid-drag is a pause too.
    // A State, not a value: read in each unstarted tile's effect, so a flip doesn't recompose the grid.
    val settled = remember { mutableStateOf(true) }
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .drop(1)
            .collectLatest {
                if (settled.value) {
                    settled.value = false
                    onAction(PhotoGalleryUiAction.Scrolling(true))
                }
                delay(SETTLE_MILLIS)
                settled.value = true
                onAction(PhotoGalleryUiAction.Scrolling(false))
            }
    }
    // Opening a photo mid-fling disposes the grid before it settles, which would leave the prefetch paused.
    DisposableEffect(Unit) { onDispose { onAction(PhotoGalleryUiAction.Scrolling(false)) } }
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
                    text = formatMomentDate(day.date.atStartOfDayIn(TimeZone.currentSystemDefault())),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
                )
            }
            items(day.items, key = { it.fileId.toString() }, contentType = { "photo" }) { item ->
                PhotoCell(item, settled, sharedTransitionScope, animatedVisibilityScope, onShown = { onAction(PhotoGalleryUiAction.ThumbnailShown) }) {
                    onAction(PhotoGalleryUiAction.Open(item))
                }
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
private fun PhotoCell(
    item: PhotoGalleryItem,
    settled: State<Boolean>,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onShown: () -> Unit,
    onClick: () -> Unit,
) {
    // Once started a tile keeps loading through the next scroll; only leaving the screen cancels it.
    var started by remember(item.fileId) { mutableStateOf(Snapshot.withoutReadObservation { settled.value }) }
    if (!started) LaunchedEffect(item.fileId) {
        snapshotFlow { settled.value }.first { it }
        started = true
    }
    val isVideo = item.payload.isVideo()
    val description = stringResource(if (isVideo) MR.string.photobase_gallery_video else MR.string.photobase_gallery_photo)
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onClick),
    ) {
        val imageData = remember(item.fileId, item.payload.key) { item.tileImageData() }
        if (imageData != null) {
            HomebaseImage(
                imageData = imageData,
                contentDescription = description,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onLoaded = onShown,
                deferLoad = !started,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
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
