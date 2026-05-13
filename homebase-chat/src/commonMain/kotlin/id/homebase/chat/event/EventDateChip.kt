package id.homebase.chat.event

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDateTime

@Composable
fun EventDateChip(
    local: LocalDateTime,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    shape: Shape = RoundedCornerShape(10.dp),
    compact: Boolean = false,
    fillHeight: Boolean = false,
) {
    val width = if (compact) 40.dp else 56.dp
    val height = if (compact) 44.dp else 64.dp
    val verticalPadding = if (compact) 4.dp else 8.dp
    val monthStyle = MaterialTheme.typography.labelSmall
    val dayStyle = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall

    val sizeModifier = if (fillHeight) {
        Modifier.width(width).fillMaxHeight()
    } else {
        Modifier.size(width = width, height = height)
    }

    Column(
        modifier = modifier
            .clip(shape)
            .background(containerColor)
            .then(sizeModifier)
            .padding(vertical = verticalPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = local.month.name.take(3),
            style = monthStyle,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
        )
        Text(
            text = local.day.toString(),
            style = dayStyle,
            fontWeight = FontWeight.Bold,
            color = contentColor,
        )
    }
}
