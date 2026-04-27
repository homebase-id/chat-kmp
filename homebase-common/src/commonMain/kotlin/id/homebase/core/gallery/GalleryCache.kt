package id.homebase.core.gallery

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

/**
 * Application-scoped cache of recent device gallery items.
 *
 * Reopening the picker should be instant: rather than re-querying MediaStore/Photos
 * on every sheet open, we keep the last result around and refresh it lazily when the
 * platform reports a library change (registered by the DI module per-platform).
 */
@OptIn(FlowPreview::class)
class GalleryCache(
    private val manager: PlatformGalleryManager,
    private val limit: Int = DEFAULT_LIMIT,
) {
    private val _items = MutableStateFlow<List<GalleryImage>>(emptyList())
    val items: StateFlow<List<GalleryImage>> = _items.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val refreshTrigger = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    init {
        scope.launch {
            refreshTrigger
                .onStart { emit(Unit) }
                .debounce(REFRESH_DEBOUNCE_MS)
                .collectLatest {
                    runCatching { manager.fetchGalleryImages(limit) }
                        .onSuccess { _items.value = it }
                }
        }
    }

    /** Coalesced refresh; safe to call from any thread, multiple times rapidly. */
    fun refresh() {
        refreshTrigger.tryEmit(Unit)
    }

    companion object {
        const val DEFAULT_LIMIT = 100
        private const val REFRESH_DEBOUNCE_MS = 250L
    }
}
