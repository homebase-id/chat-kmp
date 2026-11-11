package id.homebase.core.ui.screens.feed

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.core.feed.services.FeedPostItem
import id.homebase.core.feed.services.PostType
import id.homebase.core.feed.services.authorOdinId
import id.homebase.core.settings.ThemeState
import id.homebase.core.settings.UserPreferences
import id.homebase.core.util.getUriHandler
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

/**
 * The feed web app's post page, served from the signed-in identity's host so the WebView's auth applies; a
 * post on another identity is addressed through [authorDomain] (the web app reads it over peer). Null for
 * every post type except [PostType.Article], which stay native.
 */
internal fun articleWebUrl(
    ownDomain: String,
    authorDomain: String?,
    type: PostType,
    channelId: String,
    slug: String,
    postId: String,
): String? {
    if (type != PostType.Article || channelId.isBlank()) return null
    val identity = authorDomain ?: ownDomain
    val post = slug.ifBlank { postId }
    return "https://$ownDomain/apps/feed/preview/" +
        "${identity.encodeURLPathPart()}/${channelId.encodeURLPathPart()}/${post.encodeURLPathPart()}"
}

internal data class ArticleWebUiState(
    val url: String? = null,
    val injectionScript: String? = null,
    val error: String? = null,
)

class ArticleWebViewModel(
    private val credentialsManager: CredentialsManager,
    private val userPreferences: UserPreferences,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ArticleWebUiState())
    internal val uiState: StateFlow<ArticleWebUiState> = _uiState.asStateFlow()

    internal fun load(post: FeedPostItem, isSystemDark: Boolean) {
        viewModelScope.launch {
            try {
                val credentials = credentialsManager.requireActiveCredentials()
                val url = articleWebUrl(
                    ownDomain = credentials.domain.domainName,
                    authorDomain = post.authorOdinId?.domainName,
                    type = post.type,
                    channelId = post.channelId,
                    slug = post.slug,
                    postId = post.id.toString(),
                )
                val isDark = when (userPreferences.theme) {
                    ThemeState.Dark -> true
                    ThemeState.Light -> false
                    ThemeState.System -> isSystemDark
                }
                _uiState.value = ArticleWebUiState(
                    url = url,
                    injectionScript = buildFeedInjectionScript(
                        sharedSecretBase64 = credentials.sharedSecret.base64Encode(),
                        authToken = credentials.clientAccessToken,
                        isDark = isDark,
                    ),
                    error = if (url == null) "Not an article" else null,
                )
            } catch (e: Exception) {
                Logger.e(e, tag = "ArticleWebViewModel") { "Failed to prepare the article page" }
                _uiState.update { it.copy(error = e.message?.takeIf { m -> m.isNotBlank() } ?: "Failed to load the article.") }
            }
        }
    }
}

/** Embedded WebView where the platform has one; otherwise the article opens in the browser and [onClosed] fires. */
@Composable
internal fun ArticleWebContent(
    post: FeedPostItem,
    isSystemDark: Boolean,
    onClosed: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ArticleWebViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val uriHandler = getUriHandler()
    LaunchedEffect(post.id) { viewModel.load(post, isSystemDark) }

    val url = state.url
    if (!FeedWebViewAvailable) {
        LaunchedEffect(url) {
            if (url != null) {
                uriHandler.openUrl(url)
                onClosed()
            }
        }
        Box(modifier.fillMaxSize())
        return
    }

    var pageError by remember(url) { mutableStateOf<String?>(null) }
    var isLoading by remember(url) { mutableStateOf(false) }
    val error = state.error ?: pageError
    when {
        error != null -> FeedErrorView(errorMessage = error, onRetry = { viewModel.load(post, isSystemDark) })
        url != null -> FeedWebView(
            url = url,
            injectionScript = state.injectionScript,
            isLoading = isLoading,
            onAction = { action ->
                when (action) {
                    FeedUiAction.PageStarted -> isLoading = true
                    FeedUiAction.PageFinished -> isLoading = false
                    is FeedUiAction.PageError -> pageError = action.message
                    FeedUiAction.RetryClicked -> Unit
                }
            },
        )
        else -> Box(modifier.fillMaxSize())
    }
}
