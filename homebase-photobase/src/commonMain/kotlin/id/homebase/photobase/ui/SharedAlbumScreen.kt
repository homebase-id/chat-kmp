package id.homebase.photobase.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.uuid.Uuid
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.common.OdinId
import id.homebase.chat.conversationlist.FullScreenOverlay
import id.homebase.photobase.sharing.PhotoAddOutcome
import id.homebase.chat.widget.FullScreenVideoPlayer
import id.homebase.core.image.HomebaseImage
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.image.forGridTile
import id.homebase.core.widget.ScreenBackHandler
import id.homebase.resources.MR
import id.homebase.resources.menu_back
import id.homebase.resources.photobase_comments_failed
import id.homebase.resources.photobase_gallery_video
import id.homebase.resources.photobase_photo_added_by
import id.homebase.resources.photobase_shared_album_add_failed
import id.homebase.resources.photobase_shared_album_add_not_confirmed
import id.homebase.resources.photobase_shared_album_add_retrying
import id.homebase.resources.photobase_shared_album_added
import id.homebase.resources.photobase_shared_album_empty
import id.homebase.resources.photobase_shared_with_me_failed
import org.jetbrains.compose.resources.getString
import id.homebase.photobase.info.InfoButton
import id.homebase.photobase.info.PhotoInfoSheet
import org.jetbrains.compose.resources.stringResource

/** The photo as its owner's drive holds it, read over peer by its global transit id. */
internal fun SharedPhoto.imageData(owner: OdinId): HomebaseImageData? =
    item.tileImageData()?.copy(remoteOdinId = owner, globalTransitId = file.fileMetadata.globalTransitId ?: return null)

/** [photo]'s video, played from its owner's drive over peer; null for a photo. */
internal fun SharedPhoto.player(owner: OdinId): FullScreenOverlay.VideoPlayerData? {
    val globalTransitId = file.fileMetadata.globalTransitId ?: return null
    return (item.toViewer() as? FullScreenOverlay.VideoPlayerData)?.copy(remoteOdinId = owner, globalTransitId = globalTransitId)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedAlbumScreen(
    viewModel: SharedAlbumViewModel,
    onBack: () -> Unit,
    /** The album's own action, when this identity may add to it. */
    addAction: @Composable () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val owner = viewModel.owner.domainName
            snackbar.showSnackbar(
                when (event) {
                    SharedAlbumEvent.Failed -> getString(MR.string.photobase_shared_with_me_failed, owner)
                    SharedAlbumEvent.SocialFailed -> getString(MR.string.photobase_comments_failed)
                    SharedAlbumEvent.AddFailed -> getString(MR.string.photobase_shared_album_add_failed)
                    is SharedAlbumEvent.Add -> when (event.outcome) {
                        is PhotoAddOutcome.Delivered -> getString(MR.string.photobase_shared_album_added)
                        is PhotoAddOutcome.Refused -> getString(MR.string.photobase_shared_album_add_failed)
                        is PhotoAddOutcome.Retrying -> getString(MR.string.photobase_shared_album_add_retrying, owner)
                        is PhotoAddOutcome.NotConfirmed -> getString(MR.string.photobase_shared_album_add_not_confirmed, owner)
                    }
                },
            )
        }
    }
    val opened = uiState.openedIndex
    if (opened != null && uiState.photos.isNotEmpty()) {
        var commentsOn by remember { mutableStateOf<Uuid?>(null) }
        commentsOn?.let { photoId ->
            PhotoCommentsSheet(
                photoId = photoId,
                comments = remember(photoId) { viewModel.commentsOf(photoId) },
                reactionsOf = viewModel::reactionsOf,
                commentReactionsOf = { viewModel.commentReactionsOf(photoId, it) },
                onAction = viewModel::onSocial,
                onDismiss = { commentsOn = null },
            )
        }
        var infoOn by remember { mutableStateOf<SharedPhoto?>(null) }
        // It goes with its photo, as when the photo leaves the album.
        LaunchedEffect(uiState.photos) {
            if (infoOn?.let { shown -> uiState.photos.none { it.file.fileId == shown.file.fileId } } == true) infoOn = null
        }
        infoOn?.let { photo ->
            PhotoInfoSheet(
                item = photo.item,
                owner = viewModel.owner,
                reader = viewModel.photoInfo,
                albums = listOfNotNull(uiState.album?.name),
                addedBy = photo.addedBy,
                backedUp = false,
                onDismiss = { infoOn = null },
            )
        }
        SharedPhotoViewer(uiState.photos, opened.coerceIn(0, uiState.photos.lastIndex), viewModel.owner, snackbar, onClose = { viewModel.open(null) }) { photo ->
            val photoId = photo.file.fileMetadata.globalTransitId
            if (photoId != null) CommentsButton { commentsOn = photoId }
            InfoButton { infoOn = photo }
        }
        return
    }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(MR.string.menu_back)) }
                },
                title = { Text(uiState.album?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = { if (uiState.album?.canAdd == true) addAction() },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        val gridState = rememberLazyGridState()
        LaunchedEffect(gridState, uiState.photos.size) {
            snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                .collect { last -> if (last >= uiState.photos.size - PAGE_AHEAD) viewModel.loadMore() }
        }
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when {
                uiState.photos.isEmpty() && uiState.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                uiState.photos.isEmpty() -> Text(
                    stringResource(MR.string.photobase_shared_album_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(TILE),
                    state = gridState,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    itemsIndexed(uiState.photos, key = { _, photo -> photo.file.fileId.toString() }) { index, photo ->
                        val data = remember(photo) { photo.imageData(viewModel.owner)?.forGridTile() }
                        Box(Modifier.aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant).clickable { viewModel.open(index) }) {
                            if (data != null) HomebaseImage(imageData = data, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun SharedPhotoViewer(
    photos: List<SharedPhoto>,
    start: Int,
    owner: OdinId,
    snackbar: SnackbarHostState,
    onClose: () -> Unit,
    photoActions: @Composable (SharedPhoto) -> Unit,
) {
    ScreenBackHandler(enabled = true, onBack = onClose)
    val pager = rememberPagerState(initialPage = start) { photos.size }
    val shown = photos.getOrNull(pager.currentPage)
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(MR.string.menu_back)) }
                },
                title = {
                    shown?.addedBy?.let { Text(stringResource(MR.string.photobase_photo_added_by, it.domainName), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                },
                actions = { shown?.let { photoActions(it) } },
            )
        },
    ) { innerPadding ->
        HorizontalPager(pager, Modifier.fillMaxSize().padding(innerPadding), key = { photos[it].file.fileId.toString() }) { page ->
            val photo = photos[page]
            val video = remember(photo) { photo.player(owner) }
            // A player plays as soon as it's composed, so a neighbour shows its still until swiped to.
            if (video != null && page == pager.settledPage) {
                FullScreenVideoPlayer(data = video, onDismiss = onClose)
                return@HorizontalPager
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val data = remember(photo) { photo.imageData(owner) }
                if (data != null) {
                    HomebaseImage(
                        imageData = data,
                        contentDescription = if (video != null) stringResource(MR.string.photobase_gallery_video) else null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (video != null) {
                    Icon(Icons.Filled.PlayCircle, contentDescription = null, tint = MaterialTheme.colorScheme.inverseOnSurface, modifier = Modifier.size(64.dp))
                }
            }
        }
    }
}

private val TILE = 112.dp
private const val PAGE_AHEAD = 24
