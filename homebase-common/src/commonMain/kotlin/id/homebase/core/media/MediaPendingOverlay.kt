package id.homebase.core.media

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * Full-bleed, tappable "media is still arriving" overlay shared by the chat and
 * vault full-screen viewers — a scrim with a centered progress indicator.
 * Tapping anywhere invokes [onTap] so the viewer chrome can still be toggled.
 * Used while a payload is pending (e.g. a not-yet-uploaded attachment), as
 * opposed to [MediaUnavailablePlaceholder] which signals a failed/undecodable
 * payload.
 */
@Composable
fun MediaPendingOverlay(
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.38f)),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(48.dp),
            color = MaterialTheme.colorScheme.inversePrimary,
        )
    }
}
