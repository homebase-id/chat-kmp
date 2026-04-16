package id.homebase.chat.widget

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import id.homebase.api.client.drives.files.PayloadDescriptor
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DocumentMediaItemTest {

    @Test
    fun displaysFileName() = runComposeUiTest {
        setContent {
            MaterialTheme {
                DocumentMediaItem(
                    payload = PayloadDescriptor(
                        key = "payload-key",
                        contentType = "application/pdf",
                        descriptorContent = "report.pdf",
                        bytesWritten = 1024 * 512,
                    ),
                    onDownloadClick = {},
                    onLongPress = {},
                )
            }
        }
        onNodeWithText("report.pdf").assertExists()
    }

    @Test
    fun displaysFileSize() = runComposeUiTest {
        setContent {
            MaterialTheme {
                DocumentMediaItem(
                    payload = PayloadDescriptor(
                        key = "payload-key",
                        contentType = "application/pdf",
                        descriptorContent = "report.pdf",
                        bytesWritten = 1024 * 1024 * 2,
                    ),
                    onDownloadClick = {},
                    onLongPress = {},
                )
            }
        }
        // 2 MB file — formatFileSize drops trailing .0
        onNodeWithText("2 MB").assertExists()
    }

    @Test
    fun usesKeyAsFallbackName_whenNoDescriptorContent() = runComposeUiTest {
        setContent {
            MaterialTheme {
                DocumentMediaItem(
                    payload = PayloadDescriptor(
                        key = "fallback-key",
                        contentType = "application/pdf",
                    ),
                    onDownloadClick = {},
                    onLongPress = {},
                )
            }
        }
        onNodeWithText("fallback-key").assertExists()
    }

    @Test
    fun downloadClickFires() = runComposeUiTest {
        var clicked = false
        setContent {
            MaterialTheme {
                DocumentMediaItem(
                    payload = PayloadDescriptor(
                        key = "key",
                        contentType = "application/pdf",
                        descriptorContent = "file.pdf",
                    ),
                    onDownloadClick = { clicked = true },
                    onLongPress = {},
                )
            }
        }
        onNodeWithTag("downloadButton").performClick()
        assertTrue(clicked)
    }

    @Test
    fun rendersInDownloadingState() = runComposeUiTest {
        setContent {
            MaterialTheme {
                DocumentMediaItem(
                    payload = PayloadDescriptor(
                        key = "key",
                        contentType = "application/zip",
                        descriptorContent = "archive.zip",
                    ),
                    onDownloadClick = {},
                    onLongPress = {},
                    isDownloading = true,
                )
            }
        }
        onNodeWithText("archive.zip").assertExists()
        // Download icon should be replaced by progress indicator
        onNodeWithTag("downloadButtonIcon").assertDoesNotExist()
    }
}
