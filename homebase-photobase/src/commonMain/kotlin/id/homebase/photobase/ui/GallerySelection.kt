package id.homebase.photobase.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.HeartBroken
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import id.homebase.photobase.gallery.PhotoShelf
import id.homebase.resources.MR
import id.homebase.resources.action_undo
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
    shelf: PhotoShelf,
    enabled: Boolean,
    /** Every picked photo is a favourite already, so the heart takes them out. */
    favorites: Boolean,
    onAction: (PhotoGalleryUiAction) -> Unit,
    onDeleteForGood: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HorizontalFloatingToolbar(expanded = true, modifier = modifier) {
        val moveTo = { to: PhotoShelf -> { onAction(PhotoGalleryUiAction.MoveSelected(to)) } }
        when (shelf) {
            PhotoShelf.Library, PhotoShelf.Archive -> {
                FavoriteButton(favorites, enabled) { onAction(PhotoGalleryUiAction.SetFavorite(!favorites)) }
                if (shelf == PhotoShelf.Library) {
                    ToolbarAction(Icons.Outlined.Archive, MR.string.photobase_archive_move, enabled, moveTo(PhotoShelf.Archive))
                } else {
                    ToolbarAction(Icons.Outlined.Unarchive, MR.string.photobase_archive_unarchive, enabled, moveTo(PhotoShelf.Library))
                }
                ToolbarAction(Icons.Outlined.Delete, MR.string.photobase_bin_move, enabled, moveTo(PhotoShelf.Bin))
            }
            PhotoShelf.Bin -> {
                ToolbarAction(Icons.Outlined.RestoreFromTrash, MR.string.photobase_bin_restore, enabled, moveTo(PhotoShelf.Library))
                ToolbarAction(Icons.Outlined.DeleteForever, MR.string.photobase_bin_delete_forever, enabled, onDeleteForGood)
            }
            PhotoShelf.Favorites -> ToolbarAction(Icons.Outlined.HeartBroken, MR.string.photobase_favorites_remove, enabled) {
                onAction(PhotoGalleryUiAction.SetFavorite(false))
            }
        }
    }
}

/** Filled when the photos are favourites already; tapping takes them out. */
@Composable
internal fun FavoriteButton(favorite: Boolean, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            imageVector = if (favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
            contentDescription = stringResource(if (favorite) MR.string.photobase_favorites_remove else MR.string.photobase_favorites_add),
        )
    }
}

@Composable
private fun ToolbarAction(icon: ImageVector, label: StringResource, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(label))
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
        is PhotoGalleryEvent.Moved -> buildList {
            val result = event.result
            if (result.done > 0) {
                val done = when (event.action) {
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
