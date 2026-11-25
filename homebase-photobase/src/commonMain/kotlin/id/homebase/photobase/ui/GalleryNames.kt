package id.homebase.photobase.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.ui.graphics.vector.ImageVector
import id.homebase.photobase.gallery.PhotoGallery
import id.homebase.photobase.gallery.PhotoShelf
import id.homebase.resources.MR
import id.homebase.resources.photobase_archive_empty_state
import id.homebase.resources.photobase_archive_title
import id.homebase.resources.photobase_bin_empty_state
import id.homebase.resources.photobase_bin_title
import id.homebase.resources.photobase_favorites_empty_state
import id.homebase.resources.photobase_favorites_title
import id.homebase.resources.photobase_gallery_empty
import id.homebase.resources.photobase_label
import org.jetbrains.compose.resources.StringResource

internal val PhotoGallery.title: StringResource
    get() = when (this) {
        is PhotoGallery.OnShelf -> when (shelf) {
            PhotoShelf.Library -> MR.string.photobase_label
            PhotoShelf.Archive -> MR.string.photobase_archive_title
            PhotoShelf.Bin -> MR.string.photobase_bin_title
        }
        PhotoGallery.Favorites -> MR.string.photobase_favorites_title
    }

internal val PhotoGallery.emptyText: StringResource
    get() = when (this) {
        is PhotoGallery.OnShelf -> when (shelf) {
            PhotoShelf.Library -> MR.string.photobase_gallery_empty
            PhotoShelf.Archive -> MR.string.photobase_archive_empty_state
            PhotoShelf.Bin -> MR.string.photobase_bin_empty_state
        }
        PhotoGallery.Favorites -> MR.string.photobase_favorites_empty_state
    }

internal val PhotoGallery.icon: ImageVector
    get() = when (this) {
        is PhotoGallery.OnShelf -> when (shelf) {
            PhotoShelf.Library -> Icons.Outlined.PhotoLibrary
            PhotoShelf.Archive -> Icons.Outlined.Archive
            PhotoShelf.Bin -> Icons.Outlined.Delete
        }
        PhotoGallery.Favorites -> Icons.Outlined.FavoriteBorder
    }
