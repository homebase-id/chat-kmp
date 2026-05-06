package id.homebase.chat.widget

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.homebase.chat.services.renderer.AttachmentRenderer
import id.homebase.chat.services.renderer.LinkPreviewRenderer
import id.homebase.chat.services.renderer.LocationPreviewRenderer

/**
 * UI dispatcher for a single attachment renderer shown above the chat input bar.
 *
 * One `when` branch per [AttachmentRenderer] subtype — the only place the composer needs to
 * know how each kind looks. Adding a new kind: add a branch here that delegates to its
 * existing `XyzPreviewCard(isCompact = true, onCancel = ...)`.
 */
@Composable
fun AttachmentRendererRow(
    attachment: AttachmentRenderer,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rowModifier = modifier.fillMaxWidth().padding(bottom = 8.dp)
    when (attachment) {
        is LinkPreviewRenderer -> LinkPreviewCard(
            linkPreview = attachment.preview,
            modifier = rowModifier,
            isCompact = true,
            onCancel = onCancel,
        )
        is LocationPreviewRenderer -> LocationPreviewCard(
            locationPreview = attachment.preview,
            modifier = rowModifier,
            isCompact = true,
            onCancel = onCancel,
        )
    }
}
