package id.homebase.chat.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/**
 * Desktop has no GPS — the picker UI is hidden by the `isMobile()` gate in the chat composer,
 * so this should never be invoked in normal use. If it ever is (e.g. a future shortcut), we
 * deliver `null` to keep the contract honest rather than throwing.
 */
@Composable
actual fun rememberCurrentLocationLauncher(
    onResult: (LocationFix?) -> Unit,
): LocationLauncher {
    val onResultState = rememberUpdatedState(onResult)
    return remember {
        object : LocationLauncher {
            override fun launch() {
                onResultState.value(null)
            }
        }
    }
}
