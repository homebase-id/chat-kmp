package id.homebase.chat.widget

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import id.homebase.chat.conversationlist.StickerCreateState
import id.homebase.chat.conversationlist.StickerVariant
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.cd_sticker_variant_cutout
import id.homebase.resources.cd_sticker_variant_original
import id.homebase.resources.chat_sticker_choose_title
import id.homebase.resources.chat_sticker_variant_cutout
import id.homebase.resources.chat_sticker_variant_original
import id.homebase.resources.send
import org.jetbrains.compose.resources.stringResource

/**
 * Create-a-sticker chooser. Processing shows a spinner; Choose shows the cut-out (when
 * present) and the original side by side, the selected one highlighted, plus Send/Cancel.
 * Rendered from ConversationListScreen off the StickerCreator state flow.
 */
@Composable
fun StickerCreatorSheet(
    state: StickerCreateState,
    onSelect: (StickerVariant) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(text = stringResource(MR.string.chat_sticker_choose_title)) },
        text = {
            when (state) {
                is StickerCreateState.Processing ->
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(strokeWidth = 2.dp)
                    }
                is StickerCreateState.Choose ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (state.cutOutOutlined != null) {
                            VariantTile(
                                bytes = state.cutOutOutlined,
                                label = stringResource(MR.string.chat_sticker_variant_cutout),
                                cd = stringResource(MR.string.cd_sticker_variant_cutout),
                                selected = state.selected == StickerVariant.CutOut,
                                modifier = Modifier.weight(1f),
                                onClick = { onSelect(StickerVariant.CutOut) },
                            )
                        }
                        VariantTile(
                            bytes = state.original,
                            label = stringResource(MR.string.chat_sticker_variant_original),
                            cd = stringResource(MR.string.cd_sticker_variant_original),
                            selected = state.selected == StickerVariant.Original,
                            modifier = Modifier.weight(1f),
                            onClick = { onSelect(StickerVariant.Original) },
                        )
                    }
            }
        },
        confirmButton = {
            if (state is StickerCreateState.Choose) {
                TextButton(onClick = onSend) { Text(text = stringResource(MR.string.send)) }
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(text = stringResource(MR.string.cancel)) } },
    )
}

@Composable
private fun VariantTile(
    bytes: ByteArray, label: String, cd: String, selected: Boolean,
    modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    OutlinedCard(
        modifier = modifier.clickable(onClick = onClick),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        AsyncImage(
            model = remember(bytes) { bytes },
            contentDescription = cd,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).padding(6.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            textAlign = TextAlign.Center,
        )
    }
}
