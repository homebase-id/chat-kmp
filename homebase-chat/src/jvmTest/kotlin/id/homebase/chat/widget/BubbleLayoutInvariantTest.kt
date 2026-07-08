package id.homebase.chat.widget

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.PlatformContext
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.ThumbnailDescriptor
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.LocalAttachmentContextStore
import id.homebase.chat.services.MessageAppData
import id.homebase.chat.services.ReplyPreview
import id.homebase.core.ui.theme.HomebaseTheme
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.koin.compose.KoinApplication
import org.koin.dsl.koinConfiguration
import org.koin.dsl.module
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * #964 — chat-bubble layout regression suite.
 *
 * Renders [MessageBubbleRaw] on the JVM host renderer (`runComposeUiTest`, same as
 * [ConversationMessagePreviewTest] / [ReactionMenuTest]) across a matrix of
 * {sent, received} × {0..4 images} × {no / short / long / block caption} × {reply}
 * and asserts geometric invariants on the tagged bubble / media / caption bounds
 * ([ChatBubbleTestTags]).
 *
 * The bug it guards: a 2+-image gallery above a caption used to be pinned to a fixed
 * album width, leaving an empty strip beside the images. The fix renders the gallery
 * full-bleed (edge-to-edge to the bubble) with only the caption inset 12dp — so the
 * core invariant below (media reaches both bubble edges, caption inset) FAILS on the
 * pre-fix code and PASSES after it.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalEncodingApi::class)
class BubbleLayoutInvariantTest {

    // Bounded conversation column so a long caption has a width to fill and the bubble
    // stays deterministic (mirrors the real caller which hands the bubble a max width).
    private val columnWidth = 400.dp
    private val tol = 1.0f // dp; text layout produces sub-pixel widths

    private val koin = koinConfiguration {
        modules(module {
            // The two Koin singletons any rendered MediaItem resolves. An empty Coil
            // ImageLoader is enough — the images never need to decode; the invariants
            // are about the media container's laid-out bounds, not pixel content.
            single { ImageLoader.Builder(PlatformContext.INSTANCE).build() }
            single { LocalAttachmentContextStore(EventBus(), CoroutineScope(SupervisorJob())) }
        })
    }

    @Composable
    private fun Host(content: @Composable () -> Unit) {
        KoinApplication(configuration = koin) {
            HomebaseTheme(darkTheme = false) {
                CompositionLocalProvider(LocalCurrentOdinId provides "me.example.com") {
                    Box(Modifier.width(columnWidth)) { content() }
                }
            }
        }
    }

    // ---- fixtures ---------------------------------------------------------

    private enum class Caption(val text: String, val hasCaption: Boolean) {
        NONE("", false),
        SHORT("Nice", true),
        LONG(
            "This is a long single paragraph caption, wide enough to exceed a fixed " +
                "album width so any empty strip beside the images would show.",
            true,
        ),
        // A markdown heading forces the block-markdown Column render path (distinct
        // from the inline custom-Layout path) — this is where the gap lived.
        BLOCK(
            "## Album\n\nA block-markdown caption with a heading so the bubble takes " +
                "the wrapContentWidth Column path instead of the timestamp-tuck Layout.",
            true,
        ),
    }

    // #1028: the aspect-ratio dimension #964's suite was missing. The layout sizes
    // off these descriptor pixels (no decode needed), so covering ratios is just
    // parameterising pixelWidth/pixelHeight. TALL_PORTRAIT is the reported screenshot
    // (a ~1080x2400 phone capture); it's the ratio that regressed.
    private enum class Aspect(val w: Int, val h: Int) {
        TALL_PORTRAIT(1080, 2400),
        PORTRAIT(900, 1200),   // 3:4
        SQUARE(1000, 1000),    // 1:1
        LANDSCAPE(1200, 900),  // 4:3 — the only ratio #964 tested
        PANORAMA(1920, 1080),  // 16:9
    }

