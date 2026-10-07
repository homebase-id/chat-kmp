package id.homebase.chat.widget

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.foundation.layout.ColumnScope
import id.homebase.core.ui.theme.HomebaseTheme
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.ui.semantics.Role
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Hd
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.graphics.vector.ImageVector
import org.jetbrains.compose.resources.StringResource
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import id.homebase.core.camera.CaptureHandoff
import id.homebase.api.video.IndexedFrame
import id.homebase.api.video.VideoThumbnailService
import id.homebase.chat.conversationlist.AttachmentPendingFile
import id.homebase.core.pdf.generatePdfThumbnail
import id.homebase.core.ui.assets.HdOff
import id.homebase.core.ui.assets.HomebaseIcons
import id.homebase.core.util.resolveContentType
import id.homebase.chat.widget.video.TrimDurationLabel
import id.homebase.chat.widget.video.TrimmableVideoPlayerSurface
import id.homebase.chat.widget.video.VideoTrimScrubber
import id.homebase.api.image.MediaQuality
import id.homebase.resources.MR
import id.homebase.resources.cd_media_quality_high_off
import id.homebase.resources.cd_media_quality_high_on
import id.homebase.resources.chat_media_quality_hd
import id.homebase.resources.cd_file_attachment
import id.homebase.resources.cd_gallery_thumbnail
import id.homebase.resources.cd_image_attachment
import id.homebase.resources.cd_pause_video
import id.homebase.resources.cd_play_video
import id.homebase.resources.cd_video_thumbnail
import id.homebase.resources.chat_message_add_gallery_image
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonShapes
import id.homebase.resources.chat_message_remove_gallery_image
import id.homebase.resources.crop
import id.homebase.resources.draw
import id.homebase.resources.menu_back
import id.homebase.resources.chat_message_pdf_preview
import id.homebase.resources.save
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

internal data class EditorToolset(
    val showCrop: Boolean,
    val showDraw: Boolean,
    val showSave: Boolean,
    val showQuality: Boolean = false,
) {
    val showToolbar: Boolean get() = showCrop || showDraw || showSave
}

/** Pure decision for the per-attachment tool row. Crop/Draw apply only to
 *  editable non-GIF images (FileImage / Gallery); Save applies to any current
 *  attachment. A tool is shown only when its callback was supplied. */
internal fun editorToolsetFor(
    current: AttachmentPendingFile?,
    canCrop: Boolean,   // onCropImage != null
    canDraw: Boolean,   // onDrawImage != null
    canSave: Boolean,   // onSaveFile  != null
    canSetQuality: Boolean = false, // onToggleMediaQuality != null
): EditorToolset {
    val isEditableImage =
        current is AttachmentPendingFile.FileImage || current is AttachmentPendingFile.Gallery
    // Crop and draw re-encode to a single-frame JPEG, which would freeze a GIF. iOS gallery mimeType is "image/*".
    val isNonGifImage = when (current) {
        is AttachmentPendingFile.FileImage ->
            (current.sourceContentType ?: resolveContentType(fileName = current.file.name)) != "image/gif"
        is AttachmentPendingFile.Gallery ->
            current.image.mimeType != "image/gif" && resolveContentType(fileName = current.image.fileName) != "image/gif"
        else -> false
    }
    // Quality only bites on media we re-encode. A document or a voice note ships untouched either
    // way, so offering the toggle there would be a lie.
    val isQualityRelevant = isEditableImage || current is AttachmentPendingFile.FileVideo
    return EditorToolset(
        showCrop = canCrop && isNonGifImage,
        showDraw = canDraw && isNonGifImage,
        showSave = canSave && current != null,
        showQuality = canSetQuality && isQualityRelevant,
    )
}

internal fun isViewOnceCandidate(current: AttachmentPendingFile?): Boolean = when (current) {
    is AttachmentPendingFile.FileImage -> !current.forceSticker
    is AttachmentPendingFile.Gallery -> !current.forceSticker
    is AttachmentPendingFile.FileVideo -> true
    else -> false
}

internal fun isViewOnceEligible(attachments: List<AttachmentPendingFile>): Boolean =
    attachments.size == 1 && isViewOnceCandidate(attachments.single())

