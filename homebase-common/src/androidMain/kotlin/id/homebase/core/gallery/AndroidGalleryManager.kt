package id.homebase.core.gallery

import  android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidGalleryManager(val context: Context): PlatformGalleryManager {
    override suspend fun fetchGalleryImages(limit: Int): List<GalleryImage> = withContext(Dispatchers.IO) {
        val images = queryMediaStore(collectionUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        val videos = queryMediaStore(collectionUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        (images + videos)
            .sortedByDescending { it.dateAdded }
            .take(limit)
    }

    private fun queryMediaStore(collectionUri: android.net.Uri): List<GalleryImage> {
        val idColumn = MediaStore.MediaColumns._ID
        val dateColumn = MediaStore.MediaColumns.DATE_ADDED
        val mimeColumn = MediaStore.MediaColumns.MIME_TYPE
        val bucketColumn = MediaStore.MediaColumns.BUCKET_DISPLAY_NAME
        val displayNameColumn = MediaStore.MediaColumns.DISPLAY_NAME

        val results = mutableListOf<GalleryImage>()
        val projection = arrayOf(idColumn, dateColumn, mimeColumn, bucketColumn, displayNameColumn)
        context.contentResolver.query(
            collectionUri,
            projection,
            null,
            null,
            "$dateColumn DESC"
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(idColumn)
            val dateIdx = cursor.getColumnIndexOrThrow(dateColumn)
            val mimeIdx = cursor.getColumnIndexOrThrow(mimeColumn)
            val bucketIdx = cursor.getColumnIndexOrThrow(bucketColumn)
            val displayNameIdx = cursor.getColumnIndexOrThrow(displayNameColumn)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIdx)
                val uri = ContentUris.withAppendedId(collectionUri, id).toString()
                results.add(
                    GalleryImage(
                        id = id.toString(),
                        file = PlatformFile(uri),
                        thumbnailUri = uri,
                        dateAdded = cursor.getLong(dateIdx),
                        mimeType = cursor.getString(mimeIdx) ?: "",
                        galleryName = cursor.getString(bucketIdx) ?: "",
                        fileName = cursor.getString(displayNameIdx) ?: "",
                    )
                )
            }
        }
        return results
    }
}