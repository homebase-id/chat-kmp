package id.homebase.chat.viewonce

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.PlatformContext
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import id.homebase.chat.widget.InMemorySettings
import id.homebase.api.client.KeyHeader
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.client.eventbus.EventBus
import id.homebase.chat.conversationlist.AttachmentPendingFile
import id.homebase.chat.conversationlist.MessageClusterPosition
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.image.PlatformFileFetcher
import id.homebase.chat.services.LocalAttachmentContextStore
import id.homebase.chat.services.MessageAppData
import id.homebase.chat.services.content.MessageContent
import id.homebase.chat.widget.LocalCurrentOdinId
import id.homebase.chat.widget.MediaAttachmentEditor
import id.homebase.chat.widget.MessageBubbleRaw
import id.homebase.chat.widget.MessageTextFieldForAttachment
import id.homebase.chat.widget.isViewOnceEligible
import id.homebase.core.settings.UserPreferences
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.resources.MR
import id.homebase.resources.chat_view_once_caption_disabled
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Renders the view-once composer and bubbles in every state, light and dark, to PNGs for design
 * review. Set VIEW_ONCE_SHOTS_DIR to write the images; without it the states are only composed.
 */
@OptIn(ExperimentalTestApi::class)
class ViewOnceShotsTest {

    private val outDir: File? = System.getenv("VIEW_ONCE_SHOTS_DIR")?.let(::File)?.also { it.mkdirs() }
    private val only: List<String> = System.getenv("VIEW_ONCE_SHOTS_ONLY")?.split(',')?.map { it.trim() }.orEmpty()

    private val samples = File("../homebase-api/src/jvmTest/resources/test_images")
    private val photo = PlatformFile(File(samples, "red-leaf.jpg").absolutePath)
    private val poster = File(samples, "waterhouse.jpg")

    private fun image() = AttachmentPendingFile.FileImage(id = Uuid.random(), file = photo)
    private fun video() = AttachmentPendingFile.FileVideo(
        id = Uuid.random(),
        file = PlatformFile(poster.absolutePath),
        thumbnailBytes = poster.readBytes(),
    )

    private sealed interface Scene {
        data class Editor(val attachments: List<AttachmentPendingFile>, val viewOnce: Boolean, val caption: String = "") : Scene
        data class Thread(val group: Boolean = false) : Scene
    }

    private class Shot(
        val name: String,
        val scene: Scene,
        val fontScale: Float = 1f,
        val rtl: Boolean = false,
        val widthDp: Int = PHONE_W,
        val heightDp: Int = PHONE_H,
    )

    private val shots by lazy {
        listOf(
            Shot("e1-editor-photo-off", Scene.Editor(listOf(image()), viewOnce = false)),
            Shot("e2-editor-photo-on", Scene.Editor(listOf(image()), viewOnce = true)),
            Shot("e3-editor-video-on", Scene.Editor(listOf(video()), viewOnce = true)),
            Shot("e4-editor-two-photos-hidden", Scene.Editor(listOf(image(), image()), viewOnce = false)),
            Shot("e5-editor-on-font-scale", Scene.Editor(listOf(image()), viewOnce = true), fontScale = 1.6f),
            Shot("e6-editor-on-rtl", Scene.Editor(listOf(image()), viewOnce = true), rtl = true),
            Shot("e7-editor-on-small", Scene.Editor(listOf(image()), viewOnce = true), widthDp = 360, heightDp = 640),
            Shot("e8-editor-on-typed-caption", Scene.Editor(listOf(image()), viewOnce = true, caption = "Don't show anyone")),
            Shot("b1-thread", Scene.Thread()),
            Shot("b2-thread-group-long-name", Scene.Thread(group = true)),
            Shot("b3-thread-font-scale", Scene.Thread(group = true), fontScale = 1.6f, heightDp = 1_100),
            Shot("b4-thread-rtl", Scene.Thread(group = true), rtl = true),
            Shot("b5-thread-small", Scene.Thread(group = true), widthDp = 360, heightDp = 760),
        )
    }

    @Test
    fun viewOnceRendersEveryState() {
        for (dark in listOf(false, true)) {
            for (shot in shots) if (only.isEmpty() || only.any { shot.name.startsWith(it) }) render(shot, dark)
        }
    }

    private val koin = koinApplication {
        modules(module {
            single { UserPreferences(InMemorySettings()) }
            single { ImageLoader.Builder(PlatformContext.INSTANCE).components { add(PlatformFileFetcher.Factory()) }.build() }
            single { LocalAttachmentContextStore(EventBus(), CoroutineScope(SupervisorJob())) }
        })
    }

