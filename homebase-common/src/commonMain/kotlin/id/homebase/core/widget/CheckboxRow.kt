package id.homebase.core.widget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
fun CheckboxRow(
    modifier: Modifier = Modifier,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = modifier
            .clickable { onCheckedChange(!checked) },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
        )
        Checkbox(
            checked = checked,
            onCheckedChange = null // Handled by Row's clickable
        )
    }
}