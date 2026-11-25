package id.homebase.photobase.gallery

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class OpenGallery(val store: PhotoGalleryStore, val days: PhotoDayIndex)

/**
 * Each gallery's store and day index, made when it's first asked for. A day index follows the drive's changes for as
 * long as it's held, so an album's goes once [keptAlbums] others were opened since, unless it's on screen.
 */
class PhotoGalleries(
    private val scope: CoroutineScope,
    private val keptAlbums: Int = 4,
    private val open: (PhotoGallery, CoroutineScope) -> OpenGallery,
) {
    private val lock = SynchronizedObject()
    private class Held(val gallery: OpenGallery, val scope: CoroutineScope)

    // Least recently asked for first.
    private val held = LinkedHashMap<PhotoGallery, Held>()

    fun of(gallery: PhotoGallery): OpenGallery = synchronized(lock) {
        val opened = held.remove(gallery) ?: run {
            val own = if (gallery is PhotoGallery.Album) CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job])) else scope
            Held(open(gallery, own), own)
        }
        held[gallery] = opened
        evict(keep = gallery)
        opened.gallery
    }

    /** Photobase is gone: every gallery counts again from scratch. */
    suspend fun forget() {
        for (opened in synchronized(lock) { held.values.map { it.gallery } }) opened.days.forget()
    }

    // Not the one just asked for: its screen hasn't started watching yet.
    private fun evict(keep: PhotoGallery) {
        val albums = held.entries.filter { it.key is PhotoGallery.Album }
        var over = albums.size - keptAlbums
        for ((gallery, opened) in albums) {
            if (over <= 0) break
            if (gallery == keep || opened.gallery.days.watched) continue
            held.remove(gallery)
            opened.scope.cancel()
            over--
        }
    }
}
