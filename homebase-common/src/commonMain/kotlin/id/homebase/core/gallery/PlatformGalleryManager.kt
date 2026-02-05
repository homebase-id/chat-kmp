package id.homebase.core.gallery

import androidx.compose.runtime.Immutable
import io.github.vinceglb.filekit.PlatformFile

interface PlatformGalleryManager {
    suspend fun fetchGalleryImages(limit: Int = 50): List<PlatformFile>
}

@Immutable
data class GalleryImage(
    val id: String,
    val uri: String,
    val thumbnailUri: String? = null,
    val dateAdded: Long,
    val mimeType: String
)