package id.homebase.core.ui.screens.moments.widget

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import id.homebase.api.client.drives.files.PayloadDescriptor

/**
 * Share of the window height one feed media cell may occupy.
 *
 * The rule the feed follows is "as large as possible while staying fully
 * visible" — width is the whole card and height is only ever bounded by the
 * viewport, never by a hardcoded aspect ratio. This constant is that bound, and
 * the single place to tune it.
 *
 * 0.70 keeps ~30% of the window for everything that is not the photo: the
 * status/navigation bars, the Moments top bar, the bottom navigation bar and the
 * card's own date pill / engagement affordances. Below roughly this value a
 * portrait photo that would otherwise fit edge-to-edge starts being needlessly
 * shrunk; above it a tall portrait pushes the post's own chrome off screen.
 */
internal const val FeedMediaViewportHeightFraction = 0.70f

/**
 * Height budget for a feed media cell — [FeedMediaViewportHeightFraction] of the
 * window height. Returns [Dp.Infinity] (i.e. no bound, natural sizing) while the
 * container size is still unknown, so the first frame never collapses to zero.
 */
@Composable
internal fun momentMediaMaxHeight(): Dp {
    val containerHeightPx = LocalWindowInfo.current.containerSize.height
    if (containerHeightPx <= 0) return Dp.Infinity
    return with(LocalDensity.current) {
        (containerHeightPx * FeedMediaViewportHeightFraction).toDp()
    }
}

/**
 * Frame aspect (`width / height`) for a single feed payload: its natural ratio,
 * clamped wide by [MaxFeedPhotoAspect] so a panorama doesn't render as a sliver.
 * Portraits are never clamped here — their bound is the height budget, not a
 * ratio (that clamp-by-ratio is what letterboxed them, #1128).
 */
internal fun momentFrameAspect(payload: PayloadDescriptor): Float =
    (aspectRatioFor(payload) ?: 1f).coerceAtMost(MaxFeedPhotoAspect)

/**
 * Shared frame aspect for a multi-payload carousel: the **tallest** page's ratio,
 * so no page ever has to be cropped or squashed into the shared frame. Pages with
 * no usable thumbnail metadata are ignored (1:1 when none are usable).
 *
 * Deriving it from the tallest page — rather than page 0, or a fixed 4:5 — is what
 * keeps a landscape-first carousel from crushing later portrait pages into a short
 * strip (#873) while still giving every page one stable frame to page through.
 */
internal fun momentFrameAspect(payloads: List<PayloadDescriptor>): Float =
    payloads.mapNotNull { aspectRatioFor(it) }.minOrNull()
        ?.coerceAtMost(MaxFeedPhotoAspect)
        ?: 1f

/**
 * The feed's one sizing rule, as a pure function.
 *
 * Full [availableWidth] whenever the resulting height fits [maxHeight]; otherwise
 * the cell shrinks — keeping [aspect] exactly, so nothing is cropped or stretched —
 * until its height is the budget. Left/right dead space is therefore possible only
 * for media taller than the budget.
 */
internal fun momentFrameSize(availableWidth: Dp, aspect: Float, maxHeight: Dp): DpSize {
    val safeAspect = if (aspect.isFinite() && aspect > 0f) aspect else 1f
    val naturalHeight = availableWidth / safeAspect
    val bounded = maxHeight.value.isFinite() && naturalHeight > maxHeight
    return if (bounded) {
        DpSize(width = maxHeight * safeAspect, height = maxHeight)
    } else {
        DpSize(width = availableWidth, height = naturalHeight)
    }
}

/**
 * Lays out one feed media cell per [momentFrameSize] and hosts [content] inside it.
 *
 * The cell is centred in the full-width slot, so on the (rare) budget-bounded path
 * the leftover width splits evenly instead of hanging off one edge. Callers render
 * their media with `Modifier.fillMaxSize()` — the frame owns the geometry.
 */
@Composable
internal fun MomentMediaFrame(
    aspect: Float,
    maxHeight: Dp,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth().testTag(MomentMediaTestTags.MEDIA_SLOT),
        contentAlignment = Alignment.Center,
    ) {
        val size = momentFrameSize(maxWidth, aspect, maxHeight)
        Box(
            modifier = Modifier
                .width(size.width)
                .height(size.height)
                .testTag(MomentMediaTestTags.MEDIA),
            content = content,
        )
    }
}
