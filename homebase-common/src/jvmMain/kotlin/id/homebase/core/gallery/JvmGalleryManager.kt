package id.homebase.core.gallery

class JvmGalleryManager: PlatformGalleryManager {
    override suspend fun fetchGalleryImages(limit: Int): List<GalleryImage> {
        return emptyList()
    }
}