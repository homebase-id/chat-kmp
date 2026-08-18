package id.homebase.core.ui.screens.moments.widget

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.PlatformContext
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.cache.DriveFileProviderCached
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.ThumbnailDescriptor
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.SecureByteArray
import id.homebase.api.file.FileOperationsProvider
import id.homebase.chat.services.LocalAttachmentContextStore
import id.homebase.core.image.HomebaseImageLoader
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.forms.InputProvider
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import org.koin.compose.KoinApplication
import org.koin.dsl.koinConfiguration
import org.koin.dsl.module
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Moments feed media layout regression suite (#1128).
 *
 * Renders the real [MomentMediaGallery] on the JVM host renderer (`runComposeUiTest`,
 * same harness as the chat `BubbleLayoutInvariantTest`) inside a fixed-width card
 * stand-in, and asserts geometric invariants on the tagged slot / media bounds
 * ([MomentMediaTestTags]).
 *
 * The rule under test: **a picture is as large as possible while staying fully
 * visible.** Width is the whole card unless the resulting height would exceed the
 * viewport budget; only then does the cell shrink — and it shrinks at its own
 * aspect ratio, so nothing is ever cropped.
 *
 * Aspect ratios are driven purely off [ThumbnailDescriptor] pixel dimensions (what
 * `aspectRatioFor` reads), so no image ever has to decode: the invariants are about
 * the laid-out container, not pixel content.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalEncodingApi::class)
class MomentMediaLayoutInvariantTest {

    /** A 360dp phone minus the post card's 16dp inset on each side. */
    private val cardWidth = 328.dp

    /** [FeedMediaViewportHeightFraction] of an 800dp-tall window. */
    private val heightBudget = 560.dp

    private val tol = 1.0f // dp; layout rounds to whole pixels

    // ---- harness ----------------------------------------------------------

    private val koin = koinConfiguration {
        modules(module {
            single { ImageLoader.Builder(PlatformContext.INSTANCE).build() }
            single { LocalAttachmentContextStore(EventBus(), CoroutineScope(SupervisorJob())) }
            single { stubHomebaseImageLoader() }
        })
    }

    @Composable
    private fun Host(content: @Composable () -> Unit) {
        KoinApplication(configuration = koin) {
            MaterialTheme {
                Box(Modifier.width(cardWidth)) { content() }
            }
        }
    }

    // ---- fixtures ---------------------------------------------------------

    /**
     * Descriptor pixel dimensions only — the layout sizes off these, nothing decodes.
     * Portrait ratios are the phone-camera shapes the bug report is about.
     */
    private enum class Aspect(val w: Int, val h: Int) {
        TALL_STRIP(150, 2400),         // 0.0625 — pathological
        TALL_PORTRAIT(1080, 2400),     // 0.45   — 9:20 phone capture
        PORTRAIT_9_16(1080, 1920),     // 0.5625
        PORTRAIT_2_3(800, 1200),       // 0.667
        PORTRAIT_3_4(900, 1200),       // 0.75
        PORTRAIT_4_5(1080, 1350),      // 0.8    — the old fixed carousel shape
        SQUARE(1000, 1000),            // 1.0
        LANDSCAPE(1200, 900),          // 1.333
        WIDE_16_9(1920, 1080),         // 1.778
        PANORAMA(3000, 1000),          // 3.0    — beyond MaxFeedPhotoAspect
        ;

        val ratio: Float get() = w.toFloat() / h.toFloat()
    }

    /** Portraits that fit the budget at full card width — must be edge-to-edge. */
    private val fittingPortraits =
        listOf(Aspect.PORTRAIT_4_5, Aspect.PORTRAIT_3_4, Aspect.PORTRAIT_2_3)

    /** Portraits taller than the budget at full card width — must clamp, not crop. */
    private val overTallPortraits =
        listOf(Aspect.PORTRAIT_9_16, Aspect.TALL_PORTRAIT, Aspect.TALL_STRIP)

    private fun imagePayload(i: Int, aspect: Aspect) = PayloadDescriptor(
        key = "mmnt_img$i",
        contentType = "image/jpeg",
        iv = Base64.encode(ByteArray(16)),
        previewThumbnail = ThumbnailDescriptor(
            pixelWidth = aspect.w,
            pixelHeight = aspect.h,
            contentType = "image/jpeg",
            content = "",
        ),
    )

    private fun ComposeUiTest.render(aspects: List<Aspect>) = setContent {
        Host {
            MomentMediaGallery(
                payloads = aspects.mapIndexed { i, a -> imagePayload(i, a) },
                fileId = Uuid.random(),
                driveId = Uuid.random(),
                keyHeader = KeyHeader(iv = ByteArray(16), aesKey = SecureByteArray(ByteArray(16))),
                sharedTransitionScope = null,
                animatedVisibilityScope = null,
                messageId = Uuid.random(),
                downloadingFiles = emptySet(),
                maxMediaHeight = heightBudget,
            )
        }
    }

