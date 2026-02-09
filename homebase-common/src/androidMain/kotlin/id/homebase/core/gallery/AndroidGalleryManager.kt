package id.homebase.core.gallery

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidGalleryManager(val context: Context): PlatformGalleryManager {
    override suspend fun fetchGalleryImages(limit: Int): List<GalleryImage> = withContext(Dispatchers.IO) {
        val images = mutableListOf<GalleryImage>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        )

        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val bucketColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)

            var count = 0
            while (cursor.moveToNext() && count < limit) {
                val id = cursor.getLong(idColumn)
                val uri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id
                ).toString()

                images.add(GalleryImage(
                    id = id.toString(),
                    file = PlatformFile(uri),
                    dateAdded = cursor.getLong(dateColumn),
                    mimeType = cursor.getString(mimeColumn),
                    galleryName = cursor.getString(bucketColumn),
                ))
                count++
            }
        }
        images
    }
}