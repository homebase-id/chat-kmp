package id.homebase.core.widget

import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.runtime.Composable

@Composable
fun connectedButtonShapes(index: Int, count: Int): ToggleButtonShapes = when {
    count == 1 -> ToggleButtonShapes(ToggleButtonDefaults.shape, ToggleButtonDefaults.pressedShape, ToggleButtonDefaults.checkedShape)
    index == 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
    index == count - 1 -> ButtonGroupDefaults.connectedTrailingButtonShapes()
    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
}
