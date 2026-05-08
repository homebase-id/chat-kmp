package id.homebase.core.ui.screens.moments.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.homebase.api.image.ImageMetadata
import id.homebase.resources.MR
import id.homebase.resources.cd_moment_photo_info_chip
import id.homebase.resources.cd_moment_toggle_include_location
import id.homebase.resources.moments_chip_add_location
import id.homebase.resources.moments_chip_location_included
import kotlinx.datetime.LocalDateTime
import org.jetbrains.compose.resources.stringResource

/**
 * Floating photo-info pill for the moments composer pager. Renders nothing
 * unless the image has at least one piece of extracted metadata (date / camera
 * / GPS).
 *
 * Treats the location bit as the only privacy-sensitive field: date and
 * camera identity always show as plain pills (and travel with the post),
 * GPS is gated behind a tappable toggle. Tapping the location pill flips the
 * per-image opt-in via [onToggleLocation].
 *
 * Visually it's a row of pills overlaid on the photo, dark-translucent so
 * white text reads on any background.
 */
@Composable
fun MomentImageInfoChip(
    metadata: ImageMetadata?,
    includeLocation: Boolean,
    onToggleLocation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (metadata == null) return

    val dateLabel = metadata.capturedAt?.let { formatMonthYear(it) }
    val cameraLabel = formatCamera(metadata.cameraMake, metadata.cameraModel)
    val hasGps = metadata.latitude != null && metadata.longitude != null
    if (dateLabel == null && cameraLabel == null && !hasGps) return

    val photoInfoCd = stringResource(MR.string.cd_moment_photo_info_chip)
    Row(
        modifier = modifier
            .padding(horizontal = 12.dp)
            .semantics { contentDescription = photoInfoCd },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dateLabel != null) {
            InfoPill(icon = Icons.Default.CalendarToday, text = dateLabel)
        }
        if (cameraLabel != null) {
            InfoPill(icon = Icons.Default.PhotoCamera, text = cameraLabel)
        }
        if (hasGps) {
            LocationPill(included = includeLocation, onToggle = onToggleLocation)
        }
    }
}

@Composable
private fun InfoPill(icon: ImageVector, text: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LocationPill(included: Boolean, onToggle: () -> Unit) {
    val toggleCd = stringResource(MR.string.cd_moment_toggle_include_location)
    val label = stringResource(
        if (included) MR.string.moments_chip_location_included
        else MR.string.moments_chip_add_location
    )
    // Selected: filled primary container (clearly attached). Unselected: same
    // dark translucent treatment as the other info pills, so it reads as
    // "another piece of photo info you can tap to add" rather than a CTA.
    val (bg, fg) = if (included) {
        MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
    } else {
        Color.Black.copy(alpha = 0.55f) to Color.White
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(onClick = onToggle)
            .semantics { contentDescription = toggleCd }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = if (included) Icons.Default.Check else Icons.Default.LocationOn,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = label,
            color = fg,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * "Mar 2018"-style label. English-only for now — kotlinx-datetime 0.7.1
 * doesn't ship locale-aware month names, and the existing moments UI doesn't
 * yet localize date formatting. Localize when the rest of moments does.
 */
private fun formatMonthYear(dt: LocalDateTime): String {
    val short = dt.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
    return "$short ${dt.year}"
}

/**
 * Combine make + model into a short label, dropping the make when the model
 * already contains it ("Canon" + "Canon EOS 5D Mark IV" → "Canon EOS 5D Mark IV").
 * Returns null when neither is known.
 */
private fun formatCamera(make: String?, model: String?): String? {
    val trimmedMake = make?.trim()?.takeIf { it.isNotEmpty() }
    val trimmedModel = model?.trim()?.takeIf { it.isNotEmpty() }
    return when {
        trimmedModel != null && trimmedMake != null -> {
            if (trimmedModel.contains(trimmedMake, ignoreCase = true)) trimmedModel
            else "$trimmedMake $trimmedModel"
        }
        trimmedModel != null -> trimmedModel
        else -> trimmedMake
    }
}
