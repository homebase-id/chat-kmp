package id.homebase.chat.widget

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun MessagesSection(text: String) {
    Box(
        modifier =
            Modifier.fillMaxWidth().padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 0.dp
        ) {
            Text(
                text = text,
                modifier = Modifier.padding(
                    horizontal = 12.dp,
                    vertical = 6.dp
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

//@Immutable
//data class MessageSectionItem(
//    val firstMessageTime: Instant,
//    val messages: List<MessageUiModel>,
//    val date: LocalDate
//)

