package id.homebase.photobase.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.HeartBroken
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import id.homebase.core.widget.WithTooltip
import id.homebase.photobase.gallery.PhotoGallery
import id.homebase.photobase.gallery.PhotoShelf
import id.homebase.resources.MR
import id.homebase.resources.action_undo
import id.homebase.resources.photobase_album_add
import id.homebase.resources.photobase_album_failed
import id.homebase.resources.photobase_album_added
import id.homebase.resources.photobase_album_remove
import id.homebase.resources.photobase_album_removed
import id.homebase.resources.photobase_archive_archived
import id.homebase.resources.photobase_favorites_add
import id.homebase.resources.photobase_favorites_added
import id.homebase.resources.photobase_favorites_remove
import id.homebase.resources.photobase_favorites_removed
import id.homebase.resources.photobase_archive_move
import id.homebase.resources.photobase_archive_unarchive
import id.homebase.resources.photobase_archive_unarchived
import id.homebase.resources.photobase_bin_changed_elsewhere
import id.homebase.resources.photobase_bin_delete_forever
import id.homebase.resources.photobase_bin_deleted
import id.homebase.resources.photobase_bin_failed
import id.homebase.resources.photobase_bin_move
import id.homebase.resources.photobase_bin_moved
import id.homebase.resources.photobase_bin_refused
import id.homebase.resources.photobase_bin_restore
import id.homebase.resources.photobase_bin_restored
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SelectionToolbar(
    gallery: PhotoGallery,
    enabled: Boolean,
    allFavorite: Boolean,
    onAction: (PhotoGalleryUiAction) -> Unit,
    onConfirm: (PhotoGalleryUiAction) -> Unit,
    onAddToAlbum: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HorizontalFloatingToolbar(expanded = true, modifier = modifier) {
        val moveTo = { to: PhotoShelf -> { onAction(PhotoGalleryUiAction.MoveSelected(to)) } }
        val heart: @Composable () -> Unit = { FavoriteButton(allFavorite, enabled) { onAction(PhotoGalleryUiAction.SetFavorite(!allFavorite)) } }
        val toAlbum: @Composable () -> Unit = { ToolbarAction(Icons.Outlined.LibraryAdd, MR.string.photobase_album_add, enabled, onAddToAlbum) }
        val toBin: @Composable () -> Unit = { ToolbarAction(Icons.Outlined.Delete, MR.string.photobase_bin_move, enabled) { onConfirm(PhotoGalleryUiAction.MoveSelected(PhotoShelf.Bin)) } }
        when (gallery) {
            is PhotoGallery.OnShelf -> when (gallery.shelf) {
                PhotoShelf.Library -> {
                    heart()
                    toAlbum()
                    ToolbarAction(Icons.Outlined.Archive, MR.string.photobase_archive_move, enabled, moveTo(PhotoShelf.Archive))
                    toBin()
                }
                PhotoShelf.Archive -> {
                    heart()
                    toAlbum()
                    ToolbarAction(Icons.Outlined.Unarchive, MR.string.photobase_archive_unarchive, enabled, moveTo(PhotoShelf.Library))
                    toBin()
                }
                PhotoShelf.Bin -> {
                    ToolbarAction(Icons.Outlined.RestoreFromTrash, MR.string.photobase_bin_restore, enabled, moveTo(PhotoShelf.Library))
                    ToolbarAction(Icons.Outlined.DeleteForever, MR.string.photobase_bin_delete_forever, enabled) { onConfirm(PhotoGalleryUiAction.DeleteForGood) }
                }
            }
            PhotoGallery.Favorites -> {
                ToolbarAction(Icons.Outlined.HeartBroken, MR.string.photobase_favorites_remove, enabled) { onAction(PhotoGalleryUiAction.SetFavorite(false)) }
                toAlbum()
            }
            is PhotoGallery.Album -> {
                heart()
                toAlbum()
                ToolbarAction(Icons.Outlined.RemoveCircleOutline, MR.string.photobase_album_remove, enabled) { onAction(PhotoGalleryUiAction.RemoveSelectedFromAlbum) }
            }
        }
    }
}

@Composable
internal fun FavoriteButton(favorite: Boolean, enabled: Boolean, onClick: () -> Unit) {
    ToolbarAction(
        icon = if (favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
        label = if (favorite) MR.string.photobase_favorites_remove else MR.string.photobase_favorites_add,
        enabled = enabled,
        onClick = onClick,
    )
}

// Icons only, so four fit a small phone; a long press says what each does.
@Composable
private fun ToolbarAction(icon: ImageVector, label: StringResource, enabled: Boolean, onClick: () -> Unit) {
    val text = stringResource(label)
    WithTooltip(text) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(icon, contentDescription = text)
        }
    }
}

/** A picked or unpicked mark, on a tile or a day's header. */
@Composable
internal fun SelectionMark(picked: Boolean, unpickedTint: Color, modifier: Modifier = Modifier) {
    Icon(
        imageVector = if (picked) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
        contentDescription = null,
        tint = if (picked) MaterialTheme.colorScheme.primary else unpickedTint,
        modifier = modifier,
    )
}

/** Says what a move, delete or heart did; a move or heart offers Undo. */
internal suspend fun SnackbarHostState.show(event: PhotoGalleryEvent, onAction: (PhotoGalleryUiAction) -> Unit) {
    val message = when (event) {
        is PhotoGalleryEvent.MoveFailed -> getString(MR.string.photobase_bin_failed)
        PhotoGalleryEvent.AlbumWriteFailed -> getString(MR.string.photobase_album_failed)
        // The screen leaves the album.
        PhotoGalleryEvent.AlbumDeleted -> return
        is PhotoGalleryEvent.Moved -> buildList {
            val result = event.result
            if (result.done > 0) {
                val done = when (event.action) {
                    MoveAction.AddToAlbum -> MR.plurals.photobase_album_added
                    MoveAction.RemoveFromAlbum -> MR.plurals.photobase_album_removed
                    MoveAction.Favorite -> MR.plurals.photobase_favorites_added
                    MoveAction.Unfavorite -> MR.plurals.photobase_favorites_removed
                    MoveAction.Archive -> MR.plurals.photobase_archive_archived
                    MoveAction.Unarchive -> MR.plurals.photobase_archive_unarchived
                    MoveAction.MoveToBin -> MR.plurals.photobase_bin_moved
                    MoveAction.Restore -> MR.plurals.photobase_bin_restored
                    MoveAction.DeleteForGood, MoveAction.Empty -> MR.plurals.photobase_bin_deleted
                }
                add(getPluralString(done, result.done, result.done))
            }
            if (result.changedElsewhere > 0) add(getPluralString(MR.plurals.photobase_bin_changed_elsewhere, result.changedElsewhere, result.changedElsewhere))
            if (result.failed > 0) add(getPluralString(MR.plurals.photobase_bin_refused, result.failed, result.failed))
        }.joinToString(" ")
    }
    if (message.isEmpty()) return
    val undo = (event as? PhotoGalleryEvent.Moved)?.undo
    val answer = showSnackbar(
        message = message,
        actionLabel = undo?.let { getString(MR.string.action_undo) },
        duration = if (undo == null) SnackbarDuration.Short else SnackbarDuration.Long,
    )
    if (answer == SnackbarResult.ActionPerformed && undo != null) onAction(undo)
}
