package id.homebase.core.ui.screens.moments.widget

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp

@Composable
internal fun MomentTrimDurationLabel(startMs: Long, endMs: Long, totalMs: Long) {
    val label = formatDurationLabel(endMs - startMs) +
        " / " +
        formatDurationLabel(totalMs)
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
