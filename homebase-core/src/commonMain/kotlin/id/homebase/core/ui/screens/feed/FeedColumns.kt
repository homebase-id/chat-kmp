package id.homebase.core.ui.screens.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

internal const val FEED_CENTRE_TAG = "feedCentre"
internal const val FEED_SUGGESTIONS_TAG = "feedSuggestions"

// dotyoucore-js SocialFeed: container max-w-7xl, 4 columns, 16dp gap, timeline spans two, suggestions one.
internal val FeedColumnGap = 16.dp
internal val FeedContentMaxWidth = 632.dp
// Wider than the web's 308 so a handle fits beside the follow toggle without truncating mid-domain.
internal val FeedSuggestionsWidth = 336.dp

// Wide mode floats the posts as rounded cards on the page with the column gap around them, like the side cards.
internal fun feedListPadding(wide: Boolean) = PaddingValues(top = if (wide) FeedColumnGap else 10.dp, bottom = 16.dp)
internal fun feedPostGap(wide: Boolean) = if (wide) FeedColumnGap else 10.dp

@Composable
internal fun Modifier.feedPostCard(wide: Boolean): Modifier = if (wide) clip(MaterialTheme.shapes.large) else this

// The content slot sits at the same tree position in both modes, so the list keeps its composition across the gate.
@Composable
internal fun FeedColumns(
    wide: Boolean,
    modifier: Modifier = Modifier,
    suggestions: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .then(
                    if (wide) Modifier.widthIn(max = FeedContentMaxWidth + FeedColumnGap + FeedSuggestionsWidth)
                    else Modifier.fillMaxWidth(),
                ),
            horizontalArrangement = Arrangement.spacedBy(if (wide) FeedColumnGap else 0.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .testTag(FEED_CENTRE_TAG),
            ) { content() }
            if (wide) {
                Box(
                    modifier = Modifier
                        .width(FeedSuggestionsWidth)
                        .fillMaxHeight()
                        .testTag(FEED_SUGGESTIONS_TAG),
                ) { suggestions() }
            }
        }
    }
}
