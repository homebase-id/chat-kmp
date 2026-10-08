package id.homebase.core.clipboard

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalClipboard
import id.homebase.resources.MR
import id.homebase.resources.copied_to_clipboard
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
fun rememberCopyToClipboard(
    snackbarHostState: SnackbarHostState,
    sensitive: Boolean = false,
): (String) -> Unit {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val copied = stringResource(MR.string.copied_to_clipboard)
    return remember(clipboard, scope, snackbarHostState, sensitive, copied) {
        { text ->
            scope.launch {
                clipboard.setClipEntry(clipEntryOf(text, sensitive))
                snackbarHostState.showSnackbar(copied)
            }
            Unit
        }
    }
}
