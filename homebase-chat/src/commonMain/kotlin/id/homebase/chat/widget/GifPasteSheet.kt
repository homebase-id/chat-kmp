package id.homebase.chat.widget

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GifBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import id.homebase.core.ui.assets.HomebaseIcons
import id.homebase.core.ui.assets.StickerOutlined
import id.homebase.core.widget.AdaptiveSheet
import id.homebase.resources.MR
import id.homebase.resources.cd_gif_paste_as_gif
import id.homebase.resources.cd_gif_paste_as_sticker
import id.homebase.resources.cd_gif_paste_preview
import id.homebase.resources.chat_gif_paste_as_gif
import id.homebase.resources.chat_gif_paste_as_sticker
import id.homebase.resources.chat_gif_paste_title
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

@Composable
internal fun GifPasteSheet(
    bytes: ByteArray,
    onSendAsSticker: () -> Unit,
    onSendAsGif: () -> Unit,
    onDismiss: () -> Unit,
) {
    // On web Coil's singleton isn't our loader and has no animated decoder, so the preview would freeze.
    val imageLoader: ImageLoader = koinInject()
    AdaptiveSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 16.dp, bottom = 24.dp),
        ) {
            Text(
                text = stringResource(MR.string.chat_gif_paste_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            AsyncImage(
                model = bytes,
                imageLoader = imageLoader,
                contentDescription = stringResource(MR.string.cd_gif_paste_preview),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .padding(vertical = 16.dp),
            )
            StickerOptionRow(
                icon = HomebaseIcons.StickerOutlined,
                label = stringResource(MR.string.chat_gif_paste_as_sticker),
                contentDescription = stringResource(MR.string.cd_gif_paste_as_sticker),
                onClick = onSendAsSticker,
            )
            StickerOptionRow(
                icon = Icons.Default.GifBox,
                label = stringResource(MR.string.chat_gif_paste_as_gif),
                contentDescription = stringResource(MR.string.cd_gif_paste_as_gif),
                onClick = onSendAsGif,
            )
        }
    }
}
