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
import androidx.compose.material3.TopAppBar
import id.homebase.resources.photobase_gallery_retry
import id.homebase.resources.photobase_gallery_load_failed
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import id.homebase.core.widget.ScreenBackHandler
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import id.homebase.core.haptics.rememberHaptics
import id.homebase.core.haptics.Haptics
import id.homebase.photobase.gallery.GalleryRows
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalDensity
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
import id.homebase.core.image.forGridTile
import id.homebase.core.media.subsample.fullScreenImageSharedBounds
import id.homebase.photobase.gallery.GalleryLayout
import id.homebase.photobase.gallery.PhotoGalleryItem
import id.homebase.core.util.formatMomentDate
import id.homebase.resources.MR
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import id.homebase.resources.photobase_backup_title
import id.homebase.resources.photobase_gallery_empty
import id.homebase.resources.photobase_gallery_photo
import id.homebase.resources.photobase_gallery_refresh
import id.homebase.resources.photobase_gallery_sort_newest
import id.homebase.resources.photobase_gallery_sort_oldest
import id.homebase.resources.photobase_gallery_syncing
import id.homebase.resources.photobase_gallery_video
import id.homebase.resources.photobase_label
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid
import id.homebase.core.image.mediaSharedKey

private val GRID_SPACING = 2.dp

// Thumbnails wait for scrolling to settle; the headers, which carry the tinies, only for a brief pause.
private const val SETTLE_MILLIS = 150L
private const val HEADERS_AFTER_MILLIS = 60L

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PhotoGalleryScreen(
    viewModel: PhotoGalleryViewModel,
    onOpenBackupSettings: (() -> Unit)?,
    backupIndicator: @Composable () -> Unit,
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle()
    val uiState by state
    val library by viewModel.library.collectAsStateWithLifecycle()
    val loaded = remember(state) { derivedStateOf { state.value.loaded } }
    // Only the read days that show another number than counted move the grid; other reads fill tiles in place.
    val shown by remember(library) {
        derivedStateOf {
            val counts = library ?: return@derivedStateOf emptyMap()
            loaded.value.filter { (day, items) -> items.size != counts.countOf(day) }.mapValues { it.value.size }
        }
    }
    val layout = remember(library, uiState.newestFirst, shown) { library?.let { GalleryLayout(it, uiState.newestFirst, shown) } }
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
            if (viewing && layout != null) {
                GalleryViewer(uiState.openedFileId, layout, loaded.value, onAction, { memory.returnToSlot = it }, this@SharedTransitionLayout, this@AnimatedContent)
            } else {
                GalleryGrid(uiState, layout, loaded, onAction, onOpenBackupSettings, backupIndicator, memory, this@SharedTransitionLayout, this@AnimatedContent)
            }
        }
    }
}

/** Outlives the grid while the viewer covers it. */
internal class GridMemory(val state: LazyGridState) {
    var newestFirst: Boolean? = null
    var topDay: Int? = null
    var returnTo: Uuid? = null
    // The viewer's day and slot when that day wasn't read yet: the open photo is still the one before it.
    var returnToSlot: Pair<Int, Int>? = null
}

