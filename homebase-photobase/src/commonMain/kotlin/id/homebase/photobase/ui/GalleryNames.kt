package id.homebase.photobase.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.ui.graphics.vector.ImageVector
import id.homebase.photobase.gallery.PhotoGallery
import id.homebase.photobase.gallery.PhotoShelf
import id.homebase.resources.MR
import id.homebase.resources.photobase_album_empty_state
import id.homebase.resources.photobase_album_title
import id.homebase.resources.photobase_archive_empty_state
import id.homebase.resources.photobase_archive_title
import id.homebase.resources.photobase_bin_empty_state
import id.homebase.resources.photobase_bin_title
import id.homebase.resources.photobase_favorites_empty_state
import id.homebase.resources.photobase_favorites_title
import id.homebase.resources.photobase_gallery_empty
import id.homebase.resources.photobase_label
import org.jetbrains.compose.resources.StringResource

internal class GalleryLook(val title: StringResource, val emptyText: StringResource, val icon: ImageVector)

internal val PhotoGallery.look: GalleryLook
    get() = when (this) {
        is PhotoGallery.OnShelf -> when (shelf) {
            PhotoShelf.Library -> GalleryLook(MR.string.photobase_label, MR.string.photobase_gallery_empty, Icons.Outlined.PhotoLibrary)
            PhotoShelf.Archive -> GalleryLook(MR.string.photobase_archive_title, MR.string.photobase_archive_empty_state, Icons.Outlined.Archive)
            PhotoShelf.Bin -> GalleryLook(MR.string.photobase_bin_title, MR.string.photobase_bin_empty_state, Icons.Outlined.Delete)
        }
        PhotoGallery.Favorites -> GalleryLook(MR.string.photobase_favorites_title, MR.string.photobase_favorites_empty_state, Icons.Outlined.FavoriteBorder)
        // Its own name, once the albums are read.
        is PhotoGallery.Album -> GalleryLook(MR.string.photobase_album_title, MR.string.photobase_album_empty_state, Icons.Outlined.PhotoAlbum)
    }
