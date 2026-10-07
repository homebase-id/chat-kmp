package id.homebase.chat.viewonce

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.runDesktopComposeUiTest
import coil3.ImageLoader
import coil3.PlatformContext
import id.homebase.api.client.eventbus.EventBus
import id.homebase.chat.conversationlist.AttachmentPendingFile
import id.homebase.chat.image.PlatformFileFetcher
import id.homebase.chat.services.LocalAttachmentContextStore
import id.homebase.chat.widget.InMemorySettings
import id.homebase.chat.widget.MediaAttachmentEditor
import id.homebase.core.settings.UserPreferences
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class ViewOnceAddMoreTest {

    private val koin = koinApplication {
        modules(module {
            single { UserPreferences(InMemorySettings()) }
            single { ImageLoader.Builder(PlatformContext.INSTANCE).components { add(PlatformFileFetcher.Factory()) }.build() }
            single { LocalAttachmentContextStore(EventBus(), CoroutineScope(SupervisorJob())) }
        })
    }

    private val photo = PlatformFile(File("../homebase-api/src/jvmTest/resources/test_images/red-leaf.jpg").absolutePath)

    private fun addButtonsEnabled(addMoreEnabled: Boolean): List<Boolean> {
        val enabled = mutableListOf<Boolean>()
        runDesktopComposeUiTest {
            setContent {
                KoinIsolatedContext(koin) {
                    MaterialTheme {
                        MediaAttachmentEditor(
                            attachments = listOf(AttachmentPendingFile.FileImage(id = Uuid.random(), file = photo)),
                            currentPage = 0,
                            onPageChanged = {},
                            onAddImage = {},
                            onCameraClick = {},
                            onRemoveFile = {},
                            addMoreEnabled = addMoreEnabled,
                        )
                    }
                }
            }
            waitForIdle()
            val nodes = onAllNodes(hasContentDescription("Add image")).fetchSemanticsNodes()
            nodes.forEach { enabled += !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) }
        }
        return enabled
    }

    @Test
    fun whileTheToggleIsOnBothAddMoreButtonsAreDisabled() {
        val states = addButtonsEnabled(addMoreEnabled = false)
        assertEquals(2, states.size, "the camera and the + both add another item")
        assertEquals(listOf(false, false), states)
    }

    @Test
    fun withTheToggleOffBothAddMoreButtonsWork() {
        assertEquals(listOf(true, true), addButtonsEnabled(addMoreEnabled = true))
    }
}
