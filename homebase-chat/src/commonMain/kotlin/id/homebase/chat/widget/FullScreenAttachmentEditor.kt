package id.homebase.chat.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.mohamedrejeb.richeditor.model.RichTextState
import id.homebase.chat.conversationlist.AttachmentPendingFile
import id.homebase.chat.conversationlist.FullScreenOverlay
import id.homebase.chat.widget.video.LocalVideoPlayerSurface
import id.homebase.resources.MR
import id.homebase.resources.chat_message_add_gallery_image
import id.homebase.resources.chat_message_remove_gallery_image
import id.homebase.resources.crop
import id.homebase.resources.draw
import id.homebase.resources.menu_back
import id.homebase.resources.save
import io.github.vinceglb.filekit.name
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullScreenAttachmentEditor(
    modifier: Modifier = Modifier,
    data: FullScreenOverlay.AttachmentData,
    textFieldState: RichTextState,
    currentPage: Int,
    onPageChanged: (Int) -> Unit,
    onSaveFile: (file: AttachmentPendingFile) -> Unit,
    onAddFile: () -> Unit,
    onAddImage: () -> Unit,
    onRemoveFile: (conversationId: Uuid, attachmentId: Uuid) -> Unit,
    onSendMessage: (conversationId: Uuid, message: String, files: List<AttachmentPendingFile>) -> Unit,
    onDismiss: () -> Unit,
    onCropImage: (conversationId: Uuid, attachmentId: Uuid) -> Unit = { _, _ -> },
    onDrawImage: (conversationId: Uuid, attachmentId: Uuid) -> Unit = { _, _ -> },
) {
    val isFileMode = data.attachments.all { it is AttachmentPendingFile.File }
    val imageLoader: ImageLoader = koinInject()
    val pagerState = rememberPagerState(
        initialPage = currentPage.coerceIn(0, maxOf(0, data.attachments.size - 1)),
        pageCount = { data.attachments.size }
    )

    LaunchedEffect(pagerState.currentPage) {
        onPageChanged(pagerState.currentPage)
    }

    LaunchedEffect(data.attachments.size) {
        if (currentPage < data.attachments.size) {
            pagerState.scrollToPage(currentPage)
        }
    }
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
    ) {
        Box(
            modifier = Modifier.weight(1f)
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = true,
                beyondViewportPageCount = 1
            ) { page ->
                when (val attachment = data.attachments[page]) {
                    is AttachmentPendingFile.File -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.UploadFile, contentDescription = null, Modifier.size(96.dp))
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(attachment.file.name)
                        }
                    }
                    is AttachmentPendingFile.FileImage -> {
                        AsyncImage(
                            imageLoader = imageLoader,
                            model = attachment.file.toString(),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp)),
                            contentScale = ContentScale.Fit
                        )
                    }
                    is AttachmentPendingFile.FileVideo -> {
                        var isPlaying by remember(attachment.attachmentId) { mutableStateOf(false) }
                        var firstFrameRendered by remember(attachment.attachmentId) { mutableStateOf(false) }
                        // Show a Coil-decoded poster whenever we don't yet have the
                        // pre-extracted bytes — Coil's VideoFrameDecoder pulls a frame
                        // straight from the URI without our FFmpeg + temp-file dance.
                        val posterModel: Any = attachment.thumbnailBytes ?: attachment.file.toString()
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.Black),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isPlaying) {
                                LocalVideoPlayerSurface(
                                    filePath = attachment.file.toString(),
                                    modifier = Modifier.fillMaxSize(),
                                    onFirstFrameRendered = { firstFrameRendered = true },
                                )
                            }
                            if (!firstFrameRendered) {
                                AsyncImage(
                                    imageLoader = imageLoader,
                                    model = posterModel,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxWidth(),
                                    contentScale = ContentScale.Fit
                                )
                                Icon(
                                    Icons.Default.PlayCircle,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clickable { isPlaying = true },
                                    tint = Color.White.copy(alpha = 0.85f)
                                )
                            }
                        }
                    }
                    is AttachmentPendingFile.Gallery -> {
                        AsyncImage(
                            imageLoader = imageLoader,
                            model = attachment.image.thumbnailUri ?: attachment.image.file.toString(),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp)),
                            contentScale = ContentScale.Fit
                        )
                    }
                    is AttachmentPendingFile.Audio -> {
                        // not currently supported
                    }
                }
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
            ) {
                Icon(Icons.Default.Close, contentDescription = stringResource(MR.string.menu_back))
            }

            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .fillMaxWidth(0.6f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = data.conversationTitle,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }

        }

        // Attachment-strip row: thumbnails for every queued attachment with a
        // trailing "+" to add another. This row is just about managing the
        // collection of attachments — actions on the current one live below.
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                data.attachments.forEach { attachment ->
                    val isSelected = data.attachments[pagerState.currentPage].attachmentId == attachment.attachmentId
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(
                                width = if (isSelected) 2.dp else 0.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Unspecified,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable {
                                scope.launch {
                                    val idx = data.attachments.indexOfFirst {
                                        it.attachmentId == attachment.attachmentId
                                    }
                                    if (idx >= 0) pagerState.animateScrollToPage(idx)
                                }
                            }
                    ) {
                        when (attachment) {
                            is AttachmentPendingFile.File -> {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Default.UploadFile, contentDescription = null)
                                }
                            }
                            is AttachmentPendingFile.FileImage -> {
                                AsyncImage(
                                    imageLoader = imageLoader,
                                    model = attachment.file.toString(),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                            is AttachmentPendingFile.FileVideo -> {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    AsyncImage(
                                        imageLoader = imageLoader,
                                        model = attachment.thumbnailBytes ?: attachment.file.toString(),
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                    Icon(
                                        Icons.Default.PlayCircle,
                                        contentDescription = null,
                                        tint = Color.White.copy(alpha = 0.85f)
                                    )
                                }
                            }
                            is AttachmentPendingFile.Gallery -> {
                                AsyncImage(
                                    imageLoader = imageLoader,
                                    model = attachment.image.thumbnailUri ?: attachment.image.file.toString(),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                            is AttachmentPendingFile.Audio -> {
                                // not currently supported
                            }
                        }

                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.5f))
                                    .clickable { onRemoveFile(data.conversationId, attachment.attachmentId) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = stringResource(MR.string.chat_message_remove_gallery_image),
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
            }
            IconButton(
                onClick = if (isFileMode) onAddFile else onAddImage,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(MR.string.chat_message_add_gallery_image)
                )
            }
        }

        // Edit-tools row (Signal convention): actions on the current
        // attachment — crop (image only), download. Future tools (filters,
        // markup) would join this row.
        val currentAttachment = data.attachments.getOrNull(pagerState.currentPage)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (currentAttachment is AttachmentPendingFile.FileImage ||
                currentAttachment is AttachmentPendingFile.Gallery
            ) {
                IconButton(
                    onClick = { onCropImage(data.conversationId, currentAttachment.attachmentId) },
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Crop,
                        contentDescription = stringResource(MR.string.crop),
                    )
                }
                IconButton(
                    onClick = { onDrawImage(data.conversationId, currentAttachment.attachmentId) },
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Draw,
                        contentDescription = stringResource(MR.string.draw),
                    )
                }
            }
            if (currentAttachment != null) {
                IconButton(
                    onClick = { onSaveFile(currentAttachment) },
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = stringResource(MR.string.save),
                    )
                }
            }
        }

        MessageTextFieldForAttachment(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .imePadding(),
            state = textFieldState,
            onSmileyClick = {},
            onSendMessage = {
                onSendMessage(
                    data.conversationId,
                    textFieldState.toMarkdown().trimEnd(),
                    data.attachments
                )
            }
        )
    }
}