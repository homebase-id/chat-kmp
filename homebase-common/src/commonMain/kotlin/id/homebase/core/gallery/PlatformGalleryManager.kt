package id.homebase.core.gallery

import androidx.compose.runtime.Immutable
import io.github.vinceglb.filekit.PlatformFile

interface PlatformGalleryManager {
    suspend fun fetchGalleryImages(limit: Int = 50): List<GalleryImage>
}

@Immutable
data class GalleryImage(
    val id: String,
    val file: PlatformFile,
    val thumbnailUri: String? = null,
    val dateAdded: Long,
    val mimeType: String,
    val fileName: String,
    val galleryName: String,
)