    private data class Case(
        val name: String,
        val sent: Boolean,
        val images: Int,
        val caption: Caption,
        val reply: Boolean = false,
        val aspect: Aspect = Aspect.LANDSCAPE,
    )

    private fun imagePayload(i: Int, aspect: Aspect = Aspect.LANDSCAPE) = PayloadDescriptor(
        key = "chat_img$i",
        contentType = "image/jpeg",
        iv = Base64.encode(ByteArray(16)),
        previewThumbnail = ThumbnailDescriptor(
            pixelWidth = aspect.w, pixelHeight = aspect.h, contentType = "image/jpeg", content = "",
        ),
    )

    private fun Case.message(): MessageUiModel {
        val reply = if (reply) ReplyPreview(
            replyUniqueId = Uuid.random(),
            authorOdinId = "bob.example.com",
            message = "the message being replied to",
        ) else null
        return MessageUiModel(
            id = Uuid.random(),
            globalTransitId = null,
            fileId = Uuid.random(),
            conversationId = Uuid.random(),
            content = caption.text,
            userDate = Instant.fromEpochMilliseconds(0),
            modified = null,
            created = Instant.fromEpochMilliseconds(0),
            originalAuthor = if (sent) null else OdinId("alice.example.com"),
            sender = if (sent) null else OdinId("alice.example.com"),
            displayName = "Alice",
            messageAppData = MessageAppData(replyPreview = reply),
            reactionPreview = null,
            previewThumbnail = null,
            payloads = (0 until images).map { imagePayload(it, aspect) }.toPersistentList(),
            keyHeader = KeyHeader(iv = ByteArray(16), aesKey = SecureByteArray(ByteArray(16))),
            versionTag = Uuid.random(),
            isPendingSend = false,
            hasMore = false,
        )
    }

    private fun ComposeUiTest.render(case: Case) = setContent {
        Host {
            MessageBubbleRaw(
                message = case.message(),
                decryptedFiles = persistentMapOf(),
                sentByYou = case.sent,
                onLongClick = {},
                onMediaClick = {},
                onClickMessageId = {},
                sharedTransitionScope = null,
                animatedVisibilityScope = null,
                downloadingFiles = emptySet(),
            )
        }
    }

    private fun ComposeUiTest.boundsOf(tag: String): DpRect =
        onNodeWithTag(tag).getUnclippedBoundsInRoot()

    private fun ComposeUiTest.exists(tag: String): Boolean =
        onNodeWithTag(tag).let { runCatching { it.getUnclippedBoundsInRoot() }.isSuccess }

    private fun approx(a: Float, b: Float) = kotlin.math.abs(a - b) <= tol

    // ---- tests ------------------------------------------------------------

