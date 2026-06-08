package id.homebase.chat.widget

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import id.homebase.chat.conversationlist.StickerCreateState
import id.homebase.chat.conversationlist.StickerVariant
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.chat_sticker_choose_title
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
                        state.variants.forEach { opt ->
                            VariantTile(
                                bytes = opt.bytes,
                                label = stringResource(opt.kind.label),
                                cd = stringResource(opt.kind.contentDescription),
                                selected = state.selected == opt.kind,
                                modifier = Modifier.weight(1f),
                                onClick = { onSelect(opt.kind) },
                            )
                        }
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
        // Checkerboard backdrop so a transparent cut-out's shape AND its white outline read
        // clearly (a white outline on a white card is invisible).
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).checkerboard(),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = remember(bytes) { bytes },
                contentDescription = cd,
                modifier = Modifier.fillMaxSize().padding(6.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            textAlign = TextAlign.Center,
        )
    }
}

/** Photoshop-style transparency checkerboard, drawn behind the content. */
private fun Modifier.checkerboard(
    cell: Dp = 7.dp,
    light: Color = Color(0xFFF6F6F6),
    dark: Color = Color(0xFFC9C9C9),
): Modifier = drawBehind {
    drawRect(light)
    val c = cell.toPx()
    if (c <= 0f) return@drawBehind
    var row = 0
    var y = 0f
    while (y < size.height) {
        var col = 0
        var x = 0f
        while (x < size.width) {
            if ((row + col) and 1 == 1) {
                drawRect(
                    color = dark,
                    topLeft = Offset(x, y),
                    size = Size(minOf(c, size.width - x), minOf(c, size.height - y)),
                )
            }
            x += c; col++
        }
        y += c; row++
    }
}
