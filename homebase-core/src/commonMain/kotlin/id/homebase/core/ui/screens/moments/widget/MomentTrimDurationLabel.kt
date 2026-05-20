package id.homebase.core.ui.screens.moments.widget

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp

/**
 * Inlined moments-side copy of `id.homebase.chat.widget.video.TrimDurationLabel`,
 * which is `internal` to the chat module. Same visual output; lift this when
 * we refactor the editor into a shared widget.
 */
private fun formatDurationLabel(ms: Long): String {
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "$m:${s.toString().padStart(2, '0')}"
}

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
