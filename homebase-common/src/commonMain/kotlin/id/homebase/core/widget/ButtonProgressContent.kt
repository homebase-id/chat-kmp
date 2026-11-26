package id.homebase.core.widget

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** A text button's content: [idle], or a spinner before [busyLabel] while its work runs. */
@Composable
fun ButtonProgressContent(busy: Boolean, busyLabel: String, idle: String) {
    if (busy) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
        Spacer(Modifier.width(8.dp))
        Text(busyLabel)
    } else {
        Text(idle)
    }
}
