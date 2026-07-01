package id.homebase.chat.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.core.image.HomebaseImage
import id.homebase.core.image.HomebaseImageData
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
import id.homebase.resources.MR
import id.homebase.resources.chat_message_download_file
import id.homebase.resources.chat_message_file_type_icon
import id.homebase.resources.chat_message_pdf_preview
import org.jetbrains.compose.resources.stringResource

@Composable
fun DocumentMediaItem(
    payload: PayloadDescriptor,
    modifier: Modifier = Modifier,
    onDownloadClick: () -> Unit,
    onLongPress: () -> Unit,
    isDownloading: Boolean = false,
    previewImageData: HomebaseImageData? = null,
    showDownloadButton: Boolean = true,
) {
    val contentType = payload.contentType ?: ""
    val fileName = payload.descriptorContent ?: payload.key
    val fileSize = payload.bytesWritten?.formatFileSize() ?: ""

    // Map content types to specific icons based on the requested specification
    val fileIcon: ImageVector = when (contentType) {
        "application/pdf" -> HomebaseIcons.Pdf
        "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> HomebaseIcons.WordFile
        "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> HomebaseIcons.Excel
        "application/zip", "application/x-rar-compressed" -> HomebaseIcons.FileZip
        "application/javascript", "application/json" -> HomebaseIcons.FileCode
        "application/vnd.android.package-archive" -> HomebaseIcons.Apk
        else -> HomebaseIcons.File
    }

    Column(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(Dimens.Message.cornerRadius))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .combinedClickable(
                onClick = onDownloadClick,
                onLongClick = onLongPress
            )
    ) {
        if (previewImageData != null) {
            HomebaseImage(
                imageData = previewImageData,
                modifier = Modifier.fillMaxWidth().height(180.dp),
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                contentDescription = stringResource(MR.string.chat_message_pdf_preview),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
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
                    contentDescription = stringResource(MR.string.chat_message_file_type_icon),
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

            if (showDownloadButton) {
                Spacer(modifier = Modifier.width(8.dp))

                IconButton(onClick = { onDownloadClick() }, modifier = Modifier.size(40.dp).testTag("downloadButton")) {
                    if (isDownloading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            modifier = Modifier.testTag("downloadButtonIcon"),
                            imageVector = Icons.Default.Download,
                            contentDescription = stringResource(MR.string.chat_message_download_file),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}
