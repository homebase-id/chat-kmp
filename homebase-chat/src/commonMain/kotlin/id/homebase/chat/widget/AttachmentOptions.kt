package id.homebase.chat.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentOptionsDisplay(
    visible: Boolean,
    height: Dp,
    content: @Composable ColumnScope.() -> Unit
) {
    if (visible) {
        val listState = rememberScrollState()
        Column(
            modifier = Modifier
                .height(height)
                .verticalScroll(state = listState)
        ) {
            content()
        }
    }
}

@Composable
fun AttachmentOptions(
    onImageClick: () -> Unit,
    onVideoClick: () -> Unit,
    onFileClick: () -> Unit,
    onCameraClick: () -> Unit,
    onLocationClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AttachmentOption(
            icon = Icons.Default.Image,
            label = "Photos/Images",
            onClick = onImageClick
        )
        AttachmentOption(
            icon = Icons.Default.VideoFile,
            label = "Video",
            onClick = onVideoClick
        )
        AttachmentOption(
            icon = Icons.Default.AttachFile,
            label = "Document",
            onClick = onFileClick
        )
        AttachmentOption(
            icon = Icons.Default.Camera,
            label = "Camera",
            onClick = onCameraClick
        )
        AttachmentOption(
            icon = Icons.Default.LocationOn,
            label = "Location",
            onClick = onLocationClick
        )
    }
}

@Composable
private fun AttachmentOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(label)
    }
}
