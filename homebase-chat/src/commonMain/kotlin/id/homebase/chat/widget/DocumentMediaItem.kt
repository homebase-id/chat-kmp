package id.homebase.chat.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.core.ui.assets.Apk
import id.homebase.core.ui.assets.Excel
import id.homebase.core.ui.assets.File
import id.homebase.core.ui.assets.FileCode
import id.homebase.core.ui.assets.FileZip
import id.homebase.core.ui.assets.HomebaseIcons
import id.homebase.core.ui.assets.Pdf
import id.homebase.core.ui.assets.WordFile
import id.homebase.core.ui.theme.Dimens
import id.homebase.core.util.formatFileSize

@Composable
fun DocumentMediaItem(
    payload: PayloadDescriptor,
    modifier: Modifier = Modifier,
    onDownloadClick: (() -> Unit)? = null,
    isDownloading: Boolean = false
) {
    val contentType = payload.contentType ?: ""
    val fileName = payload.descriptorContent ?: payload.key
    val fileSize = payload.bytesWritten?.formatFileSize() ?: ""

    // Map content types to specific icons based on the requested specification
    val fileIcon: ImageVector = when {
        contentType == "application/pdf" -> HomebaseIcons.Pdf
        contentType == "application/msword" || contentType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> HomebaseIcons.WordFile

        contentType == "application/vnd.ms-excel" || contentType == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> HomebaseIcons.Excel

        contentType == "application/zip" || contentType == "application/x-rar-compressed" -> HomebaseIcons.FileZip

        contentType == "application/javascript" || contentType == "application/json" -> HomebaseIcons.FileCode

        contentType == "application/vnd.android.package-archive" -> HomebaseIcons.Apk
        else -> HomebaseIcons.File
    }

    Row(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(Dimens.Message.cornerRadius))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable { onDownloadClick?.invoke() }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // File Icon
        Box(
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = fileIcon,
                contentDescription = "File Type Icon",
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // File Details
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = fileName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (fileSize.isNotEmpty()) {
                Text(
                    text = fileSize,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Download Action (always visible)
        IconButton(onClick = { onDownloadClick?.invoke() }, modifier = Modifier.size(40.dp)) {
            if (isDownloading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = "Download File",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
