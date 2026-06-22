package id.homebase.core.ui.screens.location.livelocation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import id.homebase.core.widget.AvatarImage
import id.homebase.resources.MR
import id.homebase.resources.live_location_age_minutes
import id.homebase.resources.live_location_marker_cd
import id.homebase.resources.live_location_you
import org.jetbrains.compose.resources.stringResource

/** Diameter of a live-location avatar dot. */
val LiveMarkerSize = 40.dp

/**
 * One map dot: a ringed avatar (self gets a primary-color ring, others a white ring for contrast
 * over tiles) plus, when the position is older than [AGE_LABEL_AFTER_MS], a small "Nm" age pill.
 */
@Composable
fun LiveLocationMarker(marker: LiveMarker, modifier: Modifier = Modifier) {
    val ringColor = if (marker.isSelf) MaterialTheme.colorScheme.primary else Color.White
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AvatarImage(
            modifier = Modifier.border(2.dp, ringColor, CircleShape).clip(CircleShape),
            avatarUrl = marker.avatarUrl,
            avatarInitials = marker.initials,
            size = LiveMarkerSize,
        )
        val ageMinutes = (marker.ageMs / 60_000L).toInt()
        if (marker.ageMs > AGE_LABEL_AFTER_MS && ageMinutes >= 1) {
            Text(
                text = stringResource(MR.string.live_location_age_minutes, ageMinutes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/** contentDescription for a marker — "You" for self, "<name>'s live location" otherwise. */
@Composable
fun liveMarkerContentDescription(marker: LiveMarker): String =
    if (marker.isSelf) stringResource(MR.string.live_location_you)
    else stringResource(MR.string.live_location_marker_cd, marker.key)
