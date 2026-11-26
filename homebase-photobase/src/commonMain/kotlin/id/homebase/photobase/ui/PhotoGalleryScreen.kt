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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.filled.MoreVert
import id.homebase.resources.photobase_gallery_more
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.MutableIntState
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.rememberLazyListState
import id.homebase.photobase.gallery.GalleryLines
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import id.homebase.core.haptics.rememberHaptics
import id.homebase.core.haptics.Haptics
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
import id.homebase.resources.settings
import id.homebase.resources.photobase_gallery_photo
import id.homebase.resources.photobase_gallery_refresh
import id.homebase.resources.photobase_gallery_sort_newest
import id.homebase.resources.photobase_gallery_sort_oldest
import id.homebase.resources.photobase_gallery_syncing
import id.homebase.resources.photobase_gallery_video
import id.homebase.resources.photobase_label
import org.jetbrains.compose.resources.stringResource
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.material.icons.filled.Favorite
import id.homebase.resources.photobase_favorite_state
import id.homebase.core.haptics.HapticEvent
import id.homebase.core.widget.ConfirmDialog
import id.homebase.core.widget.NameDialog
import androidx.compose.ui.graphics.vector.ImageVector
import org.jetbrains.compose.resources.StringResource
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.ui.text.style.TextOverflow
import id.homebase.resources.save
import id.homebase.resources.photobase_album_delete
import id.homebase.resources.photobase_album_delete_confirm_text
import id.homebase.resources.photobase_album_delete_confirm_title
import id.homebase.resources.photobase_album_name
import id.homebase.resources.photobase_album_rename
import id.homebase.core.widget.SelectionTopBar
import id.homebase.photobase.gallery.PhotoGallery
import id.homebase.photobase.gallery.PhotoShelf
import androidx.compose.material.icons.outlined.PhotoLibrary
import id.homebase.resources.photobase_albums_title
import id.homebase.resources.menu_back
import id.homebase.resources.photobase_bin_delete_confirm_title
import id.homebase.resources.photobase_bin_delete_forever
import id.homebase.resources.photobase_bin_empty
import id.homebase.resources.photobase_bin_empty_confirm_title
import id.homebase.resources.photobase_bin_cant_undo
import id.homebase.resources.photobase_bin_move
import id.homebase.resources.photobase_bin_move_confirm_title
import id.homebase.resources.photobase_bin_move_confirm_text
import id.homebase.resources.photobase_selection_day
import id.homebase.resources.photobase_selection_select
import id.homebase.resources.photobase_selection_day_clear
import org.jetbrains.compose.resources.pluralStringResource
import kotlin.uuid.Uuid
import id.homebase.core.image.mediaSharedKey