    /**
     * THE fix (#964, full-bleed). For every 2/3/4-image gallery that sits above a
     * caption the images run EDGE-TO-EDGE to the bubble — no blue strip beside them —
     * while the caption below keeps its own 12dp inset (the messenger convention,
     * matching this app's media-only bubbles):
     *  - media.left == bubble.left and media.right == bubble.right (full-bleed, no gap), and
     *  - the caption is inset from the bubble's left edge (not flush with the media), and
     *  - the caption stays within the bubble.
     *
     * On the pre-fix code the gallery was pinned to a fixed album width and inset 12dp to
     * line up with the caption, leaving the strip the user reported; the edge-to-edge
     * checks below fail on that layout and pass once the media inset is removed.
     */
    @Test
    fun galleryWithCaption_mediaFullBleed_captionInset() = runComposeUiTest {
        val cases = buildList {
            for (sent in listOf(true, false))
                for (images in listOf(2, 3, 4))
                    for (cap in listOf(Caption.SHORT, Caption.LONG, Caption.BLOCK))
                        add(Case("${images}img/${cap}/${if (sent) "sent" else "recv"}", sent, images, cap))
            // reply above a captioned gallery must not disturb the full-bleed media
            add(Case("2img/LONG/sent+reply", true, 2, Caption.LONG, reply = true))
            add(Case("3img/BLOCK/recv+reply", false, 3, Caption.BLOCK, reply = true))
        }
        val failures = mutableListOf<String>()
        for (case in cases) {
            render(case)
            val bubble = boundsOf(ChatBubbleTestTags.BUBBLE)
            val media = boundsOf(ChatBubbleTestTags.MEDIA)
            val caption = boundsOf(ChatBubbleTestTags.CAPTION)

            fun check(cond: Boolean, why: String) { if (!cond) failures += "[${case.name}] $why" }

            // Full-bleed: the gallery reaches BOTH bubble edges — this is the "no strip
            // beside the images" the fix restores.
            check(approx(media.left.value, bubble.left.value),
                "media.left=${media.left.value} != bubble.left=${bubble.left.value} (left strip)")
            check(approx(media.right.value, bubble.right.value),
                "media.right=${media.right.value} != bubble.right=${bubble.right.value} (right strip)")
            // The caption is inset from the bubble edge (not flush with the full-bleed media).
            check(caption.left.value > bubble.left.value + tol,
                "caption should be inset from bubble edge: caption.left=${caption.left.value} bubble.left=${bubble.left.value}")
            // Caption stays within the bubble.
            check(caption.left.value >= bubble.left.value - tol && caption.right.value <= bubble.right.value + tol,
                "caption overflows bubble")
        }
        assertTrue(failures.isEmpty(), "gallery full-bleed invariant failures:\n" + failures.joinToString("\n"))
    }

    /**
     * Consistent edge inset: the gallery's left offset from the bubble edge is the SAME
     * across every gallery+caption case — now 0 (full-bleed), and crucially not a
     * per-image-count value. Guards against a count-dependent album offset creeping back.
     */
    @Test
    fun galleryWithCaption_consistentEdgeInset() = runComposeUiTest {
        val insets = mutableListOf<Pair<String, Float>>()
        for (images in listOf(2, 3, 4)) for (cap in listOf(Caption.SHORT, Caption.LONG, Caption.BLOCK)) {
            val case = Case("${images}img/$cap", true, images, cap)
            render(case)
            val bubble = boundsOf(ChatBubbleTestTags.BUBBLE)
            val media = boundsOf(ChatBubbleTestTags.MEDIA)
            insets += case.name to (media.left.value - bubble.left.value)
        }
        val distinct = insets.map { kotlin.math.round(it.second) }.distinct()
        assertTrue(distinct.size == 1, "gallery edge inset must be constant, got: $insets")
    }

    /**
     * Regression guard: a SINGLE image + caption is UNCHANGED — the image stays
     * edge-to-edge (spans the full bubble width, not inset like a gallery), and the
     * bubble hugs it. The fix must not touch this path.
     */
    @Test
    fun singleImageWithCaption_unchanged_edgeToEdge() = runComposeUiTest {
        val failures = mutableListOf<String>()
        for (sent in listOf(true, false)) for (cap in listOf(Caption.LONG, Caption.BLOCK)) {
            val case = Case("1img/$cap/${if (sent) "sent" else "recv"}", sent, 1, cap)
            render(case)
            val bubble = boundsOf(ChatBubbleTestTags.BUBBLE)
            val media = boundsOf(ChatBubbleTestTags.MEDIA)
            if (!approx(media.left.value, bubble.left.value))
                failures += "[${case.name}] single image should be edge-to-edge: media.left=${media.left.value} bubble.left=${bubble.left.value}"
            if (!approx(media.right.value, bubble.right.value))
                failures += "[${case.name}] single image should span bubble width: media.right=${media.right.value} bubble.right=${bubble.right.value}"
        }
        assertTrue(failures.isEmpty(), "single-image invariant failures:\n" + failures.joinToString("\n"))
    }

