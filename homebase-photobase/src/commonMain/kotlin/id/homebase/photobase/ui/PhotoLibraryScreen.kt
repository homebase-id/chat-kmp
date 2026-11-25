package id.homebase.photobase.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.core.widget.SettingsRow
import id.homebase.core.widget.SettingsRowAction
import id.homebase.core.widget.SettingsSectionHeader
import id.homebase.core.widget.SettingsTopBar
import id.homebase.photobase.gallery.PhotoGallery
import id.homebase.resources.MR
import id.homebase.resources.photobase_album_new
import id.homebase.resources.photobase_albums_none
import id.homebase.resources.photobase_albums_title
import id.homebase.resources.photobase_bin_failed
import id.homebase.resources.photobase_library_count
import id.homebase.resources.photobase_library_title
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun PhotoLibraryScreen(viewModel: PhotoLibraryViewModel, onOpen: (PhotoGallery) -> Unit, onBack: () -> Unit) {
    val counts by viewModel.counts.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    var naming by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.createFailed.collect { snackbar.showSnackbar(getString(MR.string.photobase_bin_failed)) } }
    if (naming) {
        NewAlbumDialog(
            onCreate = { naming = false; viewModel.createAlbum(it) },
            onDismiss = { naming = false },
        )
    }
    Scaffold(
        topBar = { SettingsTopBar(stringResource(MR.string.photobase_library_title), onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        LazyVerticalGrid(columns = GridCells.Adaptive(160.dp), modifier = Modifier.padding(innerPadding)) {
            items(counts.toList(), key = { it.first.key }, span = { GridItemSpan(maxLineSpan) }) { (gallery, count) ->
                SettingsRow(
                    icon = gallery.look.icon,
                    title = stringResource(gallery.look.title),
                    supportingText = count?.let { pluralStringResource(MR.plurals.photobase_library_count, it, it) },
                    action = SettingsRowAction.Navigate { onOpen(gallery) },
                )
            }
            item(key = "albums", span = { GridItemSpan(maxLineSpan) }) {
                SettingsSectionHeader(stringResource(MR.string.photobase_albums_title), Modifier.padding(start = 16.dp, top = 16.dp)) {
                    IconButton(onClick = { naming = true }) {
                        Icon(Icons.Outlined.Add, contentDescription = stringResource(MR.string.photobase_album_new))
                    }
                }
            }
            if (albums?.isEmpty() == true) {
                item(key = "none", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        stringResource(MR.string.photobase_albums_none),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            items(albums.orEmpty(), key = { it.id.toString() }) { album ->
                AlbumCard(album, onOpen = { onOpen(PhotoGallery.Album(album.id)) }, modifier = Modifier.padding(8.dp))
            }
        }
    }
}
