package id.homebase.photobase.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.homebase.api.common.OdinId
import id.homebase.core.image.HomebaseImage
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.image.forGridTile
import id.homebase.core.widget.AdaptiveSheet
import id.homebase.core.widget.NameDialog
import id.homebase.photobase.gallery.PhotoAlbum
import id.homebase.resources.MR
import id.homebase.resources.create
import id.homebase.resources.photobase_album_add
import id.homebase.resources.photobase_album_name
import id.homebase.resources.photobase_album_new
import id.homebase.resources.photobase_library_count
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid

/** The album's newest photo, or its icon while it has none. */
@Composable
internal fun AlbumCover(album: PhotoAlbum, modifier: Modifier = Modifier) =
    AlbumCover(remember(album.cover) { album.cover?.tileImageData()?.forGridTile() }, modifier)

@Composable
internal fun AlbumCover(imageData: HomebaseImageData?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (imageData != null) {
            HomebaseImage(imageData = imageData, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Outlined.PhotoAlbum, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
        }
    }
}

/** An album on the Library screen: its cover, name and how many photos it has. */
@Composable
internal fun AlbumCard(album: PhotoAlbum, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpen).padding(4.dp)) {
        AlbumCover(album, Modifier.fillMaxWidth())
        Text(album.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text(
            pluralStringResource(MR.plurals.photobase_library_count, album.photos, album.photos),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** An album someone shares with this identity: its cover read from their drive, and its name. Unlike [AlbumCard], no count. */
@Composable
internal fun SharedAlbumCard(name: String, cover: SharedPhoto?, owner: OdinId, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpen).padding(4.dp)) {
        AlbumCover(remember(cover) { cover?.imageData(owner)?.forGridTile() }, Modifier.fillMaxWidth())
        Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
    }
}

/** Picks the album the selection goes to, or a new one; null [albums] while they're read. */
@Composable
internal fun AlbumPickerSheet(albums: StateFlow<List<PhotoAlbum>?>, leaveOut: Uuid?, onPick: (Uuid) -> Unit, onNew: () -> Unit, onDismiss: () -> Unit) {
    val shown by albums.collectAsStateWithLifecycle()
    AdaptiveSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Text(stringResource(MR.string.photobase_album_add), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
            ListItem(
                headlineContent = { Text(stringResource(MR.string.photobase_album_new)) },
                leadingContent = { Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(48.dp).padding(12.dp)) },
                modifier = Modifier.clickable { dismiss(onNew) },
            )
            if (shown == null) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(16.dp))
            for (album in shown.orEmpty().filter { it.id != leaveOut }) {
                ListItem(
                    headlineContent = { Text(album.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(pluralStringResource(MR.plurals.photobase_library_count, album.photos, album.photos)) },
                    leadingContent = { AlbumCover(album, Modifier.size(48.dp)) },
                    modifier = Modifier.clickable { dismiss { onPick(album.id) } },
                )
            }
        }
    }
}

@Composable
internal fun NewAlbumDialog(onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    NameDialog(
        title = stringResource(MR.string.photobase_album_new),
        label = stringResource(MR.string.photobase_album_name),
        confirmLabel = stringResource(MR.string.create),
        onConfirm = onCreate,
        onDismiss = onDismiss,
    )
}