internal fun viewOnceToggleFor(
    attachments: List<AttachmentPendingFile>,
    requested: Boolean,
    onToggle: () -> Unit,
): ViewOnceToggle? =
    if (isViewOnceEligible(attachments)) ViewOnceToggle(requested, onToggle) else null

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaAttachmentEditor(
    attachments: List<AttachmentPendingFile>,
    currentPage: Int,
    onPageChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onCropImage: ((attachmentId: Uuid) -> Unit)? = null,
    onDrawImage: ((attachmentId: Uuid) -> Unit)? = null,
    onTrimChange: ((attachmentId: Uuid, startMs: Long?, endMs: Long?) -> Unit)? = null,
    onSaveFile: ((file: AttachmentPendingFile) -> Unit)? = null,
    onAddFile: (() -> Unit)? = null,
    onAddImage: (() -> Unit)? = null,
    onCameraClick: (() -> Unit)? = null,
    onRemoveFile: ((attachmentId: Uuid) -> Unit)? = null,
    /**
     * Signal and Telegram both make this button the setting rather than a per-send override, so
     * the toggle writes straight through to the global preference.
     */
    mediaQuality: MediaQuality = MediaQuality.STANDARD,
    onToggleMediaQuality: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    collapseSecondaryChrome: Boolean = false,
    centerImageInPage: Boolean = false,
    // False while the editor's own enter transition runs; a camera held over it waits for both.
    revealed: Boolean = true,
    imageOverlay: @Composable BoxScope.(AttachmentPendingFile) -> Unit = {},
    pagerTopEndSlot: @Composable BoxScope.() -> Unit = {},
    // Sits just above the attachment strip; whatever it shows takes room from the media rather than covering it.
    aboveStripSlot: @Composable ColumnScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
) {
    val isFileMode = attachments.all { it is AttachmentPendingFile.File }
    // Media is framed on black in both themes, like the camera it often comes from; documents keep the app theme.
    if (isFileMode) {
        MediaAttachmentEditorContent(attachments, currentPage, onPageChanged, modifier, onCropImage, onDrawImage, onTrimChange, onSaveFile, onAddFile, onAddImage, onCameraClick, onRemoveFile, mediaQuality, onToggleMediaQuality, onDismiss, collapseSecondaryChrome, centerImageInPage, revealed, imageOverlay, pagerTopEndSlot, aboveStripSlot, bottomBar)
    } else {
        HomebaseTheme(darkTheme = true, followsSystemTheme = false, updatesSystemChrome = false) {
            MediaAttachmentEditorContent(attachments, currentPage, onPageChanged, modifier, onCropImage, onDrawImage, onTrimChange, onSaveFile, onAddFile, onAddImage, onCameraClick, onRemoveFile, mediaQuality, onToggleMediaQuality, onDismiss, collapseSecondaryChrome, centerImageInPage, revealed, imageOverlay, pagerTopEndSlot, aboveStripSlot, bottomBar)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaAttachmentEditorContent(
    attachments: List<AttachmentPendingFile>,
    currentPage: Int,
    onPageChanged: (Int) -> Unit,
    modifier: Modifier,
    onCropImage: ((attachmentId: Uuid) -> Unit)?,
    onDrawImage: ((attachmentId: Uuid) -> Unit)?,
    onTrimChange: ((attachmentId: Uuid, startMs: Long?, endMs: Long?) -> Unit)?,
    onSaveFile: ((file: AttachmentPendingFile) -> Unit)?,
    onAddFile: (() -> Unit)?,
    onAddImage: (() -> Unit)?,
    onCameraClick: (() -> Unit)?,
    onRemoveFile: ((attachmentId: Uuid) -> Unit)?,
    mediaQuality: MediaQuality,
    onToggleMediaQuality: (() -> Unit)?,
    onDismiss: (() -> Unit)?,
    collapseSecondaryChrome: Boolean,
    centerImageInPage: Boolean,
    revealed: Boolean,
    imageOverlay: @Composable BoxScope.(AttachmentPendingFile) -> Unit,
    pagerTopEndSlot: @Composable BoxScope.() -> Unit,
    aboveStripSlot: @Composable ColumnScope.() -> Unit,
    bottomBar: @Composable () -> Unit,
) {
    val isFileMode = attachments.all { it is AttachmentPendingFile.File }
    val imageLoader: ImageLoader = koinInject()
    val pagerState = rememberPagerState(
        initialPage = currentPage.coerceIn(0, maxOf(0, attachments.size - 1)),
        pageCount = { attachments.size }
    )

    LaunchedEffect(pagerState.currentPage) {
        onPageChanged(pagerState.currentPage)
    }

    LaunchedEffect(attachments.size) {
        if (currentPage < attachments.size) {
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

    val activeAttachment = attachments.getOrNull(pagerState.currentPage)
    val activeVideo = activeAttachment as? AttachmentPendingFile.FileVideo

    // Pages whose media has drawn: a camera held over the editor leaves once the page on screen is one of them.
    val drawnAttachments = remember { mutableStateSetOf<Uuid>() }
    val markDrawn = { id: Uuid -> drawnAttachments += id }
    val markDrawnOnceLoaded = { id: Uuid ->
        { state: AsyncImagePainter.State ->
            if (state is AsyncImagePainter.State.Success || state is AsyncImagePainter.State.Error) markDrawn(id)
        }
    }
    val activeDrawn = activeAttachment != null && activeAttachment.attachmentId in drawnAttachments
    LaunchedEffect(activeDrawn, revealed, attachments.size) {
        if (activeDrawn && revealed) CaptureHandoff.contentShown()
    }

    // Extract the thumbnail strip for the currently-visible video. Persist across
    // swipes by stashing in framesByAtt; cancellation happens automatically when
    // the user swipes to another page (LaunchedEffect re-keys).
    LaunchedEffect(activeVideo?.attachmentId, activeVideo?.durationMs) {
        val v = activeVideo ?: return@LaunchedEffect
        val dur = v.durationMs ?: return@LaunchedEffect
        if (dur <= 0L) return@LaunchedEffect
        val map = framesByAtt.getOrPut(v.attachmentId) { mutableStateMapOf() }
        if (map.size >= frameStripCount) return@LaunchedEffect
        VideoThumbnailService.extractThumbnailStrip(
            videoPath = v.playablePath ?: v.file.toString(),
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
                // The close button gets its own band, so it never sits on the media's corner.
                modifier = Modifier.fillMaxSize().padding(top = if (onDismiss != null) CLOSE_BAND else 0.dp),
                userScrollEnabled = true,
                beyondViewportPageCount = 1
            ) { page ->
                // The pager composes a stale page index for one frame after the list shrinks.
                when (val attachment = attachments.getOrNull(page) ?: return@HorizontalPager) {
                    is AttachmentPendingFile.File -> {
                        val isPdf = remember(attachment.file) {
                            resolveContentType(fileName = attachment.file.name) == "application/pdf"
                        }
                        LaunchedEffect(attachment.attachmentId) { markDrawn(attachment.attachmentId) }
                        if (isPdf) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                PdfAttachmentPreview(
                                    file = attachment.file,
                                    imageLoader = imageLoader,
                                    maxWidth = 1080,
                                    contentDescription = stringResource(MR.string.chat_message_pdf_preview),
                                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)),
                                    contentScale = ContentScale.Fit,
                                )
                            }
                        } else {
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
                    }
                    is AttachmentPendingFile.FileImage -> {
                        Box(modifier = Modifier.fillMaxSize()) {
                            AsyncImage(
                                imageLoader = imageLoader,
                                model = attachment.file,
                                contentDescription = stringResource(MR.string.cd_image_attachment),
                                modifier = Modifier
                                    .then(if (centerImageInPage) Modifier.align(Alignment.Center) else Modifier)
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp)),
                                contentScale = ContentScale.Fit,
                                onState = markDrawnOnceLoaded(attachment.attachmentId),
                            )
                            imageOverlay(attachment)
                        }
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
                        // Sized to the poster's aspect, so a video floats on the surface like a photo instead of a full-page black slab.
                        var aspect by remember(attId) { mutableStateOf<Float?>(null) }
                        val rememberAspect: (AsyncImagePainter.State) -> Unit = { state ->
                            if (state is AsyncImagePainter.State.Success) {
                                val size = state.painter.intrinsicSize
                                if (size.width > 0f && size.height > 0f) aspect = size.width / size.height
                            }
                        }
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Box(
                            modifier = Modifier
                                .then(aspect?.let { Modifier.aspectRatio(it) } ?: Modifier.fillMaxSize())
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.Black),
                            contentAlignment = Alignment.Center,
                        ) {
                            val firstFrameShown = attId in drawnAttachments
                            if (durationMs != null && durationMs > 0L) {
                                TrimmableVideoPlayerSurface(
                                    filePath = attachment.playablePath ?: attachment.file.toString(),
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
                                    onFirstFrameRendered = { markDrawn(attId) },
                                )
                                // The surface is black (or see-through on Android) until its first decoded frame.
                                val poster = attachment.thumbnailBytes
                                if (!firstFrameShown && poster != null) {
                                    AsyncImage(
                                        imageLoader = imageLoader,
                                        model = poster,
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Fit,
                                        onState = rememberAspect,
                                    )
                                }
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
                                    onState = rememberAspect,
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
                    }
                    is AttachmentPendingFile.Gallery -> {
                        AsyncImage(
                            imageLoader = imageLoader,
                            model = attachment.image.thumbnailUri ?: attachment.image.file,
                            contentDescription = stringResource(MR.string.cd_gallery_thumbnail),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp)),
                            contentScale = ContentScale.Fit,
                            onState = markDrawnOnceLoaded(attachment.attachmentId),
                        )
                    }
                    is AttachmentPendingFile.Audio -> {
                        // not currently supported
                        LaunchedEffect(attachment.attachmentId) { markDrawn(attachment.attachmentId) }
                    }
                }
            }
            if (onDismiss != null) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    )
                ) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(MR.string.menu_back))
                }
            }

            pagerTopEndSlot()

        }

        // Inline trim bar — directly under the video player when the active
        // attachment is a video. Trim applies live as the user drags; the
        // FileVideo's trimStartMs/trimEndMs is the source-of-truth, and Send
        // ships whatever range the handles are at. Hidden until durationMs is
        // resolved (a few hundred ms after the editor opens). Keyed on presence only, so the bar keeps
        // its last video while it collapses and swaps content in place between videos.
        val trimBarFade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
        val trimBarResize = MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()
        AnimatedContent(
            targetState = activeVideo?.takeIf { onTrimChange != null && (it.durationMs ?: 0L) > 0L },
            contentKey = { it != null },
            transitionSpec = {
                fadeIn(trimBarFade) togetherWith fadeOut(trimBarFade) using SizeTransform { _, _ -> trimBarResize }
            },
        ) { video ->
        if (video != null && onTrimChange != null) {
            val attId = video.attachmentId
            val durationMs = video.durationMs ?: 0L
            val startMs = video.trimStartMs ?: 0L
            val endMs = video.trimEndMs ?: durationMs
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
                        onTrimChange(attId, rs, re)
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
        } // end AnimatedContent (trim bar)

        aboveStripSlot()

        // Attachment-strip row: thumbnails for every queued attachment with a
        // trailing "+" to add another. This row is just about managing the
        // collection of attachments — actions on the current one live below.
        AnimatedVisibility(
            visible = !collapseSecondaryChrome,
            enter = secondaryChromeEnter(),
            exit = secondaryChromeExit(),
        ) {
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
                attachments.forEach { attachment ->
                    val isSelected = activeAttachment?.attachmentId == attachment.attachmentId
                    val corner by animateDpAsState(
                        if (isSelected) 20.dp else 8.dp,
                        MaterialTheme.motionScheme.fastSpatialSpec(),
                    )
                    val thumbShape = RoundedCornerShape(corner)
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .clip(thumbShape)
                            .border(
                                width = if (isSelected) 2.dp else 0.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Unspecified,
                                shape = thumbShape,
                            )
                            .clickable {
                                scope.launch {
                                    val idx = attachments.indexOfFirst {
                                        it.attachmentId == attachment.attachmentId
                                    }
                                    if (idx >= 0) pagerState.animateScrollToPage(idx)
                                }
                            }
                    ) {
                        when (attachment) {
                            is AttachmentPendingFile.File -> {
                                val isPdf = remember(attachment.file) {
                                    resolveContentType(fileName = attachment.file.name) == "application/pdf"
                                }
                                if (isPdf) {
                                    PdfAttachmentPreview(
                                        file = attachment.file,
                                        imageLoader = imageLoader,
                                        maxWidth = 240,
                                        contentDescription = stringResource(MR.string.chat_message_pdf_preview),
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop,
                                        alignment = Alignment.TopCenter,
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(Icons.Default.UploadFile, contentDescription = stringResource(MR.string.cd_file_attachment))
                                    }
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

                        if (isSelected && onRemoveFile != null) {
                            // A 48dp target in the thumbnail's top-end corner; only the small badge is drawn.
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(48.dp)
                                    .clickable(
                                        onClickLabel = stringResource(MR.string.chat_message_remove_gallery_image),
                                        role = Role.Button,
                                    ) { onRemoveFile(attachment.attachmentId) },
                                contentAlignment = Alignment.TopEnd,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .padding(top = 5.dp, end = 5.dp)
                                        .size(22.dp)
                                        .background(MaterialTheme.colorScheme.errorContainer, CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = stringResource(MR.string.chat_message_remove_gallery_image),
                                        tint = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (onCameraClick != null) {
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
            }
            val addAction = if (isFileMode) onAddFile else onAddImage
            if (addAction != null) {
                IconButton(
                    onClick = addAction,
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
        } // end AnimatedVisibility (attachment strip)

        // Edit-tools row (Signal convention): actions on the current
        // attachment — crop (image only), download. Future tools (filters,
        // markup) would join this row.
        val currentAttachment = attachments.getOrNull(pagerState.currentPage)
        val toolsetFor = { attachment: AttachmentPendingFile? ->
            editorToolsetFor(
                current = attachment,
                canCrop = onCropImage != null,
                canDraw = onDrawImage != null,
                canSave = onSaveFile != null,
                canSetQuality = onToggleMediaQuality != null,
            )
        }
        val toolset = toolsetFor(currentAttachment)
        val toolbarFade = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
        AnimatedVisibility(
            visible = !collapseSecondaryChrome,
            enter = secondaryChromeEnter(),
            exit = secondaryChromeExit(),
        ) {
        // Reserve the toolbar's height whenever tools can appear, so paging onto an attachment
        // without tools fades the toolbar instead of growing the pager.
        val canShowToolbar = onCropImage != null || onDrawImage != null || onSaveFile != null
        Column {
        // Flows so the chips wrap under the toolbar at large font scales instead of clipping.
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .heightIn(min = if (canShowToolbar) SEND_OPTION_HEIGHT else 0.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            val sendOptions = listOfNotNull(
                SendOption.Quality.takeIf { toolset.showQuality },
            )
            // A lone download joins the send options' connected group, so the row reads as one toolbar.
            val saveJoinsGroup = sendOptions.isNotEmpty() && toolset.showSave && onlyTool(toolset)
            // Not composed when empty: FlowRow would still space a zero-width slot and push the group off the edge.
            if (!saveJoinsGroup) AnimatedContent(
                targetState = currentAttachment?.takeIf { toolset.showToolbar },
                contentKey = { it != null },
                transitionSpec = { fadeIn(toolbarFade) togetherWith fadeOut(toolbarFade) using null },
            ) { attachment ->
                if (attachment != null) {
                    val tools = toolsetFor(attachment)
                    val toolButtons = listOfNotNull(
                        if (tools.showCrop) EditorTool(Icons.Default.Crop, MR.string.crop) { onCropImage!!(attachment.attachmentId) } else null,
                        if (tools.showDraw) EditorTool(Icons.Default.Draw, MR.string.draw) { onDrawImage!!(attachment.attachmentId) } else null,
                        if (tools.showSave) EditorTool(Icons.Default.Download, MR.string.save) { onSaveFile!!(attachment) } else null,
                    )
                    // Same height and connected shapes as the send options, so the row reads as one toolbar.
                    Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                        toolButtons.forEachIndexed { index, tool ->
                            ConnectedToolButton(tool, sendOptionShapes(index, toolButtons.size))
                        }
                    }
                }
            }
            if (sendOptions.isNotEmpty()) {
                val groupSize = sendOptions.size + if (saveJoinsGroup) 1 else 0
                val offset = groupSize - sendOptions.size
                Row(
                    horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (saveJoinsGroup && currentAttachment != null) {
                        ConnectedToolButton(
                            EditorTool(Icons.Default.Download, MR.string.save) { onSaveFile!!(currentAttachment) },
                            sendOptionShapes(0, groupSize),
                        )
                    }
                    sendOptions.forEachIndexed { index, option ->
                        val shapes = sendOptionShapes(index + offset, groupSize)
                        when (option) {
                            SendOption.Quality -> MediaQualityToggle(
                                isHigh = mediaQuality == MediaQuality.HIGH,
                                onClick = { onToggleMediaQuality!!() },
                                shapes = shapes,
                            )
                        }
                    }
                }
            }
        }
        }
        } // end AnimatedVisibility (tool row)

        // max, not sum: the ime inset already spans the nav bar, so stacking them double-counts.
        Box(
            modifier = Modifier.windowInsetsPadding(
                WindowInsets.ime.union(WindowInsets.navigationBars)
            )
        ) {
            bottomBar()
        }
    }
}

private enum class SendOption { Quality }

private class EditorTool(val icon: ImageVector, val label: StringResource, val onClick: () -> Unit)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ConnectedToolButton(tool: EditorTool, shapes: ToggleButtonShapes) {
    FilledTonalIconButton(
        onClick = tool.onClick,
        shapes = IconButtonShapes(shape = shapes.shape, pressedShape = shapes.pressedShape),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        modifier = Modifier.size(SEND_OPTION_HEIGHT),
    ) {
        Icon(tool.icon, contentDescription = stringResource(tool.label))
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MediaQualityToggle(isHigh: Boolean, onClick: () -> Unit, shapes: ToggleButtonShapes) {
    ToggleButton(
        checked = isHigh,
        onCheckedChange = { onClick() },
        shapes = shapes,
        colors = sendOptionColors(),
        modifier = Modifier.heightIn(min = SEND_OPTION_HEIGHT).testTag("mediaQualityChip"),
    ) {
        Icon(
            imageVector = if (isHigh) Icons.Default.Hd else HomebaseIcons.HdOff,
            contentDescription = stringResource(
                if (isHigh) MR.string.cd_media_quality_high_on else MR.string.cd_media_quality_high_off
            ),
            modifier = Modifier.size(ButtonDefaults.IconSize),
        )
        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        Text(stringResource(MR.string.chat_media_quality_hd))
    }
}

private val SEND_OPTION_HEIGHT = 48.dp
private val CLOSE_BAND = 72.dp

private fun onlyTool(tools: EditorToolset): Boolean =
    listOf(tools.showCrop, tools.showDraw, tools.showSave).count { it } == 1

// Outer corners are full and morph to a squircle when checked; inner corners stay connected, so the pair always reads as one group.
private fun sendOptionShapes(index: Int, count: Int): ToggleButtonShapes {
    fun shape(outer: CornerSize, inner: CornerSize): RoundedCornerShape {
        val start = if (index == 0) outer else inner
        val end = if (index == count - 1) outer else inner
        return RoundedCornerShape(topStart = start, bottomStart = start, topEnd = end, bottomEnd = end)
    }
    return ToggleButtonShapes(
        shape = shape(CornerSize(50), CornerSize(8.dp)),
        pressedShape = shape(CornerSize(12.dp), CornerSize(4.dp)),
        checkedShape = shape(CornerSize(16.dp), CornerSize(8.dp)),
    )
}

@Composable
private fun sendOptionColors() = ToggleButtonDefaults.toggleButtonColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainer,
    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
    checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
)

@Composable
fun secondaryChromeEnter(): EnterTransition =
    expandVertically(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeIn(MaterialTheme.motionScheme.fastEffectsSpec())

@Composable
fun secondaryChromeExit(): ExitTransition =
    shrinkVertically(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeOut(MaterialTheme.motionScheme.fastEffectsSpec())

/**
 * First-page preview for a PDF attachment in the composer: renders page 1 to a
 * bitmap ([generatePdfThumbnail]) off the main thread and shows it. Falls back to
 * the generic file icon while rendering, or when the PDF can't be rendered
 * (e.g. web, where the renderer returns null).
 */
@Composable
private fun PdfAttachmentPreview(
    file: PlatformFile,
    imageLoader: ImageLoader,
    maxWidth: Int,
    contentDescription: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    alignment: Alignment = Alignment.Center,
) {
    val pageJpeg by produceState<ByteArray?>(initialValue = null, file, maxWidth) {
        value = withContext(Dispatchers.Default) {
            runCatching { generatePdfThumbnail(file.readBytes(), maxWidth)?.thumbnailBytes }.getOrNull()
        }
    }

    val bytes = pageJpeg
    if (bytes != null) {
        AsyncImage(
            imageLoader = imageLoader,
            model = bytes,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
            alignment = alignment,
        )
    } else {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Icon(Icons.Default.UploadFile, contentDescription = contentDescription)
        }
    }
}
