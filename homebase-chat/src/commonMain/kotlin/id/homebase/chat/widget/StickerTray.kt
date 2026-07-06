package id.homebase.chat.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import id.homebase.chat.services.sticker.SavedSticker
import id.homebase.chat.services.sticker.toImageData
import id.homebase.core.image.HomebaseImage
import id.homebase.core.ui.assets.HomebaseIcons
import id.homebase.core.ui.assets.StickerAdd
import id.homebase.resources.MR
import id.homebase.resources.cd_import_sticker
import id.homebase.resources.cd_sticker_thumbnail
import id.homebase.resources.chat_stickers_empty
import org.jetbrains.compose.resources.stringResource

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
    isLoaded: Boolean,
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
                    imageVector = HomebaseIcons.StickerAdd,
                    contentDescription = importDescription,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.sizeIn(maxWidth = 32.dp, maxHeight = 32.dp),
                )
            }
        }

        when {
            // Still hydrating the library from the local DB — show a spinner instead of
            // the empty label so a populated tray doesn't flash "no stickers yet".
            !isLoaded -> item {
                Box(
                    modifier = Modifier.aspectRatio(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                }
            }

            // Loaded and genuinely empty.
            stickers.isEmpty() -> item {
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

            else -> items(stickers, key = { it.uniqueId }) { sticker ->
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
    // A pending sticker is still uploading: the server hasn't assigned its real fileId yet,
    // so its thumbnail can't be fetched and it can't be sent or deleted (both would target a
    // placeholder id). Show a spinner and ignore taps until the outbox confirms it
    // (StickerStream then clears isPending and the real row takes over).
    if (sticker.isPending) {
        StickerTileSpinner(Modifier.aspectRatio(1f))
        return
    }
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
                // While the thumb is loading — or while its payload is still uploading and the
                // fetch 404s — show a spinner, not the default broken-image icon. Once the
                // payload lands the row re-emits and the thumb resolves.
                placeholder = { StickerTileSpinner(Modifier.fillMaxSize()) },
                error = { StickerTileSpinner(Modifier.fillMaxSize()) },
            )
        }
    }
}

/**
 * Small centered spinner used for a sticker tile that is uploading / loading its thumbnail.
 * Matches the sent-image upload treatment (a white indicator + translucent track on a dark
 * scrim, see [id.homebase.chat.widget.MediaMessage]) so a still-uploading sticker reads the
 * same as any other media that is still arriving, instead of the default `primary` blue.
 */
@Composable
private fun StickerTileSpinner(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(Color.Black.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.sizeIn(maxWidth = 20.dp, maxHeight = 20.dp),
            color = Color.White,
            trackColor = Color.White.copy(alpha = 0.2f),
            strokeWidth = 2.dp,
        )
    }
}
