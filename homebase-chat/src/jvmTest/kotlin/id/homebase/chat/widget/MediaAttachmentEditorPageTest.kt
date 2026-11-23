package id.homebase.chat.widget

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import coil3.ImageLoader
import coil3.PlatformContext
import id.homebase.api.client.contacts.AvatarResolver
import id.homebase.api.client.eventbus.EventBus
import id.homebase.chat.conversationlist.AttachmentPendingFile
import id.homebase.chat.image.PlatformFileFetcher
import id.homebase.chat.services.LocalAttachmentContextStore
import id.homebase.core.settings.UserPreferences
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class MediaAttachmentEditorPageTest {

    private val koin = koinApplication {
        modules(module {
            single { AvatarResolver(MutableStateFlow(emptyList())) }
            single { UserPreferences(InMemorySettings()) }
            single { ImageLoader.Builder(PlatformContext.INSTANCE).components { add(PlatformFileFetcher.Factory()) }.build() }
            single { LocalAttachmentContextStore(EventBus(), CoroutineScope(SupervisorJob())) }
        })
    }

    private val photo = PlatformFile(File("../homebase-api/src/jvmTest/resources/test_images/red-leaf.jpg").absolutePath)

    private fun image() = AttachmentPendingFile.FileImage(id = Uuid.random(), file = photo)

    @Test
    fun addingAndRemovingAttachmentsKeepsThePagerOnTheRightPage() = runDesktopComposeUiTest {
        val a = image()
        val b = image()
        val c = image()
        val labels = mapOf(a.attachmentId to "Page A", b.attachmentId to "Page B", c.attachmentId to "Page C")
        var attachments by mutableStateOf(listOf<AttachmentPendingFile>(a, b))
        var page by mutableStateOf(1)
        setContent {
            KoinIsolatedContext(koin) {
                MaterialTheme {
                    MediaAttachmentEditor(
                        attachments = attachments,
                        currentPage = page,
                        onPageChanged = { page = it },
                        imageOverlay = { Text(labels.getValue(it.attachmentId)) },
                    )
                }
            }
        }
        onNodeWithText("Page B").assertIsDisplayed()

        attachments = attachments + c
        waitForIdle()
        assertEquals(1, page)
        onNodeWithText("Page B").assertIsDisplayed()

        attachments = attachments - b
        waitForIdle()
        assertEquals(1, page)
        onNodeWithText("Page C").assertIsDisplayed()

        attachments = attachments - c
        page = page.coerceAtMost(attachments.lastIndex)
        waitForIdle()
        assertEquals(0, page)
        onNodeWithText("Page A").assertIsDisplayed()
    }
}