    @Composable
    private fun Themed(dark: Boolean, fontScale: Float, rtl: Boolean, content: @Composable () -> Unit) {
        KoinIsolatedContext(koin) {
            CompositionLocalProvider(
                LocalDensity provides Density(SCALE, fontScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalCurrentOdinId provides "me.example.com",
            ) {
                HomebaseTheme(darkTheme = dark, followsSystemTheme = false, updatesSystemChrome = false, content = content)
            }
        }
    }

    @Composable
    private fun EditorScene(scene: Scene.Editor) {
        var viewOnceRequested by remember { mutableStateOf(scene.viewOnce) }
        val viewOnce = viewOnceRequested && isViewOnceEligible(scene.attachments)
        val caption = rememberRichTextState()
        LaunchedEffect(Unit) { if (scene.caption.isNotEmpty()) caption.setText(scene.caption) }
        MediaAttachmentEditor(
            attachments = scene.attachments,
            currentPage = 0,
            onPageChanged = {},
            onSaveFile = {},
            onCropImage = {},
            onDrawImage = {},
            onToggleMediaQuality = {},
            viewOnce = viewOnce,
            onToggleViewOnce = { viewOnceRequested = !viewOnceRequested },
            onAddImage = {},
            onRemoveFile = {},
            onDismiss = {},
            bottomBar = {
                MessageTextFieldForAttachment(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    state = caption,
                    onSendMessage = {},
                    captionDisabledText = if (viewOnce) stringResource(MR.string.chat_view_once_caption_disabled) else null,
                    showFormattingToolbar = false,
                )
            },
        )
    }

    private fun message(
        content: MessageContent?,
        text: String,
        sent: Boolean,
        author: String = "alice.example.com",
        name: String = "Alice",
        minute: Int = 0,
    ) = MessageUiModel(
        id = Uuid.random(),
        globalTransitId = null,
        fileId = Uuid.random(),
        conversationId = Uuid.random(),
        content = text,
        userDate = Instant.fromEpochMilliseconds(1_760_000_000_000L + minute * 60_000L),
        modified = null,
        created = Instant.fromEpochMilliseconds(1_760_000_000_000L),
        originalAuthor = if (sent) null else OdinId(author),
        sender = if (sent) null else OdinId(author),
        displayName = name,
        messageAppData = MessageAppData(deliveryStatus = if (sent) 30 else 0),
        reactionPreview = null,
        previewThumbnail = null,
        payloads = persistentListOf(),
        keyHeader = KeyHeader(iv = ByteArray(16), aesKey = SecureByteArray(ByteArray(16))),
        versionTag = Uuid.random(),
        isPendingSend = false,
        messageContent = content,
        hasMore = false,
    )

    @Composable
    private fun ThreadScene(scene: Scene.Thread) {
        val photo = MessageContent.ViewOnce(ViewOnceDescriptor(ViewOnceDescriptor.KIND_IMAGE))
        val videoContent = MessageContent.ViewOnce(ViewOnceDescriptor(ViewOnceDescriptor.KIND_VIDEO))
        val longName = "Bartholomew Featherstonehaugh-Wolfeschlegelsteinhausen"
        val rows = listOf(
            message(null, "Here's the thing I mentioned", sent = false, minute = 1) to null,
            message(photo, "", sent = false, minute = 2) to "Alice",
            message(videoContent, "", sent = false, author = "bart.example.com", name = longName, minute = 3) to longName,
            message(null, "Got it, sending mine", sent = true, minute = 4) to null,
            message(photo, "", sent = true, minute = 5) to null,
            message(videoContent, "", sent = true, minute = 6) to null,
            message(MessageContent.ViewOnce(null), "", sent = false, minute = 7) to "Alice",
        )
        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for ((msg, author) in rows) {
                val sent = msg.originalAuthor == null
                Box(Modifier.fillMaxWidth(), contentAlignment = if (sent) Alignment.CenterEnd else Alignment.CenterStart) {
                    MessageBubbleRaw(
                        modifier = Modifier.widthIn(max = 300.dp),
                        message = msg,
                        decryptedFiles = persistentMapOf(),
                        sentByYou = sent,
                        clusterPosition = MessageClusterPosition.ALONE,
                        authorName = author?.takeIf { scene.group },
                        onLongClick = {},
                        onMediaClick = {},
                        onClickMessageId = {},
                        sharedTransitionScope = null,
                        animatedVisibilityScope = null,
                        downloadingFiles = emptySet(),
                    )
                }
            }
        }
    }

    private fun render(shot: Shot, dark: Boolean) = runDesktopComposeUiTest(
        width = (shot.widthDp * SCALE).toInt(),
        height = (shot.heightDp * SCALE).toInt(),
    ) {
        mainClock.autoAdvance = false
        setContent {
            Themed(dark, shot.fontScale, shot.rtl) {
                when (val scene = shot.scene) {
                    is Scene.Editor -> EditorScene(scene)
                    is Scene.Thread -> ThreadScene(scene)
                }
            }
        }
        // Coil decodes off the UI thread, so give it wall-clock time between frames.
        repeat(8) {
            mainClock.advanceTimeBy(250)
            Thread.sleep(150)
        }
        save(shot.name, dark)
    }

    private fun ComposeUiTest.save(name: String, dark: Boolean) {
        val image = onAllNodes(isRoot()).onFirst().captureToImage()
        assertTrue(image.width > 0 && image.height > 0)
        outDir?.let { dir ->
            ImageIO.write(image.toAwtImage(), "png", File(dir, "$name-${if (dark) "dark" else "light"}.png"))
        }
    }

    private companion object {
        const val SCALE = 2f
        const val PHONE_W = 412
        const val PHONE_H = 892
    }
}
