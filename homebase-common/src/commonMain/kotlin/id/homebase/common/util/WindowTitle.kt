package id.homebase.common.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where the user is, for the desktop window's title and the browser tab's: "Photos – Homebase Chat". Phones show
 * neither and ignore it. Null names the app alone.
 */
object WindowTitle {
    private val _place = MutableStateFlow<String?>(null)
    val place: StateFlow<String?> = _place.asStateFlow()

    fun of(place: String?, appName: String): String = place?.let { "$it – $appName" } ?: appName

    internal fun set(place: String?) {
        _place.value = place
    }
}

/** Names the window after [place] while this is shown. */
@Composable
fun WindowTitleEffect(place: String) {
    DisposableEffect(place) {
        WindowTitle.set(place)
        onDispose { if (WindowTitle.place.value == place) WindowTitle.set(null) }
    }
}