    /**
     * #1028 — THE aspect-ratio-complete guard the #964 suite was missing. A SINGLE
     * image + caption must never leave a bubble-background strip beside the image,
     * for EVERY aspect ratio — not just the landscape 4:3 that #964 happened to test.
     *
     * The invariant (the same "no gap" one used for the gallery and the landscape
     * single image): the media reaches BOTH bubble edges, so there is no colored void
     * between the image and the bubble/caption edge —
     *   media.left == bubble.left  and  media.right == bubble.right.
     * Whether the convention is "media fills the bubble" or "bubble hugs the media",
     * this holds; only a height-capped narrow image under a wider caption (the #1028
     * bug) breaks it.
     *
     * TALL_PORTRAIT + LONG/BLOCK is the reported screenshot and the first case that
     * fails on today's code (image height-capped narrow, caption widens the bubble →
     * right-side strip) and passes after the fix.
     */
    @Test
    fun singleImageWithCaption_noGap_everyAspectRatio() = runComposeUiTest {
        val failures = mutableListOf<String>()
        for (sent in listOf(true, false))
            for (aspect in Aspect.entries)
                for (cap in listOf(Caption.SHORT, Caption.LONG, Caption.BLOCK)) {
                    val case = Case(
                        name = "1img/$aspect/$cap/${if (sent) "sent" else "recv"}",
                        sent = sent, images = 1, caption = cap, aspect = aspect,
                    )
                    render(case)
                    val bubble = boundsOf(ChatBubbleTestTags.BUBBLE)
                    val media = boundsOf(ChatBubbleTestTags.MEDIA)

                    if (!approx(media.left.value, bubble.left.value))
                        failures += "[${case.name}] left strip: media.left=${media.left.value} bubble.left=${bubble.left.value}"
                    if (!approx(media.right.value, bubble.right.value))
                        failures += "[${case.name}] right strip beside image: media.right=${media.right.value} bubble.right=${bubble.right.value}"
                }
        assertTrue(
            failures.isEmpty(),
            "single-image no-gap invariant failed for these aspect ratios:\n" + failures.joinToString("\n"),
        )
    }

    /**
     * Regression guard: media-only bubbles (no caption, any image count) are UNCHANGED —
     * the media fills the whole bubble, no inset, no gap.
     */
    @Test
    fun mediaOnly_unchanged_mediaFillsBubble() = runComposeUiTest {
        val failures = mutableListOf<String>()
        for (images in listOf(1, 2, 3, 4)) {
            val case = Case("${images}img/media-only", true, images, Caption.NONE)
            render(case)
            val bubble = boundsOf(ChatBubbleTestTags.BUBBLE)
            val media = boundsOf(ChatBubbleTestTags.MEDIA)
            if (exists(ChatBubbleTestTags.CAPTION))
                failures += "[${case.name}] media-only bubble unexpectedly has a caption"
            if (!approx(media.left.value, bubble.left.value) || !approx(media.right.value, bubble.right.value))
                failures += "[${case.name}] media should fill bubble: media=[${media.left.value},${media.right.value}] bubble=[${bubble.left.value},${bubble.right.value}]"
        }
        assertTrue(failures.isEmpty(), "media-only invariant failures:\n" + failures.joinToString("\n"))
    }

    /**
     * A text-only bubble (no media) renders with a caption and no media node — sanity
     * that the tags/harness behave at the 0-image corner of the matrix.
     */
    @Test
    fun textOnly_hasCaption_noMedia() = runComposeUiTest {
        for (cap in listOf(Caption.SHORT, Caption.LONG, Caption.BLOCK)) {
            render(Case("0img/$cap", true, 0, cap))
            assertTrue(exists(ChatBubbleTestTags.BUBBLE), "bubble missing for $cap")
            assertTrue(exists(ChatBubbleTestTags.CAPTION), "caption missing for $cap")
            assertTrue(!exists(ChatBubbleTestTags.MEDIA), "unexpected media for text-only $cap")
        }
    }
}
