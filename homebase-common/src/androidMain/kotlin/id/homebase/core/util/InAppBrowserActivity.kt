package id.homebase.core.util

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import id.homebase.core.config.AppConfig
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.resources.MR
import id.homebase.resources.close
import id.homebase.resources.in_app_browser_secure
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
        val uri = url.toUri()
        val host = uri.host.orEmpty()
        val isSecure = uri.scheme.equals("https", ignoreCase = true)

        setContent {
            HomebaseTheme {
                var pageTitle by remember { mutableStateOf(host) }
                var progress by remember { mutableFloatStateOf(0f) }
                val surface = MaterialTheme.colorScheme.surface

                Scaffold(
                    topBar = {
                        Column {
                            TopAppBar(
                                title = {
                                    Column {
                                        Text(
                                            text = pageTitle,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            if (isSecure) {
                                                Icon(
                                                    imageVector = Icons.Default.Lock,
                                                    contentDescription = stringResource(MR.string.in_app_browser_secure),
                                                    modifier = Modifier.size(12.dp),
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            Text(
                                                text = host,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                },
                                navigationIcon = {
                                    IconButton(onClick = ::finish) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = stringResource(MR.string.close)
                                        )
                                    }
                                },
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                ),
                            )
                            AnimatedVisibility(visible = progress < 1f) {
                                LinearProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    AndroidView(
                        modifier = Modifier.fillMaxSize().padding(innerPadding),
                        factory = { context ->
                            WebView(context).apply {
                                setBackgroundColor(surface.toArgb())
                                webViewClient = object : WebViewClient() {
                                    // Sign-up ends by navigating at our own scheme. A WebView
                                    // can't load that and doesn't need to — this activity is
                                    // the thing being addressed, so read it here and close,
                                    // rather than bouncing out through the system.
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView,
                                        request: WebResourceRequest,
                                    ): Boolean {
                                        val target = request.url
                                        if (!target.scheme.equals(
                                                AppConfig.DEEP_LINK_SCHEME,
                                                ignoreCase = true,
                                            )
                                        ) {
                                            return false
                                        }

                                        if (target.host == AppConfig.CREATE_ACCOUNT_CALLBACK_HOST) {
                                            target.getQueryParameter("domain")
                                                ?.takeIf { it.isNotBlank() }
                                                ?.let { CreatedIdentityRelay.deliver(it) }
                                        }
                                        this@InAppBrowserActivity.finish()
                                        return true
                                    }
                                }
                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                        progress = newProgress / 100f
                                    }

                                    // WebView falls back to the raw URL when a page has no <title>.
                                    override fun onReceivedTitle(view: WebView?, title: String?) {
                                        if (!title.isNullOrBlank() && !title.startsWith("http")) {
                                            pageTitle = title
                                        }
                                    }
                                }
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
