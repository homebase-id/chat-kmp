package id.homebase.core.ui.screens.storage

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import id.homebase.api.client.cache.CacheStats
import id.homebase.core.test.setTestLocale
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class StorageSettingsUiTest {

    private val healthyPayload = CacheRowState(
        id = "drive_payloads",
        sizeBytes = 1024L,
        maxBytes = 200L * 1024L * 1024L,
    )
    private val unavailableThumbs = CacheRowState(
        id = "drive_thumbnails",
        sizeBytes = CacheStats.UNAVAILABLE,
        maxBytes = 0L,
    )
    private val healthyThumbs = CacheRowState(
        id = "drive_thumbnails",
        sizeBytes = 2048L,
        maxBytes = 300L * 1024L * 1024L,
    )

    @Test
    fun `shows red banner and Unavailable row when a cache is unavailable`() = runComposeUiTest {
        setTestLocale("en-US")
        setContent {
            MaterialTheme {
                StorageSettingsUi(
                    uiState = StorageSettingsUiState(
                        caches = listOf(healthyPayload, unavailableThumbs),
                        isLoading = false,
                    ),
                    onAction = {},
                    onBackClick = {},
                    onNavigateToDefragmenter = {},
                    snackbarHostState = remember { SnackbarHostState() },
                )
            }
        }
        onNodeWithText("Some caches could not be opened").assertExists()
        onNodeWithText("Unavailable").assertExists()
    }

    @Test
    fun `hides red banner when all caches are healthy`() = runComposeUiTest {
        setTestLocale("en-US")
        setContent {
            MaterialTheme {
                StorageSettingsUi(
                    uiState = StorageSettingsUiState(
                        caches = listOf(healthyPayload, healthyThumbs),
                        isLoading = false,
                    ),
                    onAction = {},
                    onBackClick = {},
                    onNavigateToDefragmenter = {},
                    snackbarHostState = remember { SnackbarHostState() },
                )
            }
        }
        onNodeWithText("Some caches could not be opened").assertDoesNotExist()
        onNodeWithText("Unavailable").assertDoesNotExist()
    }

    @Test
    fun `Clear caches button is enabled when an unavailable row is present`() = runComposeUiTest {
        setTestLocale("en-US")
        setContent {
            MaterialTheme {
                StorageSettingsUi(
                    uiState = StorageSettingsUiState(
                        caches = listOf(healthyPayload, unavailableThumbs),
                        isLoading = false,
                    ),
                    onAction = {},
                    onBackClick = {},
                    onNavigateToDefragmenter = {},
                    snackbarHostState = remember { SnackbarHostState() },
                )
            }
        }
        onNodeWithText("Clear caches").assertIsEnabled()
    }
}
