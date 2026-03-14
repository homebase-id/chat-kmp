package id.homebase.core.widget

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import id.homebase.core.shapes.SquircleShape

@Composable
fun SquircleIcon(
    modifier: Modifier = Modifier,
    imageVector: ImageVector ,
    contentDescription: String? = null,
    tint: Color = Color.Unspecified,
    ) {
    Icon(
        imageVector = imageVector,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.clip(SquircleShape(cornerSmoothing = 0.6f))
    )
}