/** Every photo of the library in the grid's order, one per page; a page whose day isn't read yet reads it. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun GalleryViewer(
    openedFileId: Uuid?,
    layout: GalleryLayout,
    loaded: Map<Int, List<PhotoGalleryItem>>,
    onAction: (PhotoGalleryUiAction) -> Unit,
    onUnreadPage: (Pair<Int, Int>?) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    val opened by rememberUpdatedState(openedFileId)
    val initialPage = remember { openedFileId?.let { layout.photoOf(it, loaded) } ?: 0 }
    val pagerState = rememberPagerState(initialPage) { layout.photoCount }
    val close = { onAction(PhotoGalleryUiAction.CloseViewer) }
    ScreenBackHandler(onBack = close)
    val showUI = remember { mutableStateOf(true) }
    // The day and slot of a page whose day is still being read: when it arrives, that photo becomes the open one.
    val awaiting = remember { mutableStateOf<Pair<Int, Int>?>(null) }
    LaunchedEffect(layout, loaded) {
        if (layout.photoCount == 0) {
            close()
            return@LaunchedEffect
        }
        val waitingOn = awaiting.value
        if (waitingOn != null) {
            // Photos arriving before it move its page; a day that's gone takes the viewer with it.
            val at = layout.photoOf(waitingOn.first, waitingOn.second)
            if (at == null) {
                close()
                return@LaunchedEffect
            }
            if (at != pagerState.settledPage) pagerState.scrollToPage(at)
        } else {
            // Photos arriving above the open one would otherwise leave the pager on a different photo.
            // Against the settled page, not the current one: mid-swipe, current is already the neighbour.
            val at = opened?.let { layout.photoOf(it, loaded) }
            if (at == null) {
                close()
                return@LaunchedEffect
            }
            if (at != pagerState.settledPage) pagerState.scrollToPage(at)
        }
        snapshotFlow { pagerState.settledPage }.collect { page ->
            val place = layout.locate(page)
            onAction(PhotoGalleryUiAction.Showing(place.first, place.first))
            val item = layout.photoAt(page, loaded)
            awaiting.value = if (item == null) place else null
            onUnreadPage(awaiting.value)
            if (item != null && item.fileId != opened) onAction(PhotoGalleryUiAction.Open(item))
        }
    }
    val shown = layout.photoAt(pagerState.settledPage, loaded)
    HorizontalPager(
        state = pagerState,
        key = { page -> layout.photoAt(page, loaded)?.fileId?.toString() ?: "slot-$page" },
        beyondViewportPageCount = 1,
        pageSpacing = 24.dp,
        // Around the pager, not inside a page: from inside a page the open animation restarts mid-flight and jumps.
        modifier = Modifier.fillMaxSize().fullScreenImageSharedBounds(
            shown?.let { mediaSharedKey(it.fileId, it.shown.key) },
            sharedTransitionScope,
            animatedVisibilityScope,
        ),
    ) { page ->
        val item = layout.photoAt(page, loaded)
        if (item == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@HorizontalPager
        }
        val settled = page == pagerState.settledPage
        when (val viewer = remember(item) { item.toViewer() }) {
            is FullScreenOverlay.ViewMessageData -> FullScreenMediaViewer(
                data = viewer,
                onDismiss = close,
                sharedTransitionScope = null,
                animatedVisibilityScope = null,
                showUIState = showUI,
                // A native video view can't follow the open and close flight, so a Live photo's clip waits for it to end.
                isFocused = settled && !animatedVisibilityScope.transition.isRunning,
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
        remember(item.fileId, item.payload.key) { item.tileImageData() }?.let { imageData ->
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
    val iv = shown.iv?.bytes ?: return null
    return HomebaseImageData.from(
        driveId = driveId,
        fileId = fileId,
        descriptor = shown,
        previewThumbnail = shown.previewThumbnail?.toEmbeddedThumb(),
        keyHeader = KeyHeader(iv = iv, aesKey = keyHeader.aesKey),
    )
}

@Composable
private fun GalleryGrid(
    uiState: PhotoGalleryUiState,
    layout: GalleryLayout?,
    loaded: State<Map<Int, List<PhotoGalleryItem>>>,
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
                    IconButton(onClick = { onAction(PhotoGalleryUiAction.ToggleSort) }) {
                        Icon(
                            Icons.Outlined.SwapVert,
                            contentDescription = stringResource(
                                if (uiState.newestFirst) MR.string.photobase_gallery_sort_newest else MR.string.photobase_gallery_sort_oldest,
                            ),
                        )
                    }
                    IconButton(onClick = { onAction(PhotoGalleryUiAction.Refresh) }, enabled = !uiState.isSyncing) {
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
            val hasPhotos = (layout?.photoCount ?: 0) > 0
            when {
                layout != null && hasPhotos -> PhotoGrid(layout, loaded, onAction, memory, sharedTransitionScope, animatedVisibilityScope, rememberHaptics())
                // While the drive is still syncing, "nothing backed up" may not be true.
                uiState.isSyncing -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator()
                    SyncingLabel(uiState.syncedSoFar ?: 0)
                }
                layout == null && uiState.countFailed -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(MR.string.photobase_gallery_load_failed),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = { onAction(PhotoGalleryUiAction.Retry) }) {
                        Text(stringResource(MR.string.photobase_gallery_retry))
                    }
                }
                layout == null || !uiState.librarySynced -> CircularProgressIndicator(Modifier.align(Alignment.Center))
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
            // Not for a routine round that brings nothing: every reconnect starts one.
            if ((uiState.syncedSoFar ?: 0) > 0 && hasPhotos) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(MaterialTheme.colorScheme.surfaceContainer),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    SyncingLabel(uiState.syncedSoFar ?: 0, Modifier.padding(vertical = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun SyncingLabel(syncedSoFar: Int, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(MR.string.photobase_gallery_syncing, syncedSoFar),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
internal fun PhotoGrid(
    layout: GalleryLayout,
    loaded: State<Map<Int, List<PhotoGalleryItem>>>,
    onAction: (PhotoGalleryUiAction) -> Unit,
    memory: GridMemory,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    haptics: Haptics,
) {
    val gridState = memory.state
    val newestFirst = layout.newestFirst
    val topDay = layout.days.firstOrNull()
    LaunchedEffect(newestFirst, topDay) {
        val sortChanged = memory.newestFirst.let { it != null && it != newestFirst }
        // A new day on top is brought into view only for someone already at the top; further down stays put.
        val newOnTop = memory.topDay.let { it != null && it != topDay } && gridState.firstVisibleItemIndex <= 1
        memory.newestFirst = newestFirst
        memory.topDay = topDay
        if (sortChanged || newOnTop) gridState.scrollToItem(0)
    }
    LaunchedEffect(Unit) {
        val slot = memory.returnToSlot
        val viewed = memory.returnTo
        memory.returnToSlot = null
        memory.returnTo = null
        val index = when {
            slot != null -> layout.itemOf(slot.first, slot.second)
            viewed != null -> layout.itemOf(viewed, loaded.value)
            else -> null
        } ?: return@LaunchedEffect
        if (gridState.layoutInfo.visibleItemsInfo.none { it.index == index }) gridState.scrollToItem(index)
    }
    val currentLayout by rememberUpdatedState(layout)
    LaunchedEffect(gridState) {
        var first = true
        snapshotFlow {
            val visible = gridState.layoutInfo.visibleItemsInfo
            if (visible.isEmpty()) null else currentLayout.dayOfItem(visible.first().index) to currentLayout.dayOfItem(visible.last().index)
        }.collectLatest { days ->
            if (days == null) return@collectLatest
            // Not on every frame of a fling: only where the grid pauses. Not for the first screen either.
            if (!first) delay(HEADERS_AFTER_MILLIS)
            first = false
            onAction(PhotoGalleryUiAction.Showing(days.first, days.second))
        }
    }
    // Settled rather than isScrollInProgress: a finger held still mid-drag is a pause too.
    // A State, not a value: read in each unstarted tile's effect, so a flip doesn't recompose the grid.
    val settled = remember { mutableStateOf(true) }
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .drop(1)
            .collectLatest {
                settled.value = false
                delay(SETTLE_MILLIS)
                settled.value = true
            }
    }
    // What the grid measured, for the scrubber: its columns, and a header's and a row's height with the gap after it.
    val spacing = with(LocalDensity.current) { GRID_SPACING.roundToPx() }
    val measured by remember(gridState, spacing) {
        derivedStateOf {
            val info = gridState.layoutInfo
            val header = info.visibleItemsInfo.firstOrNull { it.contentType == "day" }?.size?.height
            val tile = info.visibleItemsInfo.firstOrNull { it.contentType == "photo" }?.size?.height
            if (header == null || tile == null || info.maxSpan <= 0) null else Triple(info.maxSpan, header + spacing, tile + spacing)
        }
    }
    val rows = remember(layout, measured) { measured?.let { (columns, header, row) -> GalleryRows(layout, columns, header, row) } }
    var pointerNear by remember { mutableStateOf(false) }
    val nearPx = with(LocalDensity.current) { 48.dp.toPx() }
    Box(
        Modifier.fillMaxSize().pointerInput(nearPx) {
            // Seen first and never consumed: a mouse near the right edge shows the scrubber, and the grid still scrolls.
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull() ?: continue
                    if (change.type == PointerType.Mouse) {
                        pointerNear = event.type != PointerEventType.Exit && change.position.x > size.width - nearPx
                    }
                }
            }
        },
    ) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = 96.dp),
            verticalArrangement = Arrangement.spacedBy(GRID_SPACING),
            horizontalArrangement = Arrangement.spacedBy(GRID_SPACING),
            contentPadding = PaddingValues(4.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            for (position in layout.days.indices) {
                val day = layout.days[position]
                item(key = "day-$day", span = { GridItemSpan(maxLineSpan) }, contentType = "day") {
                    Text(
                        // The zone formatMomentDate reads back in, or a new zone shows the day before or after.
                        text = formatMomentDate(LocalDate.fromEpochDays(day).atStartOfDayIn(TimeZone.currentSystemDefault())),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
                    )
                }
                items(layout.slotsOf(position), key = { "slot-$day-$it" }, contentType = { "photo" }) { slot ->
                    when (val item = layout.photoAt(position, slot, loaded.value)) {
                        null -> PlaceholderCell()
                        else -> PhotoCell(item, settled, sharedTransitionScope, animatedVisibilityScope, onShown = { onAction(PhotoGalleryUiAction.ThumbnailShown) }) {
                            onAction(PhotoGalleryUiAction.Open(item))
                        }
                    }
                }
            }
        }
        if (rows != null) PhotoScrubber(gridState, rows, pointerNear, haptics, Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun PlaceholderCell() {
    Box(Modifier.galleryTile())
}

@Composable
private fun Modifier.galleryTile(): Modifier = aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceContainerHighest)

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
            .galleryTile()
            .clickable(onClick = onClick),
    ) {
        val imageData = remember(item.fileId, item.payload.key) { item.tileImageData()?.forGridTile() }
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
