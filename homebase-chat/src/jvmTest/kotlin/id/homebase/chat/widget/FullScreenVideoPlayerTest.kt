package id.homebase.chat.widget

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import coil3.ImageLoader
import coil3.PlatformContext
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.SecureByteArray
import id.homebase.chat.conversationlist.FullScreenOverlay
import id.homebase.chat.services.LocalAttachmentContext
import id.homebase.chat.services.LocalAttachmentContextStore
import id.homebase.core.ui.theme.HomebaseTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.io.File
import kotlin.test.Test
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class FullScreenVideoPlayerTest {

    private val videoFile = File.createTempFile("trimmed", ".mp4").also { it.deleteOnExit() }
    private val messageId = Uuid.random()
    private val store = LocalAttachmentContextStore(EventBus(), CoroutineScope(SupervisorJob()))
    private val koin = koinApplication {
        modules(module {
            single { store }
            single { ImageLoader.Builder(PlatformContext.INSTANCE).build() }
        })
    }

    private fun data(key: String) = FullScreenOverlay.VideoPlayerData(
        fileId = Uuid.random(),
        driveId = Uuid.random(),
        payloadKey = key,
        keyHeader = KeyHeader(iv = ByteArray(16), aesKey = SecureByteArray(ByteArray(16))),
        payload = PayloadDescriptor(key = key, contentType = "video/mp4"),
        localFilePath = videoFile.absolutePath,
        uploadMessageId = messageId,
    )

    private fun putVideo(key: String, trimmed: Boolean) = store.put(
        messageId,
        key,
        LocalAttachmentContext.Video(
            thumbnailBytes = ByteArray(0),
            localFilePath = videoFile.absolutePath,
            aspectRatio = 1.78f,
            trimStartMs = if (trimmed) 1_000L else null,
            trimEndMs = if (trimmed) 5_000L else null,
            durationMs = 10_000L,
        ),
    )

    @Test
    fun trimmedLocalVideo_tapPauses_playButtonResumes() = runComposeUiTest {
        putVideo("v1", trimmed = true)
        setContent {
            KoinIsolatedContext(koin) {
                HomebaseTheme(darkTheme = false) {
                    FullScreenVideoPlayer(data = data("v1"), onDismiss = {})
                }
            }
        }
        onAllNodesWithTag("fullScreenVideoPlay").assertCountEquals(0)

        onNodeWithTag("fullScreenVideoTap").performClick()
        onNodeWithTag("fullScreenVideoPlay").assertExists()

        onNodeWithTag("fullScreenVideoPlay").performClick()
        onAllNodesWithTag("fullScreenVideoPlay").assertCountEquals(0)
    }
}
