package id.homebase.core.ui.screens.moments.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import id.homebase.core.util.formatMomentDate
import kotlin.time.Instant

/**
 * Small pill showing a moment's date. Reads "Today" / "Yesterday" / "May 8" /
 * "May 8, 2024" depending on how recent the timestamp is — see [formatMomentDate].
 *
 * With [overMedia] it is a dark-translucent overlay that reads on any photo and
 * matches the corner badges; without it there is nothing to scrim, so it takes a
 * tonal role pair instead.
 */
@Composable
fun MomentDatePill(
    timestamp: Instant,
    modifier: Modifier = Modifier,
    overMedia: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    Text(
        text = formatMomentDate(timestamp),
        color = if (overMedia) Color.White else colors.onSecondaryContainer,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (overMedia) Color.Black.copy(alpha = 0.45f) else colors.secondaryContainer)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}
