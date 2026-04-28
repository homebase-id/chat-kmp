package id.homebase.core.gallery

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidGalleryManager(val context: Context) : PlatformGalleryManager {

    private val collectionUri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

    private val projection = arrayOf(
        MediaStore.Files.FileColumns._ID,
        MediaStore.Files.FileColumns.MEDIA_TYPE,
        MediaStore.Files.FileColumns.MIME_TYPE,
        MediaStore.Files.FileColumns.DATE_ADDED,
        MediaStore.Files.FileColumns.DISPLAY_NAME,
        MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
        MediaStore.Files.FileColumns.WIDTH,
        MediaStore.Files.FileColumns.HEIGHT,
        MediaStore.Files.FileColumns.DURATION,
    )

    override suspend fun fetchGalleryImages(limit: Int): List<GalleryImage> =
        withContext(Dispatchers.IO) {
            val args = Bundle().apply {
                putString(
                    ContentResolver.QUERY_ARG_SQL_SELECTION,
                    "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)",
                )
                putStringArray(
                    ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                    arrayOf(
                        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
                    ),
                )
                putStringArray(
                    ContentResolver.QUERY_ARG_SORT_COLUMNS,
                    arrayOf(MediaStore.Files.FileColumns.DATE_ADDED),
                )
                putInt(
                    ContentResolver.QUERY_ARG_SORT_DIRECTION,
                    ContentResolver.QUERY_SORT_DIRECTION_DESCENDING,
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
                }
            }

            val cursor = context.contentResolver.query(collectionUri, projection, args, null)
                ?: return@withContext emptyList()

            cursor.use { c ->
                val idIdx = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val typeIdx = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                val mimeIdx = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val dateIdx = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
                val displayIdx = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val bucketIdx = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)
                val widthIdx = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.WIDTH)
                val heightIdx = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.HEIGHT)
                val durationIdx = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DURATION)

                val results = ArrayList<GalleryImage>(c.count.coerceAtMost(limit))
                while (c.moveToNext() && results.size < limit) {
                    val id = c.getLong(idIdx)
                    val mediaType = c.getInt(typeIdx)
                    val isVideo = mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    val collectionForItem = if (isVideo) {
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    } else {
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    }
                    val uri = ContentUris.withAppendedId(collectionForItem, id).toString()
                    val mime = c.getString(mimeIdx)
                        ?: if (isVideo) "video/*" else "image/*"
                    val duration = if (isVideo) c.getLong(durationIdx).takeIf { it > 0 } else null

                    results.add(
                        GalleryImage(
                            id = id.toString(),
                            file = PlatformFile(uri),
                            thumbnailUri = uri,
                            dateAdded = c.getLong(dateIdx),
                            mimeType = mime,
                            galleryName = c.getString(bucketIdx) ?: "",
                            fileName = c.getString(displayIdx) ?: "",
                            durationMs = duration,
                            width = c.getInt(widthIdx),
                            height = c.getInt(heightIdx),
                        )
                    )
                }
                results
            }
        }
}
