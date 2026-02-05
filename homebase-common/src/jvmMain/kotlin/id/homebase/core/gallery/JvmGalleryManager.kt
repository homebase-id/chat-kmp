package id.homebase.core.gallery

import io.github.vinceglb.filekit.PlatformFile

class JvmGalleryManager: PlatformGalleryManager {
    override suspend fun fetchGalleryImages(limit: Int): List<PlatformFile> {
        return emptyList()
    }
}