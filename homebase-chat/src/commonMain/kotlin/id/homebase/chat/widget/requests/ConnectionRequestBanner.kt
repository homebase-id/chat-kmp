package id.homebase.chat.widget.requests

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.homebase.chat.data.IncomingConnectionRequestUiModel

@Composable
fun ConnectionRequestBanner(
    incomingRequests: List<IncomingConnectionRequestUiModel>,
    onClick: () -> Unit,
) {
     if (incomingRequests.isEmpty()) return

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = "You have ${incomingRequests.count()} connection request(s)",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}