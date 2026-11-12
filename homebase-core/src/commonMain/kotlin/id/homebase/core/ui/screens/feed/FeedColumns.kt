package id.homebase.core.ui.screens.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

internal const val FEED_CENTRE_TAG = "feedCentre"
internal const val FEED_SUGGESTIONS_TAG = "feedSuggestions"

// dotyoucore-js SocialFeed: container max-w-7xl, 4 columns, 16dp gap, timeline spans two, suggestions one.
internal val FeedColumnGap = 16.dp
internal val FeedContentMaxWidth = 632.dp
internal val FeedSuggestionsWidth = 308.dp

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
