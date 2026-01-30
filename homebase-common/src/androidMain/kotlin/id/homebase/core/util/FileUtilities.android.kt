package id.homebase.core.util

import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import kotlinx.io.files.Path

private const val TAG = "getUriHandler"

@Composable
actual fun getUriHandler(): FileSystemHandler {
    val context = LocalContext.current

    return remember {
        object : FileSystemHandler {
            override fun openUrl(url: String, onError: (Throwable) -> Unit) {
                val customTabsIntent =
                    CustomTabsIntent.Builder()
                        .setColorScheme(CustomTabsIntent.COLOR_SCHEME_SYSTEM)
                        .setShowTitle(true)
                        .build()

                customTabsIntent.launchUrl(context, url.toUri())
            }

            override fun editFile(
                file: Path,
                showChooser: Boolean,
                onError: (Throwable) -> Unit
            ) {
                TODO("Not yet implemented")
            }

            override fun openFile(
                file: Path,
                showChooser: Boolean,
                onError: (Throwable) -> Unit
            ) {
                TODO("Not yet implemented")
            }

            override fun openFileBrowser(
                file: Path,
                onError: (Throwable) -> Unit
            ) {
                TODO("Not yet implemented")
            }

            override fun shareFile(
                file: Path,
                onError: (Throwable) -> Unit
            ) {
                TODO("Not yet implemented")
            }

            override fun openAppStore(onError: (Throwable) -> Unit) {
                TODO("Not yet implemented")
            }
        }
    }
}

