package id.homebase.chat.widget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import id.homebase.core.image.HomebaseImage
import id.homebase.core.image.HomebaseImageData
import id.homebase.resources.MR
import id.homebase.resources.cd_sticker_options_add
import id.homebase.resources.cd_sticker_options_remove
import id.homebase.resources.cd_sticker_options_save
import id.homebase.resources.cd_sticker_thumbnail
import id.homebase.resources.chat_sticker_add_to_library
import id.homebase.resources.chat_sticker_options_title
import id.homebase.resources.chat_sticker_remove_from_library
import id.homebase.resources.chat_sticker_save_to_device
import org.jetbrains.compose.resources.stringResource

/**
 * WhatsApp-style bottom sheet shown when a sticker MESSAGE is tapped (instead of opening the
 * fullscreen media viewer). It is purely about the user's own sticker collection plus a device
 * export — there is no "delete message" action here.
 *
 * Mirrors [id.homebase.core.widget.ReactionsBottomSheet]'s [ModalBottomSheet] pattern:
 *  - "Add to my stickers" — shown when the sticker is NOT already saved.
 *  - "Remove from my stickers" — shown INSTEAD of "Add" when it IS saved (does NOT delete the
 *     message).
 *  - "Save to device" — always shown; exports the sticker image to the gallery/files.
 *
 * Each row fires its callback then the caller dismisses; dismissing clears the sheet state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StickerOptionsSheet(
    stickerImage: HomebaseImageData,
    isAlreadySaved: Boolean,
    onAddToLibrary: () -> Unit,
    onRemoveFromLibrary: () -> Unit,
    onSaveToDevice: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetMaxWidth = 640.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp),
        ) {
            val previewHeight = 160.dp
            val stickerAspect = stickerImage.previewThumbnail
                ?.takeIf { it.pixelWidth > 0 && it.pixelHeight > 0 }
                ?.let { (it.pixelWidth.toFloat() / it.pixelHeight.toFloat()).coerceIn(0.6f, 1.8f) }
                ?: 1f

            HomebaseImage(
                imageData = stickerImage,
                contentDescription = stringResource(MR.string.cd_sticker_thumbnail),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 8.dp, bottom = 16.dp)
                    .size(width = previewHeight * stickerAspect, height = previewHeight),
            )

            Text(
                text = stringResource(MR.string.chat_sticker_options_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (isAlreadySaved) {
                StickerOptionRow(
                    icon = Icons.Filled.Delete,
                    label = stringResource(MR.string.chat_sticker_remove_from_library),
                    contentDescription = stringResource(MR.string.cd_sticker_options_remove),
                    onClick = onRemoveFromLibrary,
                )
            } else {
                StickerOptionRow(
                    icon = Icons.Filled.Add,
                    label = stringResource(MR.string.chat_sticker_add_to_library),
                    contentDescription = stringResource(MR.string.cd_sticker_options_add),
                    onClick = onAddToLibrary,
                )
            }

            StickerOptionRow(
                icon = Icons.Filled.Download,
                label = stringResource(MR.string.chat_sticker_save_to_device),
                contentDescription = stringResource(MR.string.cd_sticker_options_save),
                onClick = onSaveToDevice,
            )
        }
    }
}

@Composable
private fun StickerOptionRow(
    icon: ImageVector,
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
