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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import id.homebase.core.util.isMobile
import id.homebase.core.util.noRippleClickable
import id.homebase.resources.MR
import id.homebase.resources.chat_message_attachment_contact
import id.homebase.resources.chat_message_attachment_file
import id.homebase.resources.chat_message_attachment_gallery
import id.homebase.resources.chat_message_attachment_location
import id.homebase.resources.chat_message_needs_gallery_permission
import id.homebase.resources.chat_message_needs_gallery_permission_button_text
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentOptionsDisplay(
    visible: Boolean,
    content: @Composable ColumnScope.() -> Unit
) {
    if (visible) {
        val listState = rememberScrollState()
        Column(
            modifier = Modifier
                .verticalScroll(state = listState)
        ) {
            content()
        }
    }
}

@Composable
fun AttachmentGallery(
    onImageSelected: () -> Unit,
    onPermissionRequested: () -> Unit,
) {
    if (isMobile()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(MR.string.chat_message_needs_gallery_permission),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            ElevatedButton(onClick = { onPermissionRequested() }) {
                Text(stringResource(MR.string.chat_message_needs_gallery_permission_button_text))
            }
        }
    }
}

@Composable
fun AttachmentOptions(
    onGalleryClick: () -> Unit,
    onFileClick: () -> Unit,
    onContactClick: () -> Unit,
    onLocationClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        ) {
            item {
                AttachmentOption(
                    icon = Icons.Default.Image,
                    label = stringResource(MR.string.chat_message_attachment_gallery),
                    onClick = onGalleryClick
                )
            }
            item {
                AttachmentOption(
                    icon = Icons.Default.UploadFile,
                    label = stringResource(MR.string.chat_message_attachment_file),
                    onClick = onFileClick
                )
            }
            if (isMobile()) {
                item {
                    AttachmentOption(
                        icon = Icons.Default.AccountCircle,
                        label = stringResource(MR.string.chat_message_attachment_contact),
                        onClick = onContactClick
                    )
                }
                item {
                    AttachmentOption(
                        icon = Icons.Default.LocationOn,
                        label = stringResource(MR.string.chat_message_attachment_location),
                        onClick = onLocationClick
                    )
                }
            }
        }
    }
}

@Composable
private fun AttachmentOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier.width(72.dp).noRippleClickable({ onClick() }),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        IconButton(
            modifier = Modifier.size(48.dp),
            onClick = onClick,
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor =  MaterialTheme.colorScheme.onPrimaryContainer,
            ),
            shape = RoundedCornerShape(24.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
}
}
