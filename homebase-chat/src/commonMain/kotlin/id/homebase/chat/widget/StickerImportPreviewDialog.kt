package id.homebase.chat.widget

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import id.homebase.chat.conversationlist.StickerImportPreview
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.chat_sticker_import_processing
import id.homebase.resources.chat_sticker_import_title
import id.homebase.resources.chat_sticker_import_use
import org.jetbrains.compose.resources.stringResource

/**
 * Confirm dialog for the smart-import opaque branch: a spinner while the background is
 * removed, then the cut-out preview with Use / Cancel. Rendered from `ConversationListScreen`
 * off the ViewModel's preview flow so it survives tab switches and panel collapse.
 */
@Composable
fun StickerImportPreviewDialog(
    preview: StickerImportPreview,
    onUse: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(text = stringResource(MR.string.chat_sticker_import_title)) },
        text = {
            Box(
                modifier = Modifier.fillMaxWidth().height(220.dp),
                contentAlignment = Alignment.Center,
            ) {
                when (preview) {
                    is StickerImportPreview.Processing ->
                        Text(text = stringResource(MR.string.chat_sticker_import_processing))

                    is StickerImportPreview.Ready ->
                        // remember(preview) keys on Ready.equals (content-based), so the
                        // ByteArray model is stable across recomposition — no re-decode/flicker.
                        AsyncImage(
                            model = remember(preview) { preview.bytes },
                            contentDescription = stringResource(MR.string.chat_sticker_import_title),
                            modifier = Modifier.aspectRatio(1f).padding(8.dp),
                        )
                }
            }
        },
        confirmButton = {
            if (preview is StickerImportPreview.Ready) {
                TextButton(onClick = onUse) { Text(text = stringResource(MR.string.chat_sticker_import_use)) }
            } else {
                Box(Modifier) { CircularProgressIndicator(strokeWidth = 2.dp) }
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(text = stringResource(MR.string.cancel)) }
        },
    )
}
