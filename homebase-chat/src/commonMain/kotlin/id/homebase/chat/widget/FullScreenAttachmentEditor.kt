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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.mohamedrejeb.richeditor.model.RichTextState
import id.homebase.api.video.IndexedFrame
import id.homebase.api.video.VideoThumbnailExtractor
import id.homebase.chat.conversationlist.AttachmentPendingFile
import id.homebase.chat.conversationlist.FullScreenOverlay
import id.homebase.chat.widget.video.TrimDurationLabel
import id.homebase.chat.widget.video.TrimmableVideoPlayerSurface
import id.homebase.chat.widget.video.VideoTrimScrubber
import id.homebase.resources.MR
import id.homebase.resources.cd_file_attachment
import id.homebase.resources.cd_gallery_thumbnail
import id.homebase.resources.cd_image_attachment
import id.homebase.resources.cd_pause_video
import id.homebase.resources.cd_play_video
import id.homebase.resources.cd_send_to
import id.homebase.resources.cd_video_thumbnail
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
    onCameraClick: () -> Unit,
    onRemoveFile: (conversationId: Uuid, attachmentId: Uuid) -> Unit,
    onSendMessage: (conversationId: Uuid, message: String, files: List<AttachmentPendingFile>) -> Unit,
    onDismiss: () -> Unit,
    onCropImage: (conversationId: Uuid, attachmentId: Uuid) -> Unit = { _, _ -> },
    onDrawImage: (conversationId: Uuid, attachmentId: Uuid) -> Unit = { _, _ -> },
    onTrimChange: (conversationId: Uuid, attachmentId: Uuid, startMs: Long?, endMs: Long?) -> Unit = { _, _, _, _ -> },
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

    // Per-video ephemeral state, persists across page swipes within this editor
    // session. Trim handles' source-of-truth lives on the FileVideo model itself
    // (trimStartMs / trimEndMs) so it survives navigation; only player state is
    // local.
    val playheadByAtt = remember { mutableStateMapOf<Uuid, Long>() }
    val playingByAtt = remember { mutableStateMapOf<Uuid, Boolean>() }
    val seekRequestByAtt = remember { mutableStateMapOf<Uuid, Long?>() }
    val framesByAtt = remember { mutableStateMapOf<Uuid, SnapshotStateMap<Int, IndexedFrame>>() }
    val frameStripCount = 10

    val activeAttachment = data.attachments.getOrNull(pagerState.currentPage)
    val activeVideo = activeAttachment as? AttachmentPendingFile.FileVideo

    // Extract the thumbnail strip for the currently-visible video. Persist across
    // swipes by stashing in framesByAtt; cancellation happens automatically when
    // the user swipes to another page (LaunchedEffect re-keys).
    LaunchedEffect(activeVideo?.attachmentId, activeVideo?.durationMs) {
        val v = activeVideo ?: return@LaunchedEffect
        val dur = v.durationMs ?: return@LaunchedEffect
        if (dur <= 0L) return@LaunchedEffect
        val map = framesByAtt.getOrPut(v.attachmentId) { mutableStateMapOf() }
        if (map.size >= frameStripCount) return@LaunchedEffect
        VideoThumbnailExtractor.extractThumbnailStrip(
            filePath = v.file.toString(),
            durationMs = dur,
            frameCount = frameStripCount,
            targetHeightPx = 96,
        ).collect { f -> map[f.index] = f }
    }

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
                            Icon(Icons.Default.UploadFile, contentDescription = stringResource(MR.string.cd_file_attachment), Modifier.size(96.dp))
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(attachment.file.name)
                        }
                    }
                    is AttachmentPendingFile.FileImage -> {
                        AsyncImage(
                            imageLoader = imageLoader,
                            model = attachment.file,
                            contentDescription = stringResource(MR.string.cd_image_attachment),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp)),
                            contentScale = ContentScale.Fit
                        )
                    }
                    is AttachmentPendingFile.FileVideo -> {
                        val attId = attachment.attachmentId
                        val durationMs = attachment.durationMs
                        // Default to auto-play. VLCJ in particular doesn't decode a frame
                        // when started paused, so a default of false leaves a perpetual
                        // spinner until the user manually plays.
                        val isPlaying = playingByAtt[attId] ?: true
                        val seekRequest = seekRequestByAtt[attId]
                        val clipStart = attachment.trimStartMs ?: 0L
                        val clipEnd = attachment.trimEndMs ?: (durationMs ?: 0L)
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.Black),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (durationMs != null && durationMs > 0L) {
                                TrimmableVideoPlayerSurface(
                                    filePath = attachment.file.toString(),
                                    clipStartMs = clipStart,
                                    clipEndMs = clipEnd,
                                    isPlaying = isPlaying,
                                    seekRequestMs = seekRequest,
                                    onPositionMs = { ms ->
                                        // Use the locally-defaulted Boolean: until the user
                                        // taps play/pause, the map has no entry for this
                                        // attachment, so reading from it would yield null.
                                        if (isPlaying) {
                                            playheadByAtt[attId] = ms.coerceIn(clipStart, clipEnd)
                                        }
                                    },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                // Duration not known yet — show poster while extractThumbnailAsync
                                // resolves. Player mounts as soon as durationMs lands.
                                // Video poster intentionally falls back to the file PATH string, NOT the
                                // PlatformFile model: routing a video through PlatformFileFetcher would
                                // read the whole video into memory just for a poster. Posters come from
                                // VideoThumbnailExtractor.extractPosterFrame (desktop/iOS via ffmpeg,
                                // Android via MediaMetadataRetriever). Web's extractor is unimplemented on
                                // main, so thumbnailBytes is null and this is blank for now; the web impl
                                // (browser <video>+canvas, no 22 MB ffmpeg core) ships with the video PR.
                                val posterModel: Any = attachment.thumbnailBytes
                                    ?: attachment.file.toString()
                                AsyncImage(
                                    imageLoader = imageLoader,
                                    model = posterModel,
                                    contentDescription = stringResource(MR.string.cd_video_thumbnail),
                                    modifier = Modifier.fillMaxWidth(),
                                    contentScale = ContentScale.Fit,
                                )
                            }
                            IconButton(
                                onClick = { playingByAtt[attId] = !isPlaying },
                                modifier = Modifier.background(
                                    Color.Black.copy(alpha = 0.4f),
                                    CircleShape,
                                ),
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = stringResource(if (isPlaying) MR.string.cd_pause_video else MR.string.cd_play_video),
                                    tint = Color.White,
                                )
                            }
                        }
                    }
                    is AttachmentPendingFile.Gallery -> {
                        AsyncImage(
                            imageLoader = imageLoader,
                            model = attachment.image.thumbnailUri ?: attachment.image.file,
                            contentDescription = stringResource(MR.string.cd_gallery_thumbnail),
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
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(MR.string.cd_send_to, data.conversationTitle))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = data.conversationTitle,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }

        }

        // Inline trim bar — directly under the video player when the active
        // attachment is a video. Trim applies live as the user drags; the
        // FileVideo's trimStartMs/trimEndMs is the source-of-truth, and Send
        // ships whatever range the handles are at. Hidden until durationMs is
        // resolved (a few hundred ms after the editor opens).
        if (activeVideo != null && activeVideo.durationMs != null && activeVideo.durationMs > 0L) {
            val attId = activeVideo.attachmentId
            val durationMs = activeVideo.durationMs
            val startMs = activeVideo.trimStartMs ?: 0L
            val endMs = activeVideo.trimEndMs ?: durationMs
            val playheadMs = playheadByAtt[attId] ?: startMs
            val frames = framesByAtt[attId] ?: emptyMap()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    TrimDurationLabel(startMs = startMs, endMs = endMs, totalMs = durationMs)
                }
                VideoTrimScrubber(
                    durationMs = durationMs,
                    startMs = startMs,
                    endMs = endMs,
                    playheadMs = playheadMs,
                    frames = frames,
                    frameCount = frameStripCount,
                    onTrimChange = { newStart, newEnd ->
                        // A full-range result clears the trim.
                        val (rs, re) = if (newStart == 0L && newEnd == durationMs)
                            null to null
                        else
                            newStart to newEnd
                        onTrimChange(data.conversationId, attId, rs, re)
                        // Pause and seek so the user sees the new bound's frame.
                        // Default-to-playing: if the user hasn't toggled yet, the map has
                        // no entry, so we always write false to force pause on drag.
                        playingByAtt[attId] = false
                        seekRequestByAtt[attId] = playheadMs.coerceIn(newStart, newEnd)
                    },
                    onPlayheadChange = { ms ->
                        playheadByAtt[attId] = ms
                        seekRequestByAtt[attId] = ms
                    },
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
                                    Icon(Icons.Default.UploadFile, contentDescription = stringResource(MR.string.cd_file_attachment))
                                }
                            }
                            is AttachmentPendingFile.FileImage -> {
                                AsyncImage(
                                    imageLoader = imageLoader,
                                    model = attachment.file,
                                    contentDescription = stringResource(MR.string.cd_image_attachment),
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                            is AttachmentPendingFile.FileVideo -> {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    // Poster falls back to the file PATH string, not the PlatformFile model
                                    // (see the player-mount branch above for why). Blank on web until the web
                                    // VideoThumbnailExtractor lands with the video PR.
                                    AsyncImage(
                                        imageLoader = imageLoader,
                                        model = attachment.thumbnailBytes ?: attachment.file.toString(),
                                        contentDescription = stringResource(MR.string.cd_video_thumbnail),
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                    Icon(
                                        Icons.Default.PlayCircle,
                                        contentDescription = stringResource(MR.string.cd_play_video),
                                        tint = Color.White.copy(alpha = 0.85f)
                                    )
                                }
                            }
                            is AttachmentPendingFile.Gallery -> {
                                AsyncImage(
                                    imageLoader = imageLoader,
                                    model = attachment.image.thumbnailUri ?: attachment.image.file,
                                    contentDescription = stringResource(MR.string.cd_gallery_thumbnail),
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
                onClick = onCameraClick,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            ) {
                Icon(
                    imageVector = Icons.Default.PhotoCamera,
                    contentDescription = stringResource(MR.string.chat_message_add_gallery_image),
                )
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