    private fun ComposeUiTest.boundsOf(tag: String): DpRect =
        onNodeWithTag(tag).getUnclippedBoundsInRoot()

    private fun approx(a: Float, b: Float) = abs(a - b) <= tol

    private val DpRect.w: Float get() = right.value - left.value
    private val DpRect.h: Float get() = bottom.value - top.value

    // ---- single photo -----------------------------------------------------

    /**
     * Core acceptance for #1128: a portrait photo whose natural height fits the
     * viewport budget fills the ENTIRE card width — no left/right dead space at all —
     * and is exactly as tall as its own aspect ratio demands.
     *
     * Pre-fix the single-photo path already did this; the test pins it so the height
     * bound added for the tall case can't quietly start shrinking these too.
     */
    @Test
    fun portraitPhoto_fillsCardWidth_noSideGap() = runComposeUiTest {
        val failures = mutableListOf<String>()
        for (aspect in fittingPortraits) {
            render(listOf(aspect))
            val slot = boundsOf(MomentMediaTestTags.MEDIA_SLOT)
            val media = boundsOf(MomentMediaTestTags.MEDIA)

            if (!approx(media.left.value, slot.left.value))
                failures += "[$aspect] left dead space: media.left=${media.left.value} slot.left=${slot.left.value}"
            if (!approx(media.right.value, slot.right.value))
                failures += "[$aspect] right dead space: media.right=${media.right.value} slot.right=${slot.right.value}"
            if (!approx(media.w, cardWidth.value))
                failures += "[$aspect] media width=${media.w} != card width=${cardWidth.value}"
            val expectedHeight = cardWidth.value / aspect.ratio
            if (!approx(media.h, expectedHeight))
                failures += "[$aspect] media height=${media.h} != natural ${expectedHeight}"
        }
        assertTrue(failures.isEmpty(), "portrait full-width failures:\n" + failures.joinToString("\n"))
    }

    /**
     * A portrait taller than the height budget shrinks — bounded by the budget, NOT
     * by a hardcoded aspect ratio — and keeps its own ratio exactly, so it is still
     * uncropped. This is the only case where a left/right gap is allowed.
     */
    @Test
    fun overTallPortrait_clampedToBudget_stillUncropped() = runComposeUiTest {
        val failures = mutableListOf<String>()
        for (aspect in overTallPortraits) {
            render(listOf(aspect))
            val slot = boundsOf(MomentMediaTestTags.MEDIA_SLOT)
            val media = boundsOf(MomentMediaTestTags.MEDIA)

            if (!approx(media.h, heightBudget.value))
                failures += "[$aspect] height=${media.h} should be clamped to budget=${heightBudget.value}"
            if (media.w > cardWidth.value + tol)
                failures += "[$aspect] clamped media wider than the card: ${media.w}"
            if (media.w > slot.w + tol)
                failures += "[$aspect] clamped media overflows the slot: ${media.w} > ${slot.w}"
            // Uncropped: the cell keeps the photo's ratio, so Fit fills it exactly.
            val laidOut = media.w / media.h
            if (abs(laidOut - aspect.ratio) > 0.01f)
                failures += "[$aspect] cell ratio=$laidOut != payload ratio=${aspect.ratio} (cropped/stretched)"
        }
        assertTrue(failures.isEmpty(), "over-tall portrait failures:\n" + failures.joinToString("\n"))
    }

    /**
     * Regression guard for **#873** (landscape letterboxed in a too-tall cell) and
     * **#818** (landscape side-cropped into a portrait cell): a landscape photo gets a
     * cell at its OWN ratio — full card width, and exactly as short as the photo is.
     * Both old bugs show up as a cell height that differs from `width / ratio`.
     */
    @Test
    fun landscapePhoto_naturalCell_notCroppedNotLetterboxed() = runComposeUiTest {
        val failures = mutableListOf<String>()
        for (aspect in listOf(Aspect.SQUARE, Aspect.LANDSCAPE, Aspect.WIDE_16_9)) {
            render(listOf(aspect))
            val slot = boundsOf(MomentMediaTestTags.MEDIA_SLOT)
            val media = boundsOf(MomentMediaTestTags.MEDIA)

            if (!approx(media.w, cardWidth.value))
                failures += "[$aspect] media width=${media.w} != card width=${cardWidth.value}"
            if (!approx(media.left.value, slot.left.value) || !approx(media.right.value, slot.right.value))
                failures += "[$aspect] #818 side-crop/gap: media=[${media.left.value},${media.right.value}] slot=[${slot.left.value},${slot.right.value}]"
            val expectedHeight = cardWidth.value / aspect.ratio
            if (!approx(media.h, expectedHeight))
                failures += "[$aspect] #873 blank bars: cell height=${media.h} != natural $expectedHeight"
        }
        assertTrue(failures.isEmpty(), "landscape cell failures:\n" + failures.joinToString("\n"))
    }

