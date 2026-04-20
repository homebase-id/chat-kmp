package id.homebase.core.ui.screens.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.core.settings.ThemeState
import id.homebase.core.settings.UserPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class FeedViewModel(
    private val credentialsManager: CredentialsManager,
    private val userPreferences: UserPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FeedUiState())
    val uiState: StateFlow<FeedUiState> = _uiState.asStateFlow()

    init {
        loadFeed()
    }

    fun onAction(action: FeedUiAction) {
        when (action) {
            FeedUiAction.RetryClicked -> loadFeed()
            FeedUiAction.PageStarted -> _uiState.update { it.copy(isLoading = true) }
            FeedUiAction.PageFinished -> _uiState.update { it.copy(isLoading = false) }
            is FeedUiAction.PageError -> _uiState.update {
                it.copy(isLoading = false, error = action.message)
            }
        }
    }

    fun updateSystemDarkMode(isSystemDark: Boolean) {
        val effectiveDark = when (userPreferences.theme) {
            ThemeState.System -> isSystemDark
            ThemeState.Dark -> true
            ThemeState.Light -> false
        }
        val current = _uiState.value
        if (current.isDarkMode != effectiveDark) {
            _uiState.update { it.copy(isDarkMode = effectiveDark) }
            if (current.credentialsReady) {
                rebuildInjectionScript()
            }
        }
    }

    private fun loadFeed() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val credentials = credentialsManager.requireActiveCredentials()
                val domain = credentials.domain.domainName
                val token = credentials.clientAccessToken
                val sharedSecretBase64 = credentials.sharedSecret.base64Encode()

                val isDark = when (userPreferences.theme) {
                    ThemeState.Dark -> true
                    ThemeState.Light -> false
                    ThemeState.System -> _uiState.value.isDarkMode
                }

                val feedUrl = "https://$domain/apps/feed"
                val script = buildInjectionScript(
                    sharedSecretBase64 = sharedSecretBase64,
                    authToken = token,
                    isDark = isDark,
                )

                _uiState.update {
                    it.copy(
                        feedUrl = feedUrl,
                        injectionScript = script,
                        isDarkMode = isDark,
                        credentialsReady = true,
                    )
                }
            } catch (e: Exception) {
                Logger.e(e, tag = TAG) { "Failed to load feed credentials" }
                val errorMessage = e.message?.takeIf { it.isNotBlank() } ?: "Failed to load feed credentials."
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        credentialsReady = false,
                        error = errorMessage,
                    )
                }
            }
        }
    }

    private fun rebuildInjectionScript() {
        viewModelScope.launch {
            try {
                val credentials = credentialsManager.requireActiveCredentials()
                val script = buildInjectionScript(
                    sharedSecretBase64 = credentials.sharedSecret.base64Encode(),
                    authToken = credentials.clientAccessToken,
                    isDark = _uiState.value.isDarkMode,
                )
                _uiState.update { it.copy(injectionScript = script) }
            } catch (e: Exception) {
                Logger.e(e, tag = TAG) { "Failed to rebuild injection script" }
            }
        }
    }

    companion object {
        private const val TAG = "FeedViewModel"

        /** Escape a string for safe inclusion in a JS single-quoted string literal. */
        private fun escapeForJs(value: String): String =
            value.replace("\\", "\\\\").replace("'", "\\'")

        private fun buildInjectionScript(
            sharedSecretBase64: String,
            authToken: String,
            isDark: Boolean,
        ): String {
            val safeSecret = escapeForJs(sharedSecretBase64)
            val safeToken = escapeForJs(authToken)
            return """
                (function() {
                    try {
                        localStorage.setItem('APPS_feed', '$safeSecret');
                        localStorage.setItem('BX0900_feed', '$safeToken');
                        localStorage.setItem('client_type', 'react-native-v2');
                        localStorage.setItem('prefersDark', '${if (isDark) "1" else "0"}');
                    } catch(e) { console.error('Feed injection error:', e); }
                })();
            """.trimIndent()
        }
    }
}
