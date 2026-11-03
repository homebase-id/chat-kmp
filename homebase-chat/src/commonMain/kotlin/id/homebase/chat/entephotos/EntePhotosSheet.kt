package id.homebase.chat.entephotos

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.chat.widget.ExtendPermissionDialog
import id.homebase.chat.widget.SelectionBadge
import id.homebase.core.config.EntePhotosDrive
import id.homebase.core.image.HomebaseImage
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.image.ImageSize
import id.homebase.core.util.formatHms
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.cd_ente_photo
import id.homebase.resources.cd_ente_video
import id.homebase.resources.ente_photos_album_empty
import id.homebase.resources.ente_photos_all
import id.homebase.resources.ente_photos_allow
import id.homebase.resources.ente_photos_empty
import id.homebase.resources.ente_photos_failed
import id.homebase.resources.ente_photos_load_failed
import id.homebase.resources.ente_photos_locked
import id.homebase.resources.ente_photos_needs_access
import id.homebase.resources.ente_photos_not_installed
import id.homebase.resources.ente_photos_prepare_failed
import id.homebase.resources.ente_photos_retry
import id.homebase.resources.ente_photos_send
import id.homebase.resources.ente_photos_title
import id.homebase.resources.ente_photos_too_big
import kotlin.uuid.Uuid
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** Photos and videos from the owner's Ente Photos library, by album; [onPicked] gets each pick's original, decrypted into a temp file. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntePhotosSheet(
    onDismiss: () -> Unit,
    onPicked: (List<String>) -> Unit,
    viewModel: EntePhotosViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val currentOnPicked by rememberUpdatedState(onPicked)

    LaunchedEffect(viewModel) { viewModel.open() }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is EntePhotosEvent.Picked -> currentOnPicked(event.paths)
                EntePhotosEvent.Close -> currentOnDismiss()
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = Modifier.testTag("ente_photos_sheet")) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(MR.string.cancel))
                }
                Text(
                    text = stringResource(MR.string.ente_photos_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                if (state.access == EntePhotosAccess.Granted) {
                    Button(
                        onClick = viewModel::send,
                        enabled = state.selected.isNotEmpty() && !state.preparing,
                        modifier = Modifier.testTag("ente_photos_send"),
                    ) {
                        if (state.preparing) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text(stringResource(MR.string.ente_photos_send, state.selected.size))
                        }
                    }
                }
            }
            if (state.preparing) {
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("ente_photos_progress"),
                )
            }
            if (state.prepareFailed) {
                Text(
                    text = stringResource(MR.string.ente_photos_prepare_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            when (state.access) {
                EntePhotosAccess.Checking -> Centered { CircularProgressIndicator() }
                EntePhotosAccess.NotInstalled -> Message(MR.string.ente_photos_not_installed)
                EntePhotosAccess.NeedsAccess -> Message(MR.string.ente_photos_needs_access, MR.string.ente_photos_allow, viewModel::askForAccess)
                EntePhotosAccess.Locked -> Message(MR.string.ente_photos_locked, MR.string.ente_photos_retry, viewModel::open)
                EntePhotosAccess.Failed -> Message(MR.string.ente_photos_failed, MR.string.ente_photos_retry, viewModel::open)
                EntePhotosAccess.Granted -> {
                    AlbumChips(state, onShow = viewModel::showAlbum)
                    PhotoGrid(state, onToggle = viewModel::toggle, onLoadMore = viewModel::loadMore)
                }
            }
        }
    }

    ExtendPermissionDialog(viewModel = viewModel.extendPermissionViewModel)
}

@Composable
private fun AlbumChips(state: EntePhotosUiState, onShow: (Uuid?) -> Unit) {
    if (state.albums.isEmpty()) return
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().testTag("ente_albums"),
    ) {
        item(key = "all") {
            FilterChip(
                selected = state.album == null,
                onClick = { onShow(null) },
                label = { Text(stringResource(MR.string.ente_photos_all)) },
                modifier = Modifier.testTag("ente_album_all"),
            )
        }
        rowItems(state.albums, key = { it.id.toString() }) { album ->
            FilterChip(
                selected = state.album == album.id,
                onClick = { onShow(album.id) },
                label = { Text(album.name, maxLines = 1) },
                modifier = Modifier.testTag("ente_album"),
            )
        }
    }
}

@Composable
private fun PhotoGrid(state: EntePhotosUiState, onToggle: (EntePhoto) -> Unit, onLoadMore: () -> Unit) {
    if (state.photos.isEmpty()) {
        when {
            state.loading -> Centered { CircularProgressIndicator() }
            state.loadFailed -> Message(MR.string.ente_photos_failed, MR.string.ente_photos_retry, onLoadMore)
            state.album != null -> Message(MR.string.ente_photos_album_empty)
            else -> Message(MR.string.ente_photos_empty)
        }
        return
    }
    val gridState = rememberLazyGridState()
    val nearEnd by remember {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= gridState.layoutInfo.totalItemsCount - 12
        }
    }
    val currentOnLoadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(nearEnd, state.canLoadMore) {
        if (nearEnd && state.canLoadMore) currentOnLoadMore()
    }
    val photoDescription = stringResource(MR.string.cd_ente_photo)
    val videoDescription = stringResource(MR.string.cd_ente_video)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(110.dp),
        state = gridState,
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize().testTag("ente_photos_grid"),
    ) {
        items(state.photos, key = { it.fileId.toString() }) { photo ->
            val number = state.selected.indexOf(photo.fileId) + 1
            Box(
                modifier = Modifier
                    .aspectRatio(1f)
                    .clickable(enabled = !photo.tooBigToSend) { onToggle(photo) }
                    .testTag(if (photo.isVideo) "ente_video" else "ente_photo"),
            ) {
                // A video's original is never loaded for its tile: no thumbnail, no picture.
                if (photo.isVideo && !photo.hasThumbnails) {
                    Box(
                        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.Movie, contentDescription = photo.title ?: videoDescription, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    HomebaseImage(
                        imageData = remember(photo.fileId) {
                            HomebaseImageData.from(
                                driveId = Uuid.parse(EntePhotosDrive.ALIAS),
                                fileId = photo.fileId,
                                descriptor = photo.original,
                                keyHeader = photo.payloadKey,
                                requestedSize = ImageSize.THUMB_MEDIUM,
                            )
                        },
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        contentDescription = photo.title ?: if (photo.isVideo) videoDescription else photoDescription,
                    )
                }
                if (photo.isVideo) VideoBadge(photo)
                if (photo.tooBigToSend) {
                    Box(
                        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).testTag("ente_too_big"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(MR.string.ente_photos_too_big),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    SelectionBadge(
                        isSelected = number > 0,
                        selectionNumber = number,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                    )
                }
            }
        }
        if (state.loading || state.loadFailed) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    if (state.loading) {
                        CircularProgressIndicator()
                    } else {
                        TextButton(onClick = onLoadMore) { Text(stringResource(MR.string.ente_photos_load_failed)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.VideoBadge(photo: EntePhoto) {
    Row(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(6.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(Icons.Default.PlayCircle, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
        photo.durationSeconds?.let {
            Text(text = formatHms(it * 1000, padMinutes = true), style = MaterialTheme.typography.labelSmall, color = Color.White)
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun Message(text: StringResource, action: StringResource? = null, onAction: () -> Unit = {}) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(text), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        if (action != null) Button(onClick = onAction) { Text(stringResource(action)) }
    }
}