// Thumbnails wait for scrolling to settle; the headers, which carry the tinies, only for a brief pause.
private const val SETTLE_MILLIS = 150L
private val TILE_MIN = 96.dp
private val GRID_GAP = 2.dp
private val GRID_PADDING = 4.dp
private const val HEADERS_AFTER_MILLIS = 60L
private val TOOLBAR_ROOM = 80.dp
private const val SELECTED_SCALE = 0.86f

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PhotoGalleryScreen(
    viewModel: PhotoGalleryViewModel,
    onOpenSettings: (() -> Unit)?,
    backupIndicator: @Composable () -> Unit,
    onOpenLibrary: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    haptics: Haptics = rememberHaptics(),
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle()
    val uiState by state
    val library by viewModel.library.collectAsStateWithLifecycle()
    val loaded = remember(state) { derivedStateOf { state.value.loaded } }
    val selected = remember(state) { derivedStateOf { state.value.selected } }
    // Only the read days that show another number than counted move the grid; other reads fill tiles in place.
    val shown by remember(library) {
        derivedStateOf {
            val counts = library ?: return@derivedStateOf emptyMap()
            loaded.value.filter { (day, items) -> items.size != counts.countOf(day) }.mapValues { it.value.size }
        }
    }
    val layout = remember(library, uiState.newestFirst, shown) { library?.let { GalleryLayout(it, uiState.newestFirst, shown) } }
    val gridState = rememberLazyListState()
    val columns = rememberSaveable { mutableIntStateOf(0) }
    val memory = remember(gridState) { GridMemory(gridState, columns) }
    val onAction: (PhotoGalleryUiAction) -> Unit = remember(viewModel) {
        { action ->
            if (action is PhotoGalleryUiAction.CloseViewer) memory.returnTo = viewModel.uiState.value.openedFileId
            viewModel.onAction(action)
        }
    }
    val gallery = viewModel.gallery
    val snackbar = remember { SnackbarHostState() }
    val leave by rememberUpdatedState(onBack)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            if (event == PhotoGalleryEvent.AlbumDeleted) leave?.invoke() else snackbar.show(event, onAction)
        }
    }
    var picking by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf<AlbumNaming?>(null) }
    val albumName = viewModel.albumName.collectAsStateWithLifecycle().value
    val title = albumName ?: stringResource(gallery.look.title)
    if (picking) {
        AlbumPickerSheet(
            albums = viewModel.albums,
            leaveOut = (gallery as? PhotoGallery.Album)?.id,
            onPick = { picking = false; onAction(PhotoGalleryUiAction.AddSelectedToAlbum(it)) },
            onNew = { picking = false; naming = AlbumNaming.New },
            onDismiss = { picking = false },
        )
    }
    when (naming) {
        AlbumNaming.New -> NewAlbumDialog(
            onCreate = { naming = null; onAction(PhotoGalleryUiAction.AddSelectedToNewAlbum(it)) },
            onDismiss = { naming = null },
        )
        AlbumNaming.Rename -> NameDialog(
            title = stringResource(MR.string.photobase_album_rename),
            label = stringResource(MR.string.photobase_album_name),
            confirmLabel = stringResource(MR.string.save),
            initial = albumName.orEmpty(),
            onConfirm = { naming = null; onAction(PhotoGalleryUiAction.RenameAlbum(it)) },
            onDismiss = { naming = null },
        )
        null -> Unit
    }
    // Moving to the bin asks first: a missed tap on a small phone mustn't take photos away unnoticed.
    var asking by remember { mutableStateOf<PhotoGalleryUiAction?>(null) }
    // The viewer's question goes with its photo, should a sync take that away.
    LaunchedEffect(uiState.openedFileId) {
        val pending = asking as? PhotoGalleryUiAction.MovePhotoToBin
        if (pending != null && pending.fileId != uiState.openedFileId) asking = null
    }
    asking?.let { action ->
        val selected = uiState.selected.size
        val toBin = when (action) {
            is PhotoGalleryUiAction.MovePhotoToBin -> 1
            is PhotoGalleryUiAction.MoveSelected -> selected.takeIf { action.to == PhotoShelf.Bin }
            else -> null
        }
        val question = when {
            toBin != null -> Triple(
                pluralStringResource(MR.plurals.photobase_bin_move_confirm_title, toBin, toBin),
                pluralStringResource(MR.plurals.photobase_bin_move_confirm_text, toBin),
                stringResource(MR.string.photobase_bin_move),
            )
            action == PhotoGalleryUiAction.DeleteForGood -> Triple(
                pluralStringResource(MR.plurals.photobase_bin_delete_confirm_title, selected, selected),
                pluralStringResource(MR.plurals.photobase_bin_cant_undo, selected),
                stringResource(MR.string.photobase_bin_delete_forever),
            )
            action == PhotoGalleryUiAction.DeleteAlbum -> Triple(
                stringResource(MR.string.photobase_album_delete_confirm_title, title),
                stringResource(MR.string.photobase_album_delete_confirm_text),
                stringResource(MR.string.photobase_album_delete),
            )
            action == PhotoGalleryUiAction.EmptyBin -> Triple(
                stringResource(MR.string.photobase_bin_empty_confirm_title),
                pluralStringResource(MR.plurals.photobase_bin_cant_undo, layout?.photoCount ?: 0),
                stringResource(MR.string.photobase_bin_empty),
            )
            else -> null
        }
        question?.let { (title, text, confirmLabel) ->
            ConfirmDialog(
                title = title,
                text = text,
                confirmLabel = confirmLabel,
                destructive = true,
                onConfirm = {
                    asking = null
                    onAction(action)
                },
                onDismiss = { asking = null },
            )
        }
    }
    // On the shared-bounds clock, or the tile and viewer fade out of step with the morph.
    val fadeSpec = tween<Float>(HomebaseConstants.Animation.CHAT_IMAGE_FULL_SCREEN_TRANSITION_DURATION)
    Box(Modifier.fillMaxSize()) {
        SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = uiState.openedFileId != null,
                transitionSpec = { fadeIn(fadeSpec) togetherWith fadeOut(fadeSpec) },
            ) { viewing ->
                if (viewing && layout != null) {
                    val moveToBin = { fileId: Uuid -> asking = PhotoGalleryUiAction.MovePhotoToBin(fileId) }.takeIf { gallery == PhotoGallery.Library || gallery == PhotoGallery.Archive }
                    GalleryViewer(
                        uiState.openedFileId, layout, loaded.value, onAction, moveToBin,
                        // A heart waits for the one before it, so a second tap can't send the same again.
                        heart = if (gallery == PhotoGallery.Bin) null else !uiState.busy,
                        onUnreadPage = { memory.returnToSlot = it },
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = this@AnimatedContent,
                    )
                } else {
                    GalleryGrid(
                        uiState, gallery, title, layout, loaded, selected, onAction, onOpenSettings, backupIndicator, onOpenLibrary, onBack,
                        { asking = it }, { picking = true }, { naming = AlbumNaming.Rename }.takeIf { albumName != null }, memory, haptics, this@SharedTransitionLayout, this@AnimatedContent,
                    )
                }
            }
        }
        // Above the selection's toolbar when a failed action gives the selection back.
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = if (uiState.selecting) TOOLBAR_ROOM else 0.dp))
    }
}

