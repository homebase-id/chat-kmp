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
import androidx.compose.ui.test.onNodeWithText
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
import id.homebase.chat.conversationlist.MessageClusterPosition
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.LocalAttachmentContextStore
import id.homebase.chat.services.MessageAppData
import id.homebase.chat.services.ReplyPreview
import id.homebase.core.ui.theme.Dimens
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
 * Chat-bubble layout regression suite.
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
        TINY("Idk", true),
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

    // The aspect-ratio dimension the single-image suite was missing. The layout sizes off
    // these descriptor pixels (no decode needed), so covering ratios is just parameterising
    // pixelWidth/pixelHeight. TALL_PORTRAIT is the reported ~1080x2400 phone capture.
    private enum class Aspect(val w: Int, val h: Int) {
        // A near-1D strip. Height-capped it resolves to ~20dp wide; with a caption the
        // inline path used to clamp the text to that width → one character per line
        // (reported bug). Guards the captioned min-width floor.
        TALL_STRIP(150, 2400),
        TALL_PORTRAIT(1080, 2400),
        PORTRAIT(900, 1200),   // 3:4
        SQUARE(1000, 1000),    // 1:1
        LANDSCAPE(1200, 900),  // 4:3
        PANORAMA(1920, 1080),  // 16:9
    }

    private data class Case(
        val name: String,
        val sent: Boolean,
        val images: Int,
        val caption: Caption,
        val reply: Boolean = false,
        val aspect: Aspect = Aspect.LANDSCAPE,
        // A single non-media document payload (a .log) instead of images. Has no
        // previewThumbnail/aspect and a non-image contentType, so it renders as the
        // compact DocumentMediaItem file card — the #1103 regression dimension.
        val document: Boolean = false,
    )

    private fun imagePayload(i: Int, aspect: Aspect = Aspect.LANDSCAPE) = PayloadDescriptor(
        key = "chat_img$i",
        contentType = "image/jpeg",
        iv = Base64.encode(ByteArray(16)),
        previewThumbnail = ThumbnailDescriptor(
            pixelWidth = aspect.w, pixelHeight = aspect.h, contentType = "image/jpeg", content = "",
        ),
    )

    // A document attachment: no thumbnail, non-media contentType. Renders via
    // DocumentMediaItem (icon + name + size), which hugs its content.
    private fun documentPayload() = PayloadDescriptor(
        key = "chat_file0",
        contentType = "text/plain",
        iv = Base64.encode(ByteArray(16)),
        descriptorContent = "server.log",
        bytesWritten = 3_300_000,
    )

    // Stable so a test can hand the bubble the quoted message itself (which is what makes the
    // quote render a thumbnail).
    private val quotedId = Uuid.random()

    private fun Case.message(): MessageUiModel {
        val reply = if (reply) ReplyPreview(
            replyUniqueId = quotedId,
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
            payloads = if (document) {
                listOf(documentPayload()).toPersistentList()
            } else {
                (0 until images).map { imagePayload(it, aspect) }.toPersistentList()
            },
            keyHeader = KeyHeader(iv = ByteArray(16), aesKey = SecureByteArray(ByteArray(16))),
            versionTag = Uuid.random(),
            isPendingSend = false,
            hasMore = false,
        )
    }

    private fun ComposeUiTest.render(
        case: Case,
        authorName: String? = null,
        cluster: MessageClusterPosition = MessageClusterPosition.ALONE,
        quoted: MessageUiModel? = null,
    ) = setContent {
        Host {
            MessageBubbleRaw(
                message = case.message(),
                replyMessages = quoted?.let { persistentMapOf(quotedId to it) } ?: persistentMapOf(),
                decryptedFiles = persistentMapOf(),
                sentByYou = case.sent,
                onLongClick = {},
                onMediaClick = {},
                onClickMessageId = {},
                sharedTransitionScope = null,
                animatedVisibilityScope = null,
                downloadingFiles = emptySet(),
                authorName = authorName,
                clusterPosition = cluster,
            )
        }
    }

    private fun ComposeUiTest.boundsOf(tag: String): DpRect =
        onNodeWithTag(tag).getUnclippedBoundsInRoot()

    // The reply preview is clickable, so its children are merged away in the default tree.
    private fun ComposeUiTest.quoteTextBounds(): DpRect =
        onNodeWithTag(ChatBubbleTestTags.REPLY_QUOTE_TEXT, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()

    private fun ComposeUiTest.exists(tag: String): Boolean =
        onNodeWithTag(tag).let { runCatching { it.getUnclippedBoundsInRoot() }.isSuccess }

    private fun approx(a: Float, b: Float) = kotlin.math.abs(a - b) <= tol

    // ---- tests ------------------------------------------------------------

    /**
     * Full-bleed galleries. For every 2/3/4-image gallery that sits above a
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
     * A single image + caption, for every aspect ratio, must:
     *  1. leave no bubble-background strip beside the image (media edges == bubble edges),
     *  2. never collapse below Signal's 240dp captioned-image floor (char-per-line risk), and
     *  3. not shrink a naturally-wide image (landscape / panorama) down to that floor.
     *
     * 240dp is a floor for narrow images, not a hard cap.
     */
    @Test
    fun singleImageWithCaption_noGap_everyAspectRatio() = runComposeUiTest {
        val captionedMinWidth = Dimens.MediaBubble.minWidthWithContent.value
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
                    val mediaWidth = media.right.value - media.left.value
                    if (mediaWidth < captionedMinWidth - tol)
                        failures += "[${case.name}] captioned image collapsed (char-per-line risk): media.width=$mediaWidth < $captionedMinWidth"
                    // A naturally-wide image must keep its width, not shrink to the floor.
                    if ((aspect == Aspect.LANDSCAPE || aspect == Aspect.PANORAMA) &&
                        mediaWidth <= captionedMinWidth + tol
                    )
                        failures += "[${case.name}] wide image was shrunk to the 240dp floor: media.width=$mediaWidth"
                }
        assertTrue(
            failures.isEmpty(),
            "single-image no-gap invariant failed for these aspect ratios:\n" + failures.joinToString("\n"),
        )
    }

    /**
     * A group message with a wide sender name must not widen a captioned image's bubble past
     * the image. The name ellipsizes to the media width (maxLines=1) so the bubble hugs the
     * image; post-fix media.right == bubble.right.
     */
    @Test
    fun singleImageWithCaption_wideAuthorName_noGap() = runComposeUiTest {
        val longName = "Shelly Seifert Silberberg Von Habsburg Longname"
        val failures = mutableListOf<String>()
        for (aspect in listOf(Aspect.TALL_STRIP, Aspect.TALL_PORTRAIT, Aspect.PORTRAIT)) {
            val case = Case(
                name = "1img/$aspect/name", sent = false, images = 1,
                caption = Caption.LONG, aspect = aspect,
            )
            render(case, authorName = longName)
            val bubble = boundsOf(ChatBubbleTestTags.BUBBLE)
            val media = boundsOf(ChatBubbleTestTags.MEDIA)
            if (!approx(media.right.value, bubble.right.value))
                failures += "[${case.name}] wide sender name left a gap beside the image: " +
                    "media.right=${media.right.value} bubble.right=${bubble.right.value}"
        }
        assertTrue(failures.isEmpty(), "wide-author-name gap failures:\n" + failures.joinToString("\n"))
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
     * #1103: a single document (a .log — no thumbnail, non-media contentType) must render as a
     * compact file card that hugs its content, NOT stretched to a media-height box. The
     * regression (#1028/#1032, commit f3bee10eb) forced a fill-width document to
     * Dimens.MediaBubble.maxHeight (a tall grey void below the file row); the no-caption path
     * also floored it to minHeight. After the fix a document gets NO media height at all, so
     * the tagged media node is the ~72dp card (48dp icon + 12dp padding ×2) — well under the
     * 100dp media floor. Covers no-caption, inline-caption, and block-caption paths.
     */
    @Test
    fun singleDocument_compactCard_noMediaVoid() = runComposeUiTest {
        val floor = Dimens.MediaBubble.minHeight.value
        val failures = mutableListOf<String>()
        for (sent in listOf(true, false))
            for (cap in listOf(Caption.NONE, Caption.SHORT, Caption.BLOCK)) {
                render(Case("file/$cap/${if (sent) "sent" else "recv"}", sent, images = 0, caption = cap, document = true))
                val media = boundsOf(ChatBubbleTestTags.MEDIA)
                val h = media.bottom.value - media.top.value
                // Strictly below the media floor proves the doc dropped BOTH the maxHeight fill
                // (caption path) and the heightIn(min) floor (no-caption path) — i.e. no void.
                if (h >= floor - tol)
                    failures += "[file/$cap/${if (sent) "sent" else "recv"}] document got media height=$h (>= minHeight=$floor); expected a compact file card"
            }
        assertTrue(failures.isEmpty(), "document compact-card failures:\n" + failures.joinToString("\n"))
    }

    /**
     * A short text bubble hugs its text.
     *
     * With the footer shown there is exactly ONE 8dp gap between the text and the tucked
     * timestamp — the width formula used to add an 8dp gap on top of the info Row's own
     * `start = 8.dp` padding — plus the usual 12dp trailing inset.
     *
     * With the footer hidden (a non-terminal cluster bubble) the empty info Row must reserve
     * nothing: the bubble is the text plus its 12dp side insets. It used to reserve the full
     * 28dp strip for an empty Row, i.e. 16dp more than the inset it needs.
     */
    @Test
    fun textBubble_hugsText_singleGapBeforeTimestamp() = runComposeUiTest {
        val inset = 12f
        val gap = 8f
        val failures = mutableListOf<String>()

        for (sent in listOf(true, false)) {
            val who = if (sent) "sent" else "recv"
            render(Case("text/$who", sent, 0, Caption.SHORT), cluster = MessageClusterPosition.END)
            val bubble = boundsOf(ChatBubbleTestTags.BUBBLE)
            val caption = boundsOf(ChatBubbleTestTags.CAPTION)
            val timestamp = boundsOf(ChatBubbleTestTags.TIMESTAMP)

            if (!approx(timestamp.left.value - caption.right.value, gap))
                failures += "[$who] gap before the tucked timestamp is " +
                    "${timestamp.left.value - caption.right.value}dp, expected ${gap}dp"
            // Only on a received bubble is the timestamp the last footer child; a sent one
            // trails a 4dp spacer + delivery ticks.
            if (!sent && !approx(bubble.right.value - timestamp.right.value, inset))
                failures += "[$who] trailing inset is " +
                    "${bubble.right.value - timestamp.right.value}dp, expected ${inset}dp"
        }

        // Footer hidden: no timestamp, and nothing reserved for it.
        render(Case("text/mid", false, 0, Caption.SHORT), cluster = MessageClusterPosition.MIDDLE)
        val bubble = boundsOf(ChatBubbleTestTags.BUBBLE)
        val caption = boundsOf(ChatBubbleTestTags.CAPTION)
        if (exists(ChatBubbleTestTags.TIMESTAMP))
            failures += "[mid] a non-terminal cluster bubble must not render a timestamp"
        val bubbleWidth = bubble.right.value - bubble.left.value
        val hugWidth = (caption.right.value - caption.left.value) + 2 * inset
        if (!approx(bubbleWidth, hugWidth))
            failures += "[mid] footerless bubble is ${bubbleWidth}dp wide, expected ${hugWidth}dp " +
                "(text + 2x${inset}dp inset)"

        assertTrue(failures.isEmpty(), "bubble text-hug failures:\n" + failures.joinToString("\n"))
    }

    /**
     * A reply quote is measured at an exact width taken from the bubble, and it fills whatever
     * it is given — so if the reply's own text is all that sizes the bubble, a three-character
     * reply squeezes the quote to a sliver. The quoted content must get a vote: the same quote
     * must render at the same width under a short reply as under a long one, while the bubble
     * stays inside the column.
     */
    @Test
    fun replyQuote_notCrushedByShortReplyText() = runComposeUiTest {
        render(Case("reply/long", sent = false, images = 0, caption = Caption.LONG, reply = true))
        val roomy = quoteTextBounds()
        val roomyWidth = roomy.right.value - roomy.left.value

        render(Case("reply/tiny", sent = false, images = 0, caption = Caption.TINY, reply = true))
        val tight = quoteTextBounds()
        val tightWidth = tight.right.value - tight.left.value
        val bubble = boundsOf(ChatBubbleTestTags.BUBBLE)
        val bubbleWidth = bubble.right.value - bubble.left.value

        val failures = mutableListOf<String>()
        if (!approx(tightWidth, roomyWidth))
            failures += "quote crushed under a short reply: ${tightWidth}dp vs ${roomyWidth}dp " +
                "with a long reply"
        if (bubbleWidth > columnWidth.value + tol)
            failures += "quote pushed the bubble past the column: ${bubbleWidth}dp > ${columnWidth.value}dp"

        // A quote of a media message renders a thumbnail, which is SubcomposeLayout-backed —
        // intrinsics are not supported on those, so this proves the width vote never asks one.
        render(
            Case("reply/thumb", sent = false, images = 0, caption = Caption.TINY, reply = true),
            quoted = Case("quoted", sent = false, images = 1, caption = Caption.SHORT).message(),
        )
        // The 40dp thumb + its 4dp padding must show up in the width — that is what proves the
        // image node really is in the tree, so the case above is not vacuous.
        val thumbBubble = boundsOf(ChatBubbleTestTags.BUBBLE)
        val thumbWidth = (thumbBubble.right.value - thumbBubble.left.value) - bubbleWidth
        if (!approx(thumbWidth, 44f))
            failures += "quote thumbnail added ${thumbWidth}dp to the bubble, expected 44dp"

        assertTrue(failures.isEmpty(), "reply-quote width failures:\n" + failures.joinToString("\n"))
    }

    /**
     * The sender name in a group message sits 4dp above the text — its own bottom padding —
     * instead of that 4dp plus the text row's 12dp top inset. Without a name the text keeps
     * its 12dp inset from the bubble edge, and with media above it the 12dp stays too.
     */
    @Test
    fun authorName_tightGapAboveText() = runComposeUiTest {
        val name = "Alice Author"
        val failures = mutableListOf<String>()

        render(Case("author", sent = false, images = 0, caption = Caption.SHORT), authorName = name)
        val author = onNodeWithText(name).getUnclippedBoundsInRoot()
        var caption = boundsOf(ChatBubbleTestTags.CAPTION)
        if (!approx(caption.top.value - author.bottom.value, 4f))
            failures += "author -> text gap is ${caption.top.value - author.bottom.value}dp, expected 4dp"
        // The tucked timestamp is placed off the text's own top inset, so collapsing that
        // inset must not drag it away from the last line.
        val tuckedWithAuthor =
            boundsOf(ChatBubbleTestTags.TIMESTAMP).bottom.value - caption.bottom.value

        render(Case("no-author", sent = false, images = 0, caption = Caption.SHORT))
        val bubble = boundsOf(ChatBubbleTestTags.BUBBLE)
        caption = boundsOf(ChatBubbleTestTags.CAPTION)
        if (!approx(caption.top.value - bubble.top.value, 12f))
            failures += "without an author the text inset is " +
                "${caption.top.value - bubble.top.value}dp, expected 12dp"
        val tucked = boundsOf(ChatBubbleTestTags.TIMESTAMP).bottom.value - caption.bottom.value
        if (!approx(tuckedWithAuthor, tucked))
            failures += "tucked timestamp sits ${tuckedWithAuthor}dp below the text with an " +
                "author but ${tucked}dp without"

        render(Case("author+img", sent = false, images = 1, caption = Caption.SHORT), authorName = name)
        val media = boundsOf(ChatBubbleTestTags.MEDIA)
        caption = boundsOf(ChatBubbleTestTags.CAPTION)
        if (!approx(caption.top.value - media.bottom.value, 12f))
            failures += "media -> text gap is ${caption.top.value - media.bottom.value}dp, expected 12dp"

        assertTrue(failures.isEmpty(), "author gap failures:\n" + failures.joinToString("\n"))
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