    /**
     * A panorama wider than [MaxFeedPhotoAspect] still clamps to that ratio so it
     * doesn't render as a sliver — existing behaviour, preserved.
     */
    @Test
    fun panorama_clampedToMaxFeedPhotoAspect() = runComposeUiTest {
        render(listOf(Aspect.PANORAMA))
        val media = boundsOf(MomentMediaTestTags.MEDIA)
        assertTrue(
            approx(media.w, cardWidth.value),
            "panorama should still fill the card width, got ${media.w}",
        )
        val expectedHeight = cardWidth.value / MaxFeedPhotoAspect
        assertTrue(
            approx(media.h, expectedHeight),
            "panorama height=${media.h} should be clamped to card/${MaxFeedPhotoAspect} = $expectedHeight",
        )
    }

    // ---- carousel ---------------------------------------------------------

    /**
     * A mixed-aspect carousel renders ONE frame (a per-page height would make the
     * pager jump), sized from the TALLEST page so no page has to be cropped — and at
     * full card width when that fits the budget.
     *
     * Pre-fix the frame was a fixed 4:5, which letterboxed every 2:3 / 3:4 phone
     * portrait on both sides; the height assertion below fails on that layout.
     */
    @Test
    fun carousel_oneSharedFrame_sizedFromTallestPage() = runComposeUiTest {
        val cases = listOf(
            listOf(Aspect.LANDSCAPE, Aspect.PORTRAIT_3_4),
            listOf(Aspect.PORTRAIT_2_3, Aspect.LANDSCAPE, Aspect.SQUARE),
            listOf(Aspect.SQUARE, Aspect.PORTRAIT_2_3),
        )
        val failures = mutableListOf<String>()
        for (aspects in cases) {
            render(aspects)
            val name = aspects.joinToString("+")
            val frames = onAllNodesWithTag(MomentMediaTestTags.MEDIA).fetchSemanticsNodes().size
            if (frames != 1) failures += "[$name] expected one shared frame, got $frames"

            val slot = boundsOf(MomentMediaTestTags.MEDIA_SLOT)
            val media = boundsOf(MomentMediaTestTags.MEDIA)
            val tallest = aspects.minOf { it.ratio }

            if (!approx(media.left.value, slot.left.value) || !approx(media.right.value, slot.right.value))
                failures += "[$name] carousel letterboxed: media=[${media.left.value},${media.right.value}] slot=[${slot.left.value},${slot.right.value}]"
            val expectedHeight = cardWidth.value / tallest
            if (!approx(media.h, expectedHeight))
                failures += "[$name] frame height=${media.h} should match the tallest page ($tallest) => $expectedHeight"
            // Every page fits inside the frame without being cropped.
            for (a in aspects) {
                val neededHeight = media.w / a.ratio
                if (neededHeight > media.h + tol)
                    failures += "[$name] page $a needs ${neededHeight}dp but the frame is only ${media.h}dp"
            }
        }
        assertTrue(failures.isEmpty(), "carousel frame failures:\n" + failures.joinToString("\n"))
    }

    /**
     * Regression guard for **#873** in the carousel: a landscape FIRST page must not
     * determine the frame — the later portrait pages would be crushed into a short
     * strip. The frame tracks the tallest page regardless of order.
     */
    @Test
    fun carousel_landscapeFirst_doesNotCrushPortraitPages() = runComposeUiTest {
        render(listOf(Aspect.WIDE_16_9, Aspect.PORTRAIT_2_3))
        val media = boundsOf(MomentMediaTestTags.MEDIA)
        val landscapeFrameHeight = cardWidth.value / Aspect.WIDE_16_9.ratio
        assertTrue(
            media.h > landscapeFrameHeight + tol,
            "frame collapsed to the landscape page's height (${media.h} <= $landscapeFrameHeight)",
        )
        assertTrue(
            approx(media.h, cardWidth.value / Aspect.PORTRAIT_2_3.ratio),
            "frame height=${media.h} should fit the 2:3 portrait page",
        )
    }

    /**
     * A carousel whose tallest page is taller than the budget clamps to the budget —
     * same bound as the single-photo path — instead of running off the screen.
     */
    @Test
    fun carousel_tallestPageOverBudget_clampsToBudget() = runComposeUiTest {
        render(listOf(Aspect.TALL_PORTRAIT, Aspect.LANDSCAPE))
        val media = boundsOf(MomentMediaTestTags.MEDIA)
        assertTrue(
            approx(media.h, heightBudget.value),
            "carousel frame height=${media.h} should be clamped to the budget=${heightBudget.value}",
        )
        assertTrue(media.w <= cardWidth.value + tol, "clamped frame wider than the card: ${media.w}")
    }

