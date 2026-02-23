package id.homebase.chat.widget.requests

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp


@Composable
fun ConnectionRequestBanner(
    hasRequests: Boolean,
    onClick: () -> Unit,
) {
    if (!hasRequests) return

    androidx.compose.material3.Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = "You have a new connection request",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
    }
}