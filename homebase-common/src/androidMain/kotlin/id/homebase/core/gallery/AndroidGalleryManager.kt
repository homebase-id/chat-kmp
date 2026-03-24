package id.homebase.core.gallery

import  android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidGalleryManager(val context: Context): PlatformGalleryManager {
    override suspend fun fetchGalleryImages(limit: Int): List<GalleryImage> = withContext(Dispatchers.IO) {
        val images = queryMediaStore(
            collectionUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            idColumn = MediaStore.Images.Media._ID,
            dateColumn = MediaStore.Images.Media.DATE_ADDED,
            mimeColumn = MediaStore.Images.Media.MIME_TYPE,
            bucketColumn = MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            displayNameColumn = MediaStore.Images.Media.DISPLAY_NAME,
        )
        val videos = queryMediaStore(
            collectionUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            idColumn = MediaStore.Video.Media._ID,
            dateColumn = MediaStore.Video.Media.DATE_ADDED,
            mimeColumn = MediaStore.Video.Media.MIME_TYPE,
            bucketColumn = MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            displayNameColumn = MediaStore.Video.Media.DISPLAY_NAME,
        )
        (images + videos)
            .sortedByDescending { it.dateAdded }
            .take(limit)
    }

    private fun queryMediaStore(
        collectionUri: android.net.Uri,
        idColumn: String,
        dateColumn: String,
        mimeColumn: String,
        bucketColumn: String,
        displayNameColumn: String,
    ): List<GalleryImage> {
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
                        mimeType = cursor.getString(mimeIdx),
                        galleryName = cursor.getString(bucketIdx),
                        fileName = cursor.getString(displayNameIdx),
                    )
                )
            }
        }
        return results
    }
}