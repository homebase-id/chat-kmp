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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.mohamedrejeb.richeditor.model.RichTextState
import id.homebase.chat.AttachmentPendingFile
import id.homebase.chat.FullScreenOverlay
import id.homebase.resources.MR
import id.homebase.resources.chat_message_add_gallery_image
import id.homebase.resources.chat_message_remove_gallery_image
import id.homebase.resources.menu_back
import id.homebase.resources.save
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
    onRemoveFile: (conversationId: Uuid, attachmentId: Uuid) -> Unit,
    onSendMessage: (conversationId: Uuid, message: String, files: List<AttachmentPendingFile>) -> Unit,
    onDismiss: () -> Unit,
) {
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
                val uri = when (val attachment = data.attachments[page]) {
                    is AttachmentPendingFile.File -> attachment.file.toString()
                    is AttachmentPendingFile.Gallery -> attachment.image.thumbnailUri

                }
                AsyncImage(
                    imageLoader = imageLoader,
                    model = uri,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Fit
                )
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
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(data.conversationTitle, style = MaterialTheme.typography.labelSmall)
            }

            LazyRow(
                modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (data.attachments.size > 1) {
                    items(data.attachments) { attachment ->
                        val isSelected = data.attachments[pagerState.currentPage] == attachment
                        Box(
                            modifier = Modifier
                                .size(60.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(
                                    width = if (isSelected) 2.dp else 0.dp,
                                    color = if (isSelected) Color.White else Color.Unspecified,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable {
                                    // Navigate to this image
                                    scope.launch {
                                        pagerState.animateScrollToPage(data.attachments.indexOf(attachment))
                                    }
                                }
                        ) {
                            val uri = when (attachment) {
                                is AttachmentPendingFile.File -> attachment.file.toString()
                                is AttachmentPendingFile.Gallery -> attachment.image.thumbnailUri

                            }
                            AsyncImage(
                                imageLoader = imageLoader,
                                model = uri,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )

                            // Show trash overlay on selected image
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
                item {
                    // Plus button to add more images
                    IconButton(
                        onClick = onAddFile,
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
            }
        }

        Row(
            modifier = Modifier.padding(16.dp)
        ) {
            IconButton(
                onClick = {
                    onSaveFile(data.attachments[pagerState.currentPage])
                },
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = stringResource(MR.string.save)
                )
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
                    textFieldState.toText(),
                    data.attachments
                )
            }
        )
    }
}