/** Outlives the grid while the viewer covers it. */
/** [columns] is the column count [state]'s index was taken in, saved with it. */
internal class GridMemory(val state: LazyListState, val columns: MutableIntState = mutableIntStateOf(0)) {
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
    onMoveToBin: ((Uuid) -> Unit)?,
    /** Whether the heart is enabled; null shows none. */
    heart: Boolean?,
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
                onDelete = onMoveToBin,
                extraActions = {
                    if (heart != null) FavoriteButton(item.favorite, enabled = heart) { onAction(PhotoGalleryUiAction.SetFavorite(!item.favorite, item.fileId)) }
                },
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

internal fun PhotoGalleryItem.tileImageData(): HomebaseImageData? {
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
    gallery: PhotoGallery,
    title: String,
    layout: GalleryLayout?,
    loaded: State<Map<Int, List<PhotoGalleryItem>>>,
    selected: State<Set<Uuid>>,
    onAction: (PhotoGalleryUiAction) -> Unit,
    onOpenSettings: (() -> Unit)?,
    backupIndicator: @Composable () -> Unit,
    onOpenLibrary: (() -> Unit)?,
    onBack: (() -> Unit)?,
    onConfirm: (PhotoGalleryUiAction) -> Unit,
    onAddToAlbum: () -> Unit,
    onRenameAlbum: (() -> Unit)?,
    memory: GridMemory,
    haptics: Haptics,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    val hasPhotos = (layout?.photoCount ?: 0) > 0
    ScreenBackHandler(enabled = uiState.selecting) { onAction(PhotoGalleryUiAction.ClearSelection) }
    Scaffold(
        topBar = {
            when {
                uiState.selecting -> SelectionTopBar(uiState.selected.size, onClear = { onAction(PhotoGalleryUiAction.ClearSelection) })
                gallery == PhotoGallery.Library -> LibraryTopBar(uiState, onAction, onOpenSettings, backupIndicator, onOpenLibrary)
                else -> TopAppBar(
                    navigationIcon = {
                        if (onBack != null) {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(MR.string.menu_back))
                            }
                        }
                    },
                    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    actions = {
                        if (gallery == PhotoGallery.Bin) {
                            TextButton(onClick = { onConfirm(PhotoGalleryUiAction.EmptyBin) }, enabled = hasPhotos && !uiState.busy) {
                                Text(stringResource(MR.string.photobase_bin_empty))
                            }
                        }
                        if (gallery is PhotoGallery.Album) AlbumMenu(enabled = !uiState.busy, onRename = onRenameAlbum, onDelete = { onConfirm(PhotoGalleryUiAction.DeleteAlbum) })
                    },
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding),
        ) {
            when {
                layout != null && hasPhotos -> PhotoGrid(layout, loaded, onAction, memory, sharedTransitionScope, animatedVisibilityScope, haptics, selected)
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
                        imageVector = gallery.look.icon,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(gallery.look.emptyText),
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
            if (uiState.selecting) {
                SelectionToolbar(
                    gallery = gallery,
                    enabled = !uiState.busy,
                    allFavorite = uiState.selectedAreFavorites(),
                    onAction = onAction,
                    onConfirm = onConfirm,
                    onAddToAlbum = onAddToAlbum,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                )
            }
        }
    }
}

private enum class AlbumNaming { New, Rename }

// Null onRename: the album's name isn't read yet, and both dialogs name it.
@Composable
private fun AlbumMenu(enabled: Boolean, onRename: (() -> Unit)?, onDelete: () -> Unit) {
    GalleryMenu { item ->
        item(MR.string.photobase_album_rename, Icons.Outlined.Edit, enabled && onRename != null) { onRename?.invoke() }
        item(MR.string.photobase_album_delete, Icons.Outlined.Delete, enabled && onRename != null, onDelete)
    }
}

private typealias MenuItem = @Composable (label: StringResource, icon: ImageVector, enabled: Boolean, onClick: () -> Unit) -> Unit

// Material 3 keeps three actions in the bar; the rest go in a menu, which closes before each runs.
@Composable
private fun GalleryMenu(items: @Composable (item: MenuItem) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(MR.string.photobase_gallery_more))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            items { label, icon, enabled, onClick ->
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    leadingIcon = { Icon(icon, contentDescription = null) },
                    enabled = enabled,
                    onClick = {
                        menu = false
                        onClick()
                    },
                )
            }
        }
    }
}

