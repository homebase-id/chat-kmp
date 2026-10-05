package id.homebase.core.ui.screens.notifications

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class NotificationSettingsCopyFeedbackTest {

    private class RecordingClipboard : Clipboard {
        var entry: ClipEntry? = null
        override val nativeClipboard = java.awt.datatransfer.Clipboard("test")
        override suspend fun getClipEntry(): ClipEntry? = entry
        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            entry = clipEntry
        }
        fun text(): String? =
            (entry?.nativeClipEntry as? Transferable)?.getTransferData(DataFlavor.stringFlavor) as? String
    }

    @Test
    fun copyingTheDeviceTokenConfirmsWithASnackbar() = runComposeUiTest {
        val clipboard = RecordingClipboard()
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalClipboard provides clipboard) {
                    NotificationSettingsUi(
                        uiState = NotificationSettingsUiState(
                            showDebugInfo = true,
                            deviceToken = "fcm-token-0123456789",
                        ),
                        onAction = {},
                        onBackClick = {},
                        onOpenSystemSettings = {},
                    )
                }
            }
        }
        onNodeWithContentDescription("Copy token").performClick()
        waitForIdle()
        assertEquals("fcm-token-0123456789", clipboard.text())
        onNodeWithText("Copied to clipboard").assertExists()
    }
}
