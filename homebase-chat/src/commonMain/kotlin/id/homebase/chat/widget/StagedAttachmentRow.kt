package id.homebase.chat.widget

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.homebase.chat.services.staged.StagedAttachment
import id.homebase.chat.services.staged.StagedLinkPreview
import id.homebase.chat.services.staged.StagedLocationPreview

/**
 * UI dispatcher for a single staged attachment shown above the chat input bar.
 *
 * One `when` branch per [StagedAttachment] subtype — the only place the composer needs to know
 * how each kind looks. Adding a new kind: add a branch here that delegates to its existing
 * `XyzPreviewCard(isCompact = true, onCancel = ...)`.
 */
@Composable
fun StagedAttachmentRow(
    attachment: StagedAttachment,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rowModifier = modifier.fillMaxWidth().padding(bottom = 8.dp)
    when (attachment) {
        is StagedLinkPreview -> LinkPreviewCard(
            linkPreview = attachment.preview,
            modifier = rowModifier,
            isCompact = true,
            onCancel = onCancel,
        )
        is StagedLocationPreview -> LocationPreviewCard(
            locationPreview = attachment.preview,
            modifier = rowModifier,
            isCompact = true,
            onCancel = onCancel,
        )
    }
}