@Composable
private fun LibraryTopBar(
    uiState: PhotoGalleryUiState,
    onAction: (PhotoGalleryUiAction) -> Unit,
    onOpenSettings: (() -> Unit)?,
    backupIndicator: @Composable () -> Unit,
    onOpenLibrary: (() -> Unit)?,
) {
    TopAppBar(
        title = { Text(stringResource(MR.string.photobase_label)) },
        actions = {
            if (onOpenLibrary != null) {
                IconButton(onClick = onOpenLibrary) {
                    Icon(Icons.Outlined.PhotoLibrary, contentDescription = stringResource(MR.string.photobase_albums_title))
                }
            }
            backupIndicator()
            IconButton(onClick = { onAction(PhotoGalleryUiAction.ToggleSort) }) {
                Icon(
                    Icons.Outlined.SwapVert,
                    contentDescription = stringResource(
                        if (uiState.newestFirst) MR.string.photobase_gallery_sort_newest else MR.string.photobase_gallery_sort_oldest,
                    ),
                )
            }
            GalleryMenu { item ->
                item(MR.string.photobase_gallery_refresh, Icons.Outlined.Refresh, !uiState.isSyncing) { onAction(PhotoGalleryUiAction.Refresh) }
                if (onOpenSettings != null) item(MR.string.settings, Icons.Outlined.Settings, true, onOpenSettings)
            }
        },
    )
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
    haptics: Haptics = rememberHaptics(),
    selected: State<Set<Uuid>> = remember { mutableStateOf(emptySet()) },
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = galleryColumns(constraints.maxWidth, LocalDensity.current)
        val lines = remember(layout, columns) { GalleryLines(layout, columns) }
        // New columns move every row, also while the grid was away: the line at the top stays the one showing what it
        // showed, its top aligned, since the old offset was into a row of another height.
        SideEffect {
            val before = memory.columns.intValue
            memory.columns.intValue = columns
            if (before != 0 && before != columns) {
                val old = GalleryLines(layout, before)
                val state = memory.state
                if (old.itemCount > 0) lines.itemFor(old, state.firstVisibleItemIndex.coerceAtMost(old.itemCount - 1))?.let { state.requestScrollToItem(it) }
            }
        }
        PhotoLines(lines, loaded, selected, onAction, memory, sharedTransitionScope, animatedVisibilityScope, haptics)
    }
}

