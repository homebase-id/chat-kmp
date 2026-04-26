package id.homebase.imageeditor.ui.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Free-rotation dial: horizontal slider in [-45, +45] degrees with snap-to-0
 * when the user releases within ±1°.
 *
 * Algorithm/UX inspired by Signal-Android's `RotationDial.kt` (AGPL-3.0); the
 * Compose implementation is original.
 */
@Composable
fun RotationDial(
    valueDegrees: Float,
    onValueChange: (Float) -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "",
) {
    var currentValue by remember(valueDegrees) { mutableStateOf(valueDegrees) }
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label)
            Text(text = "${currentValue.roundToInt()}°")
        }
        Slider(
            value = currentValue,
            onValueChange = {
                currentValue = it
                onValueChange(it)
            },
            onValueChangeFinished = {
                if (abs(currentValue) < SNAP_TO_ZERO_THRESHOLD) {
                    currentValue = 0f
                    onValueChange(0f)
                }
                onRelease()
            },
            valueRange = -MAX_DEG..MAX_DEG,
            colors = SliderDefaults.colors(),
        )
    }
}

private const val MAX_DEG: Float = 45f
private const val SNAP_TO_ZERO_THRESHOLD: Float = 1f
