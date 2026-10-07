package id.homebase.chat.viewonce

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Surface
import kotlinx.coroutines.delay
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
import androidx.compose.ui.test.hasTestTag
import kotlinx.coroutines.runBlocking
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
import id.homebase.chat.widget.MessageTimestampFooter
import id.homebase.chat.widget.messageBubbleShape
import id.homebase.core.util.formatMessageTimestamp
import id.homebase.core.util.formatTimestamp
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performClick
import id.homebase.chat.widget.VIEW_ONCE_TOGGLE_TAG
import id.homebase.chat.widget.ViewOnceToggle
import androidx.compose.ui.platform.testTag
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Renders the view-once composer and bubbles in every state, light and dark, to PNGs for design
 * review. Set VIEW_ONCE_SHOTS_DIR to write the images; without it the states are only composed.
 */
@OptIn(ExperimentalTestApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
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
        data class Editor(
            val attachments: List<AttachmentPendingFile>,
            val viewOnce: Boolean,
            val caption: String = "",
            val tapViewOnce: Boolean = false,
            val addAfterOn: AttachmentPendingFile? = null,
            // How long the toast has been up when the frame is taken; a "set to view once" frame lands after the fade.
            val toastAgeMs: Long = 800,
        ) : Scene
        data class Thread(val group: Boolean = false) : Scene
        data class States(val outgoing: Boolean, val pressFirst: Boolean = false) : Scene
        data class Viewer(
            val stage: ViewerStage,
            val video: Boolean = false,
            val muted: Boolean = false,
            val caption: String? = null,
            val picking: Boolean = false,
            val chromeHidden: Boolean = false,
            val sender: String = "Alice",
        ) : Scene
        data class Intro(val video: Boolean) : Scene
        data class Toast(val kind: ViewOnceToastKind) : Scene
    }

    private enum class ViewerStage { Live, Loading, Shown, Failed }

    private class Shot(
        val name: String,
        val scene: Scene,
        val fontScale: Float = 1f,
        val rtl: Boolean = false,
        val widthDp: Int = PHONE_W,
        val heightDp: Int = PHONE_H,
        val settleMs: Long = 2_000,
    )

    private val shots by lazy {
        listOf(
            Shot("e1-editor-photo-off", Scene.Editor(listOf(image()), viewOnce = false)),
            Shot("e2-editor-photo-on", Scene.Editor(listOf(image()), viewOnce = true)),
            Shot("e3-editor-video-on", Scene.Editor(listOf(video()), viewOnce = true)),
            Shot("e4-editor-two-photos-hidden", Scene.Editor(listOf(image(), image()), viewOnce = false)),
            Shot("e9-editor-two-photos-after-on", Scene.Editor(listOf(image(), image()), viewOnce = true)),
            Shot("e12-editor-two-photos-tapped", Scene.Editor(listOf(image(), image()), viewOnce = false, tapViewOnce = true)),
            Shot("e13-editor-on-then-second-photo-added", Scene.Editor(listOf(image()), viewOnce = true, addAfterOn = image()), settleMs = 3_100),
            Shot("e15-editor-toast-at-150ms", Scene.Editor(listOf(image()), viewOnce = true, toastAgeMs = 150)),
            Shot("e14-editor-toggle-off-tapped-on", Scene.Editor(listOf(image()), viewOnce = false, tapViewOnce = true), settleMs = 750),
            Shot("e10-editor-video-on-rtl-font-scale", Scene.Editor(listOf(video()), viewOnce = true), rtl = true, fontScale = 1.3f),
            Shot("e5-editor-on-font-scale", Scene.Editor(listOf(image()), viewOnce = true), fontScale = 1.6f),
            Shot("e6-editor-on-rtl", Scene.Editor(listOf(image()), viewOnce = true), rtl = true),
            Shot("e7-editor-on-small", Scene.Editor(listOf(image()), viewOnce = true), widthDp = 360, heightDp = 640),
            Shot("e8-editor-on-typed-caption", Scene.Editor(listOf(image()), viewOnce = true, caption = "Don't show anyone")),
            Shot("e11-editor-on-typed-caption-rtl", Scene.Editor(listOf(image()), viewOnce = true, caption = "Don't show anyone"), rtl = true),
            Shot("b1-thread", Scene.Thread()),
            Shot("b2-thread-group-long-name", Scene.Thread(group = true)),
            Shot("b3-thread-font-scale", Scene.Thread(group = true), fontScale = 1.6f, heightDp = 1_100),
            Shot("b4-thread-rtl", Scene.Thread(group = true), rtl = true),
            Shot("b5-thread-small", Scene.Thread(group = true), widthDp = 360, heightDp = 760),
            Shot("s1-received-states", Scene.States(outgoing = false), heightDp = 1_000),
            Shot("s2-sent-states", Scene.States(outgoing = true), heightDp = 620),
            Shot("s3-received-pressed", Scene.States(outgoing = false, pressFirst = true), heightDp = 1_000),
            Shot("s4-received-states-font-scale", Scene.States(outgoing = false), fontScale = 1.6f, heightDp = 1_400),
            Shot("s5-received-states-rtl", Scene.States(outgoing = false), rtl = true, heightDp = 1_000),
            Shot("s6-sent-states-font-scale", Scene.States(outgoing = true), fontScale = 1.6f, heightDp = 900),
            Shot("v1-viewer-photo-live", Scene.Viewer(ViewerStage.Live)),
            Shot("v16-viewer-photo-live-caption", Scene.Viewer(ViewerStage.Live, caption = "Don't show anyone. The view from the ridge before the rain came in.")),
            Shot("v2-viewer-loading", Scene.Viewer(ViewerStage.Loading)),
            // VLC can't decode in a headless test, so the poster stands in for a playing frame.
            Shot("v3-viewer-video-playing", Scene.Viewer(ViewerStage.Shown, video = true)),
            Shot("v4-viewer-failed", Scene.Viewer(ViewerStage.Failed)),
            Shot("v5-viewer-photo-font-scale", Scene.Viewer(ViewerStage.Shown), fontScale = 1.6f),
            Shot("v6-viewer-photo-rtl", Scene.Viewer(ViewerStage.Shown), rtl = true),
            Shot("v7-viewer-failed-small-font-scale", Scene.Viewer(ViewerStage.Failed, video = true), fontScale = 1.6f, widthDp = 360, heightDp = 640),
            Shot("v9-viewer-video-muted-font-scale", Scene.Viewer(ViewerStage.Shown, video = true, muted = true), fontScale = 1.6f),
            Shot("v14-viewer-video-caption-reactions", Scene.Viewer(ViewerStage.Shown, video = true, caption = "Dinner at the harbour", picking = true)),
            Shot("v11-viewer-photo-caption", Scene.Viewer(ViewerStage.Shown, caption = "Don't show anyone. The view from the ridge before the rain came in.")),
            Shot("v12-viewer-photo-landscape-top-edge", Scene.Viewer(ViewerStage.Shown), widthDp = 960, heightDp = 540),
            Shot("v8-viewer-photo-chrome-hidden", Scene.Viewer(ViewerStage.Shown, chromeHidden = true)),
            Shot("v10-viewer-video-chrome-hidden", Scene.Viewer(ViewerStage.Shown, video = true, chromeHidden = true)),
            Shot("v13-viewer-group-long-name-font-scale", Scene.Viewer(ViewerStage.Shown, video = true, sender = "Bartholomew Featherstonehaugh-Wolfeschlegelsteinhausen"), fontScale = 1.6f),
            Shot("v15-viewer-video-loading", Scene.Viewer(ViewerStage.Loading, video = true)),
            Shot("i1-intro-photo", Scene.Intro(video = false), heightDp = 620),
            Shot("i2-intro-video-font-scale-rtl", Scene.Intro(video = true), fontScale = 1.6f, rtl = true, heightDp = 820),
            Shot("t1-toast-photo", Scene.Toast(ViewOnceToastKind.Photo), heightDp = 160, settleMs = 500),
            Shot("t2-toast-off", Scene.Toast(ViewOnceToastKind.Off), heightDp = 160, settleMs = 500),
        )
    }

    @Test
    fun viewOnceRendersEveryState() {
        for (dark in listOf(false, true)) {
            for (shot in shots) if (only.isEmpty() || only.any { shot.name.startsWith(it) }) render(shot, dark)
        }
    }

    @Test
    fun togglingViewOnceFloatsTheToastOverTheMediaAndNeverMovesIt() = runDesktopComposeUiTest(
        width = (PHONE_W * SCALE).toInt(),
        height = (PHONE_H * SCALE).toInt(),
    ) {
        val state = ViewOnceComposerState(UserPreferences(InMemorySettings()).also { it.viewOnceIntroSeen = true })
        val attachments = listOf(image())
        setContent {
            Themed(dark = true, fontScale = 1f, rtl = false) {
                MediaAttachmentEditor(
                    attachments = attachments,
                    currentPage = 0,
                    onPageChanged = {},
                    onAddImage = {},
                    addMoreEnabled = !state.requested,
                    centerImageInPage = true,
                    imageOverlay = { Box(Modifier.matchParentSize().testTag(MEDIA_TAG)) },
                    aboveStripOverlay = {
                        ViewOnceToast(message = state.toast, modifier = Modifier.align(Alignment.BottomCenter))
                    },
                )
            }
        }
        repeat(8) {
            mainClock.advanceTimeBy(250)
            Thread.sleep(150)
        }
        val before = onNodeWithTag(MEDIA_TAG).getBoundsInRoot()

        runOnIdle { state.toggle(isVideo = false, eligible = true) }
        mainClock.advanceTimeBy(150)
        assertTrue(onAllNodesWithTag(VIEW_ONCE_TOAST_TAG).fetchSemanticsNodes().isNotEmpty())
        assertEquals(before, onNodeWithTag(MEDIA_TAG).getBoundsInRoot(), "mid-animation")
        mainClock.advanceTimeBy(600)
        assertEquals(before, onNodeWithTag(MEDIA_TAG).getBoundsInRoot(), "toast fully in")
    }

    private val koin = koinApplication {
        modules(module {
            single { UserPreferences(InMemorySettings()) }
            single { ImageLoader.Builder(PlatformContext.INSTANCE).components { add(PlatformFileFetcher.Factory()) }.build() }
            single { LocalAttachmentContextStore(EventBus(), CoroutineScope(SupervisorJob())) }
            single { runBlocking { ViewOnceFakeServer().start() }.homebaseImageLoader }
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
    private fun EditorScene(scene: Scene.Editor, settleMs: Long) {
        val state = remember {
            ViewOnceComposerState(UserPreferences(InMemorySettings()).also { it.viewOnceIntroSeen = true })
        }
        var attachments by remember { mutableStateOf(scene.attachments) }
        val isVideo = attachments.singleOrNull() is AttachmentPendingFile.FileVideo
        LaunchedEffect(Unit) {
            val extra = scene.addAfterOn
            if (extra == null) {
                if (scene.viewOnce) {
                    delay(settleMs - scene.toastAgeMs)
                    state.toggle(isVideo, isViewOnceEligible(attachments))
                }
                return@LaunchedEffect
            }
            if (scene.viewOnce) state.toggle(isVideo, isViewOnceEligible(attachments))
            delay(VIEW_ONCE_TOAST_MS + 500)
            attachments = attachments + extra
        }
        val eligible = isViewOnceEligible(attachments)
        LaunchedEffect(eligible) { state.onEligibilityChanged(eligible) }
        val viewOnce = state.requested && eligible
        val caption = rememberRichTextState()
        LaunchedEffect(Unit) { if (scene.caption.isNotEmpty()) caption.setText(scene.caption) }
        MediaAttachmentEditor(
            attachments = attachments,
            currentPage = 0,
            onPageChanged = {},
            onSaveFile = {},
            onCropImage = {},
            onDrawImage = {},
            onToggleMediaQuality = {},
            onAddImage = {},
            addMoreEnabled = !viewOnce,
            onRemoveFile = {},
            onDismiss = {},
            centerImageInPage = true,
            aboveStripOverlay = {
                ViewOnceToast(message = state.toast, modifier = Modifier.align(Alignment.BottomCenter))
            },
            bottomBar = {
                MessageTextFieldForAttachment(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    state = caption,
                    onSendMessage = {},
                    viewOnceToggle = if (eligible) ViewOnceToggle(viewOnce) { state.toggle(isVideo, isViewOnceEligible(attachments)) } else null,
                    showFormattingToolbar = false,
                )
            },
        )
    }

    private val recentMs = Clock.System.now().toEpochMilliseconds() - 3_600_000L

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
        // Fixed, so both themes (rendered minutes apart) print the same time.
        userDate = Instant.fromEpochMilliseconds(FIXED_TIME_MS + minute * 60_000L),
        modified = null,
        // Recent, or stateOf would rightly call every row Expired.
        created = Instant.fromEpochMilliseconds(recentMs),
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

    private class StateRow(
        val state: ViewOnceState,
        val kind: String? = ViewOnceDescriptor.KIND_IMAGE,
        val phase: ViewOnceOpenPhase = ViewOnceOpenPhase.Idle,
        val tappable: Boolean = false,
        val openOnPhone: Boolean = false,
        val openedCount: Int = 0,
        val author: String? = null,
    )

    @Composable
    private fun StatesScene(scene: Scene.States) {
        val longName = "Bartholomew Featherstonehaugh-Wolfeschlegelsteinhausen"
        val rows = if (scene.outgoing) listOf(
            StateRow(ViewOnceState.Sent),
            StateRow(ViewOnceState.Sent, kind = ViewOnceDescriptor.KIND_VIDEO),
            StateRow(ViewOnceState.Opened, openedCount = 1),
            StateRow(ViewOnceState.Opened, kind = ViewOnceDescriptor.KIND_VIDEO, openedCount = 3),
            StateRow(ViewOnceState.Expired),
        ) else listOf(
            StateRow(ViewOnceState.Unopened, tappable = true, author = "Alice"),
            StateRow(ViewOnceState.Unopened, kind = ViewOnceDescriptor.KIND_VIDEO, tappable = true, author = longName),
            StateRow(ViewOnceState.Unopened, phase = ViewOnceOpenPhase.Opening, tappable = true),
            StateRow(ViewOnceState.Unopened, phase = ViewOnceOpenPhase.Failed, tappable = true),
            StateRow(ViewOnceState.Opened),
            StateRow(ViewOnceState.Expired, kind = ViewOnceDescriptor.KIND_VIDEO),
            StateRow(ViewOnceState.Unopened, openOnPhone = true),
            StateRow(ViewOnceState.Unopened),
            StateRow(ViewOnceState.Unopened, kind = null),
        )
        val sent = scene.outgoing
        val container = if (sent) HomebaseTheme.extendedColors.bubbleSentSurface else MaterialTheme.colorScheme.surfaceContainerHigh
        val content = if (sent) HomebaseTheme.extendedColors.bubbleSentOnSurface else MaterialTheme.colorScheme.onSurface
        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            rows.forEachIndexed { index, row ->
                Box(Modifier.fillMaxWidth(), contentAlignment = if (sent) Alignment.CenterEnd else Alignment.CenterStart) {
                    ViewOnceBubble(
                        descriptor = row.kind?.let(::ViewOnceDescriptor),
                        isOutgoing = sent,
                        isGroup = row.openedCount > 1,
                        shape = messageBubbleShape(sent, MessageClusterPosition.ALONE),
                        containerColor = container,
                        contentColor = content,
                        modifier = Modifier.widthIn(max = 300.dp).then(if (index == 0) Modifier.testTag(FIRST_ROW) else Modifier),
                        state = row.state,
                        openedCount = row.openedCount,
                        canView = !row.openOnPhone,
                        phase = row.phase,
                        onOpen = if (row.tappable) ({}) else null,
                        authorName = row.author,
                        footer = { footerColor ->
                            MessageTimestampFooter(
                                infoText = formatMessageTimestamp(Instant.fromEpochMilliseconds(FIXED_TIME_MS + index * 60_000L)),
                                contentColor = footerColor,
                                showDeliveryStatus = sent,
                                isPendingSend = false,
                                deliveryStatus = 30,
                                pendingSince = null,
                            )
                        },
                    )
                }
            }
        }
    }

    @Composable
    private fun ViewerScene(scene: Scene.Viewer) {
        val frame = remember { (if (scene.video) poster else File(samples, "red-leaf.jpg")).absolutePath }
        ViewOnceViewerFrame(
            isVideo = scene.video,
            caption = scene.caption,
            senderName = scene.sender,
            sentAt = formatTimestamp(Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds() - 12 * 60_000L)),
            mediaShown = scene.stage == ViewerStage.Shown,
            failed = scene.stage == ViewerStage.Failed,
            onClose = {},
            positionMs = { 12_000L },
            durationMs = 34_000L,
            muted = scene.muted,
            onMutedChange = {},
            onTogglePlay = if (scene.video) ({}) else null,
        ) { fill, toggleChrome ->
            when (scene.stage) {
                ViewerStage.Loading -> ViewOnceViewerLoading(fill)
                ViewerStage.Failed -> ViewOnceViewerFailed(onRetry = {}, modifier = fill)
                else -> ViewOnceViewerImage(frame, onTap = toggleChrome)
            }
        }
    }

    private fun renderLiveViewer(shot: Shot, dark: Boolean) = runDesktopComposeUiTest(
        width = (shot.widthDp * SCALE).toInt(),
        height = (shot.heightDp * SCALE).toInt(),
    ) {
        val server = runBlocking { ViewOnceFakeServer(plainImage = File(samples, "red-leaf.jpg").readBytes()).start() }
        setContent {
            Themed(dark, shot.fontScale, shot.rtl) {
                val scene = shot.scene as Scene.Viewer
                server.Provide {
                ViewOnceViewer(
                    data = server.viewer(
                        caption = scene.caption,
                        senderName = scene.sender,
                        sentAt = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds() - 12 * 60_000L),
                    ),
                    onViewerClosed = {},
                    onDismiss = {},
                    loader = server.loader,
                )
                }
            }
        }
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag(VIEW_ONCE_VIEWER_IMAGE_TAG)).fetchSemanticsNodes().isNotEmpty() }
        mainClock.autoAdvance = false
        mainClock.advanceTimeBy(600)
        save(shot.name, dark)
    }

    private fun render(shot: Shot, dark: Boolean): Unit = onEdt {
        when {
            (shot.scene as? Scene.Viewer)?.stage == ViewerStage.Live -> renderLiveViewer(shot, dark)
            else -> renderStill(shot, dark)
        }
    }

    private fun renderStill(shot: Shot, dark: Boolean) = runDesktopComposeUiTest(
        width = (shot.widthDp * SCALE).toInt(),
        height = (shot.heightDp * SCALE).toInt(),
    ) {
        mainClock.autoAdvance = false
        setContent {
            Themed(dark, shot.fontScale, shot.rtl) {
                when (val scene = shot.scene) {
                    is Scene.Editor -> EditorScene(scene, shot.settleMs)
                    is Scene.Thread -> ThreadScene(scene)
                    is Scene.States -> StatesScene(scene)
                    is Scene.Viewer -> ViewerScene(scene)
                    is Scene.Intro -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)), contentAlignment = Alignment.BottomCenter) {
                        // ModalBottomSheet's own window can't be captured here, so its surface and handle are drawn around the content.
                        Surface(shape = BottomSheetDefaults.ExpandedShape, color = BottomSheetDefaults.ContainerColor) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                BottomSheetDefaults.DragHandle()
                                ViewOnceIntroContent(isVideo = scene.video, onOk = {})
                            }
                        }
                    }
                    is Scene.Toast -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
                        ViewOnceToast(remember { ViewOnceToastMessage(scene.kind) })
                    }
                }
            }
        }
        if ((shot.scene as? Scene.States)?.pressFirst == true) {
            mainClock.advanceTimeBy(500)
            onNodeWithTag(FIRST_ROW).performTouchInput { down(center) }
        }
        if ((shot.scene as? Scene.Viewer)?.chromeHidden == true) {
            mainClock.advanceTimeBy(500)
            onNodeWithTag(VIEW_ONCE_VIEWER_IMAGE_TAG).performClick()
        }
        if ((shot.scene as? Scene.Viewer)?.picking == true) {
            mainClock.advanceTimeBy(500)
            onNodeWithTag(VIEW_ONCE_VIEWER_REACT_TAG).performClick()
        }
        if ((shot.scene as? Scene.Editor)?.tapViewOnce == true) {
            mainClock.advanceTimeBy(500)
            onAllNodesWithTag(VIEW_ONCE_TOGGLE_TAG).fetchSemanticsNodes().firstOrNull()?.let { onNodeWithTag(VIEW_ONCE_TOGGLE_TAG).performClick() }
        }
        // Coil decodes off the UI thread, so give it wall-clock time between frames.
        repeat((shot.settleMs / 250).toInt()) {
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
        const val MEDIA_TAG = "editorMedia"
        const val PHONE_W = 412
        const val PHONE_H = 892
        const val FIRST_ROW = "firstRow"
        const val FIXED_TIME_MS = 1_760_000_000_000L
    }
}