/** As GridCells.Adaptive would: as many columns of at least TILE_MIN as fit across [widthPx] between the padding. */
internal fun galleryColumns(widthPx: Int, density: Density): Int = with(density) {
    maxOf(1, (widthPx - 2 * GRID_PADDING.roundToPx() + GRID_GAP.roundToPx()) / (TILE_MIN.roundToPx() + GRID_GAP.roundToPx()))
}

@Composable
private fun PhotoLines(
    lines: GalleryLines,
    loaded: State<Map<Int, List<PhotoGalleryItem>>>,
    selected: State<Set<Uuid>>,
    onAction: (PhotoGalleryUiAction) -> Unit,
    memory: GridMemory,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    haptics: Haptics,
) {
    val layout = lines.layout
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
            slot != null -> lines.itemOf(slot.first, slot.second)
            viewed != null -> lines.itemOf(viewed, loaded.value)
            else -> null
        } ?: return@LaunchedEffect
        if (gridState.layoutInfo.visibleItemsInfo.none { it.index == index }) gridState.scrollToItem(index)
    }
    val currentLines by rememberUpdatedState(lines)
    LaunchedEffect(gridState) {
        var first = true
        snapshotFlow {
            val visible = gridState.layoutInfo.visibleItemsInfo
            if (visible.isEmpty()) null else currentLines.dayOfItem(visible.first().index) to currentLines.dayOfItem(visible.last().index)
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
    val selecting by remember(selected) { derivedStateOf { selected.value.isNotEmpty() } }
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .drop(1)
            .collectLatest {
                settled.value = false
                delay(SETTLE_MILLIS)
                settled.value = true
            }
    }
    PhotoScrubber(gridState, lines, haptics) {
        LazyColumn(
            state = gridState,
            verticalArrangement = Arrangement.spacedBy(GRID_GAP),
            // Room for the selection's toolbar always, so starting a selection doesn't move the grid.
            contentPadding = PaddingValues(start = GRID_PADDING, top = GRID_PADDING, end = GRID_PADDING, bottom = GRID_PADDING + TOOLBAR_ROOM),
            // A grid to accessibility services, as the lazy grid was: a line per row, a column per tile.
            modifier = Modifier.fillMaxSize().semantics { collectionInfo = CollectionInfo(lines.itemCount, lines.columns) },
        ) {
            items(lines.itemCount, key = lines::keyOf, contentType = { if (lines.rowOfItem(it) < 0) DAY_HEADER else PHOTO_ROW }) { item ->
                val position = lines.positionOfItem(item)
                val row = lines.rowOfItem(item)
                if (row < 0) {
                    DayHeader(layout.days[position], item, lines.columns, selecting, loaded, selected, onAction)
                    return@items
                }
                val slots = layout.slotsOf(position)
                val held = loaded.value
                Row(horizontalArrangement = Arrangement.spacedBy(GRID_GAP)) {
                    for (column in 0 until lines.columns) {
                        val slot = row * lines.columns + column
                        val tile = Modifier.weight(1f).semantics { collectionItemInfo = CollectionItemInfo(item, 1, column, 1) }
                        if (slot >= slots) {
                            Spacer(tile)
                            continue
                        }
                        when (val photo = layout.photoAt(position, slot, held)) {
                            null -> PlaceholderCell(tile)
                            else -> PhotoCell(
                                photo, settled, selecting, selected, sharedTransitionScope, animatedVisibilityScope, tile,
                                onShown = { onAction(PhotoGalleryUiAction.ThumbnailShown) },
                                onLongClick = {
                                    haptics.perform(HapticEvent.LongPress)
                                    onAction(PhotoGalleryUiAction.ToggleSelected(photo.fileId))
                                },
                            ) {
                                onAction(if (selecting) PhotoGalleryUiAction.ToggleSelected(photo.fileId) else PhotoGalleryUiAction.Open(photo))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** While selecting, tapping a day picks all its photos, or none if all are picked. */
@Composable
private fun DayHeader(
    day: Int,
    item: Int,
    columns: Int,
    selecting: Boolean,
    loaded: State<Map<Int, List<PhotoGalleryItem>>>,
    selected: State<Set<Uuid>>,
    onAction: (PhotoGalleryUiAction) -> Unit,
) {
    val photos = if (selecting) loaded.value[day] else null
    val allPicked = photos != null && photos.isNotEmpty() && photos.all { it.fileId in selected.value }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .semantics { collectionItemInfo = CollectionItemInfo(item, 1, 0, columns) }
            .then(
                // Until the day's photos are read there's nothing to pick.
                if (photos != null) {
                    Modifier.clickable(
                        onClickLabel = stringResource(if (allPicked) MR.string.photobase_selection_day_clear else MR.string.photobase_selection_day),
                    ) { onAction(PhotoGalleryUiAction.ToggleDay(day)) }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 8.dp, vertical = 10.dp),
    ) {
        Text(
            // The zone formatMomentDate reads back in, or a new zone shows the day before or after.
            text = formatMomentDate(LocalDate.fromEpochDays(day).atStartOfDayIn(TimeZone.currentSystemDefault())),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        if (selecting) SelectionMark(allPicked, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.size(20.dp))
    }
}

@Composable
private fun PlaceholderCell(modifier: Modifier) {
    Box(modifier.galleryTile())
}

@Composable
private fun Modifier.galleryTile(): Modifier = aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceContainerHighest)

@Composable
private fun PhotoCell(
    item: PhotoGalleryItem,
    settled: State<Boolean>,
    // Read only while selecting, so a grid that isn't doesn't follow the selection.
    selecting: Boolean,
    selection: State<Set<Uuid>>,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    modifier: Modifier,
    onShown: () -> Unit,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
) {
    val selected = selecting && item.fileId in selection.value
    // A picked tile draws in from its edges; scaled, not resized, so its thumbnail isn't asked for again.
    val scale = if (selecting) animateFloatAsState(if (selected) SELECTED_SCALE else 1f).value else 1f
    // Once started a tile keeps loading through the next scroll; only leaving the screen cancels it.
    var started by remember(item.fileId) { mutableStateOf(Snapshot.withoutReadObservation { settled.value }) }
    if (!started) LaunchedEffect(item.fileId) {
        snapshotFlow { settled.value }.first { it }
        started = true
    }
    val isVideo = item.payload.isVideo()
    val description = stringResource(if (isVideo) MR.string.photobase_gallery_video else MR.string.photobase_gallery_photo)
    val favorite = if (item.favorite) stringResource(MR.string.photobase_favorite_state) else null
    Box(
        modifier = modifier
            .galleryTile()
            .then(if (selected) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier)
            .combinedClickable(onLongClickLabel = stringResource(MR.string.photobase_selection_select), onLongClick = onLongClick, onClick = onClick)
            .semantics {
                contentDescription = description
                if (favorite != null) stateDescription = favorite
                if (selecting) this.selected = selected
            },
    ) {
        val imageData = remember(item.fileId, item.payload.key) { item.tileImageData()?.forGridTile() }
        if (imageData != null) {
            HomebaseImage(
                imageData = imageData,
                // The tile says what it is, also before its thumbnail loads.
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().then(
                    if (scale == 1f) {
                        Modifier
                    } else {
                        Modifier.graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        }
                    },
                ),
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
        if (item.favorite) {
            Icon(
                imageVector = Icons.Filled.Favorite,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).size(18.dp),
            )
        }
        if (selecting) SelectionMark(selected, MaterialTheme.colorScheme.inverseOnSurface, Modifier.align(Alignment.TopStart).padding(4.dp).size(22.dp))
    }
}