    // ---- sizing rule, as pure functions -----------------------------------

    /**
     * [momentFrameAspect] never clamps a portrait by ratio — the fixed 4:5 carousel
     * box and the 0.8 photo cap are exactly what letterboxed / side-cropped them.
     */
    @Test
    fun frameAspect_neverClampsPortraitByRatio() {
        for (aspect in fittingPortraits + overTallPortraits) {
            val payload = imagePayload(0, aspect)
            assertEquals(
                aspect.ratio,
                momentFrameAspect(payload),
                absoluteTolerance = 0.001f,
                message = "$aspect must keep its natural ratio",
            )
        }
        // …but a panorama is still capped so it can't become a sliver.
        assertEquals(
            MaxFeedPhotoAspect,
            momentFrameAspect(imagePayload(0, Aspect.PANORAMA)),
            absoluteTolerance = 0.001f,
        )
    }

    /** The carousel frame tracks the tallest page, whatever the page order. */
    @Test
    fun frameAspect_carouselTracksTallestPage() {
        val pages = listOf(
            imagePayload(0, Aspect.LANDSCAPE),
            imagePayload(1, Aspect.PORTRAIT_2_3),
            imagePayload(2, Aspect.SQUARE),
        )
        assertEquals(Aspect.PORTRAIT_2_3.ratio, momentFrameAspect(pages), absoluteTolerance = 0.001f)
        assertEquals(
            Aspect.PORTRAIT_2_3.ratio,
            momentFrameAspect(pages.reversed()),
            absoluteTolerance = 0.001f,
        )
        // A page with no usable thumbnail metadata is ignored, not treated as 1:1.
        val withUnknown = pages + PayloadDescriptor(key = "mmnt_img9", contentType = "image/jpeg")
        assertEquals(
            Aspect.PORTRAIT_2_3.ratio,
            momentFrameAspect(withUnknown),
            absoluteTolerance = 0.001f,
        )
    }

    /** The bound is the viewport, not an aspect ratio — and it only ever shrinks. */
    @Test
    fun frameSize_fullWidthUntilTheBudgetBites() {
        val width = 328.dp
        val budget = 560.dp

        val fits = momentFrameSize(width, aspect = 0.75f, maxHeight = budget)
        assertEquals(width, fits.width)
        assertEquals(width.value / 0.75f, fits.height.value, absoluteTolerance = 0.01f)

        val clamped = momentFrameSize(width, aspect = 0.45f, maxHeight = budget)
        assertEquals(budget, clamped.height)
        assertEquals(budget.value * 0.45f, clamped.width.value, absoluteTolerance = 0.01f)
        assertTrue(clamped.width < width, "clamped cell must be narrower than the card")

        // No budget yet (container size unknown) → natural sizing, never a collapse.
        val unbounded = momentFrameSize(width, aspect = 0.45f, maxHeight = androidx.compose.ui.unit.Dp.Infinity)
        assertEquals(width, unbounded.width)
    }

    // ---- stubs ------------------------------------------------------------

    /**
     * The zoom wrapper resolves a [HomebaseImageLoader] from Koin at composition time.
     * Nothing ever fetches here (no credentials, and the mock engine 404s), but the
     * instance has to exist for the tree to compose.
     */
    private fun stubHomebaseImageLoader(): HomebaseImageLoader {
        val httpClient = HttpClient(MockEngine { respondError(HttpStatusCode.NotFound) })
        val credentials = CredentialsManager()
        val files = TempDirFileOperations()
        return HomebaseImageLoader(
            DriveFileProvider(httpClient, credentials, DriveFileProviderCached(httpClient, credentials, files)),
            files,
        )
    }

    private class TempDirFileOperations : FileOperationsProvider {
        private val root: String =
            System.getProperty("java.io.tmpdir").trimEnd('/') + "/homebase-moments-layout-test"

        override fun getCacheDirectory(): String = root
        override fun openFileInput(path: String): InputProvider = error("not used in layout tests")
        override suspend fun readFileBytes(path: String): ByteArray = ByteArray(0)
        override fun deleteTempFile(path: String): Boolean = true
        override fun getFileSize(path: String): Long = 0L
        override suspend fun writeBytesToTempFile(bytes: ByteArray, prefix: String, suffix: String): String =
            "$root/$prefix$suffix"

        override suspend fun writeBytesToShareOutboundFile(bytes: ByteArray, suffix: String): String =
            "$root/share$suffix"

        override suspend fun writeStream(path: String, data: Flow<ByteArray>) = Unit
    }
}
