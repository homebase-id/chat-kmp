package id.homebase.photobase.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.grid.LazyGridScope
import id.homebase.api.common.OdinId
import id.homebase.core.widget.NameDialog
import id.homebase.resources.photobase_share_identity
import id.homebase.resources.photobase_share_identity_invalid
import id.homebase.resources.photobase_share_send
import id.homebase.resources.photobase_shared_with_me_add
import id.homebase.resources.photobase_shared_with_me_failed
import id.homebase.resources.photobase_shared_with_me_none
import id.homebase.resources.photobase_shared_with_me_not_kept
import id.homebase.resources.photobase_shared_with_me_not_reviewed
import id.homebase.resources.photobase_shared_with_me_not_sealed
import id.homebase.resources.photobase_shared_with_me_title
import kotlin.uuid.Uuid
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
import id.homebase.resources.photobase_album_failed
import id.homebase.resources.photobase_library_count
import id.homebase.resources.photobase_library_title
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun PhotoLibraryScreen(
    viewModel: PhotoLibraryViewModel,
    onOpen: (PhotoGallery) -> Unit,
    onBack: () -> Unit,
    sharedWithMe: SharedWithMeViewModel,
    onOpenShared: (owner: OdinId, albumId: Uuid) -> Unit,
) {
    val counts by viewModel.counts.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val shared by sharedWithMe.uiState.collectAsStateWithLifecycle()
    var naming by remember { mutableStateOf(false) }
    var addingSharer by remember { mutableStateOf(false) }
    if (addingSharer) {
        NameDialog(
            title = stringResource(MR.string.photobase_shared_with_me_add),
            label = stringResource(MR.string.photobase_share_identity),
            confirmLabel = stringResource(MR.string.photobase_share_send),
            onConfirm = { addingSharer = false; sharedWithMe.addSharer(it) },
            onDismiss = { addingSharer = false },
        )
    }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.createFailed.collect { snackbar.showSnackbar(getString(MR.string.photobase_album_failed)) } }
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
            sharedWithMeSection(shared, onAdd = { addingSharer = true }, onOpenShared)
        }
    }
}

private fun LazyGridScope.sharedWithMeSection(state: SharedWithMeUiState, onAdd: () -> Unit, onOpen: (OdinId, Uuid) -> Unit) {
    item(key = "shared", span = { GridItemSpan(maxLineSpan) }) {
        SettingsSectionHeader(stringResource(MR.string.photobase_shared_with_me_title), Modifier.padding(start = 16.dp, top = 16.dp)) {
            IconButton(onClick = onAdd) {
                Icon(Icons.Outlined.PersonAdd, contentDescription = stringResource(MR.string.photobase_shared_with_me_add))
            }
        }
    }
    state.notKept?.let { who ->
        item(key = "shared-not-kept", span = { GridItemSpan(maxLineSpan) }) {
            Text(
                stringResource(MR.string.photobase_shared_with_me_not_kept, who.domainName),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
    if (state.identityInvalid || state.sharers?.isEmpty() == true) {
        item(key = "shared-none", span = { GridItemSpan(maxLineSpan) }) {
            Text(
                stringResource(if (state.identityInvalid) MR.string.photobase_share_identity_invalid else MR.string.photobase_shared_with_me_none),
                color = if (state.identityInvalid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
    state.sharers.orEmpty().forEach { sharer ->
        item(key = "sharer-${sharer.owner.domainName}", span = { GridItemSpan(maxLineSpan) }) {
            val problem = when (sharer.problem) {
                SharedProblem.NotReviewed -> MR.string.photobase_shared_with_me_not_reviewed
                SharedProblem.NotSealedYet -> MR.string.photobase_shared_with_me_not_sealed
                SharedProblem.Failed -> MR.string.photobase_shared_with_me_failed
                null -> null
            }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(sharer.owner.domainName, style = MaterialTheme.typography.titleSmall)
                if (problem != null) Text(stringResource(problem, sharer.owner.domainName), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(sharer.albums.orEmpty(), key = { "shared-${sharer.owner.domainName}-${it.id}" }) { album ->
            SharedAlbumCard(album.name, sharer.covers[album.id], sharer.owner, onOpen = { onOpen(sharer.owner, album.id) }, modifier = Modifier.padding(8.dp))
        }
    }
}

