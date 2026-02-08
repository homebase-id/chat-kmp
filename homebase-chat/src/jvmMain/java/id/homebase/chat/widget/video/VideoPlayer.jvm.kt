package id.homebase.chat.widget.video

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
actual fun VideoPlayer(
    videoUrl: String?,
    modifier: Modifier
) {

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(300.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (videoUrl != null) {
            // For now, show a placeholder text
            // To enable video playback on desktop, you would need to add VLC or JavaFX libraries
            Text(
                text = "Video player (Desktop)\n\nTo enable video playback, add VLC or JavaFX dependencies",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                text = "Loading video...",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
