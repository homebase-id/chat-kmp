package id.homebase.chat.widget

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.DescriptorContent
import id.homebase.chat.conversationlist.FullScreenOverlay
import id.homebase.chat.services.LocalAttachmentContextStore
import id.homebase.core.image.HomebaseImage
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.media.MediaPager
import id.homebase.core.media.MediaPendingOverlay
import id.homebase.core.media.MediaRail
import id.homebase.core.media.MediaRailItem
import id.homebase.core.media.MediaUnavailablePlaceholder
import id.homebase.core.media.subsample.SubSamplingImageSource
import id.homebase.core.media.subsample.ZoomableSubSamplingImage
import id.homebase.core.util.formatTimestamp
import id.homebase.resources.MR
import id.homebase.resources.chat_image_unavailable
import id.homebase.resources.chat_options
import id.homebase.resources.menu_back
import id.homebase.resources.share
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullScreenMediaViewer(
    modifier: Modifier = Modifier,
    data: FullScreenOverlay.ViewMessageData,
    isDownloading: Boolean = false,
    // Null hides the control rather than leaving it present-but-dead.
    onShare: ((messageId: Uuid, payloadKey: String) -> Unit)? = null,
    onSave: ((messageId: Uuid, payloadKey: String) -> Unit)? = null,
    // Save a viewed sticker into the user's library. Default no-op for non-chat hosts
    // (Moments) that have no sticker library; the chat pane passes a real handler.
    onSaveSticker: (messageId: Uuid, payloadKey: String) -> Unit = { _, _ -> },
    onDelete: ((messageId: Uuid) -> Unit)? = null,
    onDismiss: () -> Unit,
    onNavigateToMessage: (() -> Unit)? = null,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    val scope = rememberCoroutineScope()
    val localAttachmentStore = koinInject<LocalAttachmentContextStore>()
    var showUI by remember { mutableStateOf(true) }
    var showMenu by remember { mutableStateOf(false) }

    val initialPage = remember(data) {
        data.payloads.indexOfFirst { it.key == data.selectedPayloadKey }.coerceAtLeast(0)
    }
    val pagerState = rememberPagerState(initialPage = initialPage) { data.payloads.size }

    LaunchedEffect(data.messageId) {
        pagerState.scrollToPage(initialPage)
    }

    val currentPayloadKey = data.payloads.getOrNull(pagerState.currentPage)?.key ?: data.selectedPayloadKey

    BoxWithConstraints(
        // Deliberate divergence from the chat bubble: the bubble renders stickers
        // transparently (no backdrop), but the full-screen viewer keeps a solid
        // surface backdrop for all media — including stickers — so a maximised
        // cut-out image sits on a clean, predictable surface rather than whatever
        // is behind the viewer.
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        val maxCaptionHeight = (maxHeight * 0.15f).coerceAtLeast(60.dp)

        MediaPager(
            pageCount = data.payloads.size,
            modifier = Modifier.fillMaxSize(),
            state = pagerState,
        ) { page ->
            val payload = data.payloads[page]
            val payloadIv = remember(payload.iv) {
                payload.iv?.let {
                    try {
                        Base64.decode(it)
                    } catch (_: Exception) {
                        null
                    }
                }
            }

            // Prefer a locally-available original (an image sent this session)
            // over a remote fetch + decrypt — same as VaultZoomableImage.
            val localContext by localAttachmentStore.observe(data.messageId, payload.key)
                .collectAsStateWithLifecycle(
                    initialValue = localAttachmentStore.get(data.messageId, payload.key),
                )

            when (
                val resolved = resolveMediaPageSource(
                    localContext,
                    payload.iv != null,
                    payloadIv,
                    data.isEncrypted,
                )
            ) {
                is MediaPageSource.LocalFile -> {
                    val source = remember(resolved.path) {
                        SubSamplingImageSource.LocalFile(filePath = resolved.path)
                    }
                    ZoomableSubSamplingImage(
                        source = source,
                        contentDescription = payload.descriptorContent,
                        onTap = { showUI = !showUI },
                        sharedTransitionScope = if (page == initialPage) sharedTransitionScope else null,
                        animatedVisibilityScope = if (page == initialPage) animatedVisibilityScope else null,
                        sharedContentStateKey = "image-${data.fileId}-${payload.key}",
                    )
                }

                is MediaPageSource.Remote -> {
                    val source = remember(data.driveId, data.fileId, payload.key, resolved.iv) {
                        val imageData = HomebaseImageData(
                            driveId = data.driveId,
                            fileId = data.fileId,
                            payloadKey = payload.key,
                            previewThumbnail = payload.previewThumbnail?.toEmbeddedThumb(),
                            loadFullPayload = true,
                            lastModified = payload.lastModified,
                            isEncrypted = resolved.iv != null,
                            keyHeader = resolved.iv
                                ?.let { KeyHeader(iv = it, aesKey = data.keyHeader.aesKey) }
                                ?: KeyHeader.empty(),
                            remoteOdinId = data.remoteOdinId,
                            globalTransitId = data.globalTransitId,
                        )
                        SubSamplingImageSource.Remote(imageData)
                    }
                    ZoomableSubSamplingImage(
                        source = source,
                        contentDescription = payload.descriptorContent,
                        onTap = { showUI = !showUI },
                        sharedTransitionScope = if (page == initialPage) sharedTransitionScope else null,
                        animatedVisibilityScope = if (page == initialPage) animatedVisibilityScope else null,
                        sharedContentStateKey = "image-${data.fileId}-${payload.key}",
                    )
                }

                MediaPageSource.Pending -> {
                    // Payload not uploaded yet (no iv): show a spinner like the
                    // vault viewer, not a failure — and keep it tappable.
                    MediaPendingOverlay(onTap = { showUI = !showUI })
                }

                MediaPageSource.Unavailable -> {
                    // iv present but undecodable (corrupt): keep the page
                    // tappable so the user can still toggle the chrome (and reach
                    // the back button) instead of a blank, unresponsive page.
                    MediaUnavailablePlaceholder(
                        message = stringResource(MR.string.chat_image_unavailable),
                        icon = Icons.Default.BrokenImage,
                        onTap = { showUI = !showUI },
                    )
                }
            }
        }

        if (isDownloading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }

        AnimatedVisibility(
            visible = showUI,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut() + slideOutVertically()
        ) {
            TopAppBar(
                modifier = Modifier.align(Alignment.TopCenter),
                title = {
                    Column {
                        Text(
                            text = data.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = formatTimestamp(data.userDate),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }

                },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(MR.string.menu_back)
                        )
                    }

                },
                actions = {
                    // Offer "Save sticker" only when the current payload is a real
                    // transparent sticker (descriptor ImageFile(isSticker = true)),
                    // never on an ordinary photo.
                    val currentIsSticker = data.payloads
                        .firstOrNull { it.key == currentPayloadKey }
                        ?.descriptorInfo()
                        ?.let { it is DescriptorContent.ImageFile && it.isSticker } == true
                    val hasMenuItems = onSave != null || onDelete != null ||
                        onNavigateToMessage != null || currentIsSticker
                    if (hasMenuItems) {
                        Box {
                            IconButton(onClick = {
                                showMenu = true
                            }) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = stringResource(MR.string.chat_options)
                                )
                            }
                            FullScreenMediaMenu(
                                showMenu = showMenu,
                                dismissMenu = { showMenu = false },
                                onSave = onSave?.let { save ->
                                    {
                                        showMenu = false
                                        save(data.messageId, currentPayloadKey)
                                    }
                                },
                                onSaveSticker = if (currentIsSticker) {
                                    {
                                        showMenu = false
                                        onSaveSticker(data.messageId, currentPayloadKey)
                                    }
                                } else null,
                                onDelete = onDelete?.let { delete ->
                                    {
                                        showMenu = false
                                        delete(data.messageId)
                                    }
                                },
                                onNavigateToMessage = onNavigateToMessage?.let {
                                    {
                                        showMenu = false
                                        it()
                                    }
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                ),
            )
        }

        AnimatedVisibility(
            visible = showUI,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it }
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
                    .padding(16.dp)
            ) {
                if (data.content.isNotBlank()) {
                    ChatMarkdown(
                        content = data.content,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = maxCaptionHeight)
                            .verticalScroll(rememberScrollState())
                    )
                }
                // Gallery rail at bottom
                val railItems = remember(data.payloads) {
                    data.payloads.map { MediaRailItem(key = it.key) }
                }
                MediaRail(
                    items = railItems,
                    selectedIndex = pagerState.currentPage,
                    onItemSelected = { index ->
                        scope.launch { pagerState.animateScrollToPage(index) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { _, index, isSelected ->
                    val payload = data.payloads[index]
                    val railIv = remember(payload.iv) {
                        payload.iv?.let {
                            try {
                                Base64.decode(it)
                            } catch (_: Exception) {
                                null
                            }
                        }
                    }
                    // Same plaintext rule as the page above: an unencrypted file has no
                    // rail IV either, and still has bytes to show.
                    if (railIv != null || !data.isEncrypted) {
                        val thumbImageData = remember(
                            data.driveId,
                            data.fileId,
                            payload.key,
                            payload.lastModified,
                            railIv,
                        ) {
                            HomebaseImageData(
                                driveId = data.driveId,
                                fileId = data.fileId,
                                payloadKey = payload.key,
                                previewThumbnail = payload.previewThumbnail?.toEmbeddedThumb(),
                                lastModified = payload.lastModified,
                                // Real payload type so a GIF rail thumbnail loads
                                // the animated original instead of a never-generated
                                // server thumbnail (its preview thumb is WebP).
                                payloadContentType = payload.contentType,
                                isEncrypted = railIv != null,
                                keyHeader = railIv
                                    ?.let { KeyHeader(iv = it, aesKey = data.keyHeader.aesKey) }
                                    ?: KeyHeader.empty(),
                                remoteOdinId = data.remoteOdinId,
                                globalTransitId = data.globalTransitId,
                            )
                        }
                        HomebaseImage(
                            imageData = thumbImageData,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(8.dp))
                                .border(
                                    width = if (isSelected) 2.dp else 0.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    shape = RoundedCornerShape(8.dp)
                                ),
                            contentScale = ContentScale.Crop,
                            contentDescription = payload.descriptorContent,
                            animatedVisibilityScope = animatedVisibilityScope,
                            sharedTransitionScope = null,
                        )
                    }
                }
                if (data.payloads.size > 1) {
                    Spacer(modifier = Modifier.height(16.dp))
                }
                if (onShare != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        IconButton(onClick = { onShare(data.messageId, currentPayloadKey) }) {
                            Icon(Icons.Default.Share, contentDescription = stringResource(MR.string.share))
                        }
                    }
                }
            }
        }
    }
}
