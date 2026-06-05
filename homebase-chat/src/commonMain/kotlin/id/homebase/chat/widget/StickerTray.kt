@file:OptIn(ExperimentalMaterial3Api::class)

package id.homebase.chat.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.chat.conversationlist.ConversationListUiAction
import id.homebase.chat.services.sticker.SavedSticker
import id.homebase.chat.services.sticker.StickerStream
import id.homebase.chat.services.sticker.toImageData
import id.homebase.core.image.HomebaseImage
import id.homebase.resources.MR
import id.homebase.resources.cd_import_sticker
import id.homebase.resources.cd_sticker_thumbnail
import id.homebase.resources.chat_sticker_tray_title
import id.homebase.resources.chat_stickers_empty
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import id.homebase.core.util.detectContentTypeFromExtensionOrHint
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/**
 * Bottom-sheet wrapper around [StickerTray]. Self-contained like
 * [id.homebase.chat.event.EventComposerSheet]: pulls the saved-stickers flow from the
 * [StickerStream] singleton and routes selection/import/removal through [onUiAction], so
 * the host (`ConversationContent`) only owns the open/close boolean.
 *
 * Tapping a sticker sends it immediately and dismisses; the Import tile opens a FileKit
 * image picker (transparent-PNG/WebP), reads the bytes, and dispatches
 * [ConversationListUiAction.ImportSticker] (the handler enforces the alpha-gate).
 */
@Composable
fun StickerTraySheet(
    conversationId: Uuid,
    onUiAction: (ConversationListUiAction) -> Unit,
    onDismiss: () -> Unit,
) {
    val stickerStream: StickerStream = koinInject()
    val stickers by stickerStream.stickers.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    // Mount the optional Stickers drive the first time the tray is opened (mirror Moments).
    LaunchedEffect(Unit) {
        onUiAction(ConversationListUiAction.EnsureStickerDriveMounted)
    }

    val importPicker = rememberFilePickerLauncher(type = FileKitType.Image) { file ->
        if (file != null) {
            scope.launch {
                // PlatformFile.readBytes() is suspend. The alpha-gate (reject opaque)
                // + persistence happen in StickerHandler.handleImportSticker.
                val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@launch
                val contentType = detectContentTypeFromExtensionOrHint(file.name)
                onUiAction(ConversationListUiAction.ImportSticker(bytes, contentType))
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Text(
            text = stringResource(MR.string.chat_sticker_tray_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
        StickerTray(
            stickers = stickers,
            onStickerSelected = { sticker ->
                onUiAction(ConversationListUiAction.SendSavedSticker(conversationId, sticker))
                onDismiss()
            },
            onStickerLongPress = { sticker ->
                onUiAction(ConversationListUiAction.RemoveSticker(sticker))
            },
            onImportClick = { importPicker.launch() },
            modifier = Modifier.heightIn(max = 360.dp),
        )
    }
}

/**
 * The composer's "My Stickers" tray: a [LazyVerticalGrid] of the user's saved stickers
 * plus a leading Import tile. Tapping a sticker sends it immediately (the parent routes
 * to [id.homebase.chat.conversationlist.ConversationListUiAction.SendSavedSticker]);
 * long-pressing a sticker offers Remove.
 *
 * Stickers render bare on the surface (no opaque tile fill), matching how they appear in
 * a bubble — a transparent cut-out. All strings come from compose resources.
 */
@Composable
fun StickerTray(
    stickers: List<SavedSticker>,
    onStickerSelected: (SavedSticker) -> Unit,
    onStickerLongPress: (SavedSticker) -> Unit,
    onImportClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val importDescription = stringResource(MR.string.cd_import_sticker)
    val stickerDescription = stringResource(MR.string.cd_sticker_thumbnail)
    val emptyLabel = stringResource(MR.string.chat_stickers_empty)

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 88.dp),
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Leading Import tile.
        item {
            Box(
                modifier = Modifier
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { onImportClick() })
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = importDescription,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.sizeIn(maxWidth = 32.dp, maxHeight = 32.dp),
                )
            }
        }

        if (stickers.isEmpty()) {
            // Empty-state label spanning the rest of the row.
            item {
                Box(
                    modifier = Modifier.aspectRatio(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = emptyLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            items(stickers, key = { it.uniqueId }) { sticker ->
                StickerTile(
                    sticker = sticker,
                    contentDescription = stickerDescription,
                    onSelected = { onStickerSelected(sticker) },
                    onLongPress = { onStickerLongPress(sticker) },
                )
            }
        }
    }
}

@Composable
private fun StickerTile(
    sticker: SavedSticker,
    contentDescription: String,
    onSelected: () -> Unit,
    onLongPress: () -> Unit,
) {
    val imageData = sticker.toImageData()
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .pointerInput(sticker.uniqueId) {
                detectTapGestures(
                    onTap = { onSelected() },
                    onLongPress = { onLongPress() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (imageData != null) {
            HomebaseImage(
                imageData = imageData,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxWidth().padding(4.dp),
            )
        }
    }
}
