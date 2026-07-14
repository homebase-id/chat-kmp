package id.homebase.core.util

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.resources.MR
import id.homebase.resources.close
import org.jetbrains.compose.resources.stringResource

/**
 * An in-app browser running in the app's own task, so system Back and the Close button always
 * return to the caller. Chrome Custom Tabs delegate that guarantee to whichever browser is
 * default: its activity's task placement is outside our control, and on some devices backing out
 * lands on the launcher instead of the app (#1089).
 */
// ponytail: Back exits outright instead of walking WebView history — an SPA's pushState entries
// would make history-back an unbounded trap, which is the thing this activity exists to prevent.
class InAppBrowserActivity : ComponentActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            finish()
            return
        }
        val host = url.toUri().host.orEmpty()

        setContent {
            HomebaseTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(host) },
                            navigationIcon = {
                                IconButton(onClick = ::finish) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = stringResource(MR.string.close)
                                    )
                                }
                            }
                        )
                    }
                ) { innerPadding ->
                    AndroidView(
                        modifier = Modifier.fillMaxSize().padding(innerPadding),
                        factory = { context ->
                            WebView(context).apply {
                                webViewClient = WebViewClient()
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                loadUrl(url)
                            }
                        }
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_URL = "url"
    }
}
