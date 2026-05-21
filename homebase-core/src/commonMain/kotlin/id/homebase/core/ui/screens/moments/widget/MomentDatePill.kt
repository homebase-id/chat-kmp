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
 * Small dark-translucent pill rendered in the corner of a feed card. Reads
 * "Today" / "Yesterday" / "May 8" / "May 8, 2024" depending on how recent the
 * timestamp is — see [formatMomentDate].
 *
 * Stays as a static overlay so it reads on any photo without competing with
 * the bottom-right indicator badges. Color/sizing matches those badges so
 * the corners feel like the same family.
 */
@Composable
fun MomentDatePill(
    timestamp: Instant,
    modifier: Modifier = Modifier,
) {
    Text(
        text = formatMomentDate(timestamp),
        color = Color.White,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}
