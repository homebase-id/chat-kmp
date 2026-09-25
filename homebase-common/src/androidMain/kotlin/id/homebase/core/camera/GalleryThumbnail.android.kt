package id.homebase.core.camera

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal actual fun rememberLatestGalleryThumbnail(sizePx: Int): ImageBitmap? {
    val context = LocalContext.current.applicationContext
    return produceState<ImageBitmap?>(null, context, sizePx) {
        value = withContext(Dispatchers.IO) {
            if (!context.canReadMedia()) return@withContext null
            runCatching { context.latestMediaThumbnail(sizePx)?.asImageBitmap() }
                .onFailure { Logger.w(tag = "GalleryThumbnail", throwable = it) { "Latest media thumbnail failed" } }
                .getOrNull()
        }
    }.value
}

private fun Context.canReadMedia(): Boolean {
    fun granted(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    return when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> true
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            granted(Manifest.permission.READ_MEDIA_IMAGES) || granted(Manifest.permission.READ_MEDIA_VIDEO)
        else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}

private class NewestMedia(val collection: Uri, val id: Long, val dateAdded: Long) {
    val isVideo get() = collection == MediaStore.Video.Media.EXTERNAL_CONTENT_URI
}

private fun Context.latestMediaThumbnail(sizePx: Int): Bitmap? {
    val newest = listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        .mapNotNull { newestIn(it) }
        .maxByOrNull { it.dateAdded } ?: return null
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        return contentResolver.loadThumbnail(ContentUris.withAppendedId(newest.collection, newest.id), Size(sizePx, sizePx), null)
    }
    @Suppress("DEPRECATION")
    return if (newest.isVideo) {
        MediaStore.Video.Thumbnails.getThumbnail(contentResolver, newest.id, MediaStore.Video.Thumbnails.MINI_KIND, null)
    } else {
        MediaStore.Images.Thumbnails.getThumbnail(contentResolver, newest.id, MediaStore.Images.Thumbnails.MINI_KIND, null)
    }
}

private fun Context.newestIn(collection: Uri): NewestMedia? {
    val args = Bundle().apply {
        putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.MediaColumns.DATE_ADDED))
        putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) putInt(ContentResolver.QUERY_ARG_LIMIT, 1)
    }
    val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED)
    return contentResolver.query(collection, projection, args, null)?.use { cursor ->
        if (cursor.moveToFirst()) NewestMedia(collection, cursor.getLong(0), cursor.getLong(1)) else null
    }
}
