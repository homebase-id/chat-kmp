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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichText
import id.homebase.api.client.KeyHeader
import id.homebase.chat.conversationlist.FullScreenOverlay
import id.homebase.core.image.HomebaseImage
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.util.applyDefaultStyling
import id.homebase.core.util.formatTimestamp
import id.homebase.resources.MR
import id.homebase.resources.chat_options
import id.homebase.resources.menu_back
import id.homebase.resources.share
import org.jetbrains.compose.resources.stringResource
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

@OptIn(ExperimentalMaterial3Api::class, ExperimentalRichTextApi::class)
@Composable
fun FullScreenMediaViewer(
    modifier: Modifier = Modifier,
    data: FullScreenOverlay.ViewMessageData,
    isDownloading: Boolean = false,
    onShare: (messageId: Uuid, payloadKey: String) -> Unit,
    onSave: (messageId: Uuid, payloadKey: String) -> Unit,
    onDelete: (messageId: Uuid) -> Unit,
    onDismiss: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    var selectedKey by remember(data) { mutableStateOf(data.selectedPayloadKey) }
    var showUI by remember { mutableStateOf(true) }
    var showMenu by remember { mutableStateOf(false) }

    // Store zoom state per payload
    val zoomStates = remember(data) {
        mutableMapOf<String, Pair<Float, Offset>>().apply {
            data.payloads.forEach { payload ->
                put(payload.key, 1f to Offset.Zero)
            }
        }
    }

    // Derive selected payload without causing recomposition
    val selectedPayload = remember(data, selectedKey) {
        data.payloads.firstOrNull { it.key == selectedKey }
    }

    val selectedPayloadIv = remember(selectedPayload) {
        selectedPayload?.iv?.let { Base64.decode(it) }
    }

    var scale by remember(selectedKey) {
        mutableStateOf(zoomStates[selectedKey]?.first ?: 1f)
    }
    var offset by remember(selectedKey) {
        mutableStateOf(zoomStates[selectedKey]?.second ?: Offset.Zero)
    }

    // See ConversationItem.kt ConversationMessagePreview for why remember is required here
    val textState = remember(data.content) {
        RichTextState().applyDefaultStyling().also { it.setMarkdown(data.content) }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        val viewportWidth = constraints.maxWidth.toFloat()
        val viewportHeight = constraints.maxHeight.toFloat()

        val state = rememberTransformableState { zoomChange, offsetChange, _ ->
            var currentScale = scale
            var currentOffset = offset

            currentScale = (currentScale * zoomChange).coerceIn(1f, 5f)

            if (currentScale > 1f) {
                val velocityFactor = 2f
                val newOffset = currentOffset + (offsetChange * velocityFactor)

                val maxOffsetX = (viewportWidth * currentScale - viewportWidth) / 2f
                val maxOffsetY = (viewportHeight * currentScale - viewportHeight) / 2f

                currentOffset = Offset(
                    x = newOffset.x.coerceIn(-maxOffsetX, maxOffsetX),
                    y = newOffset.y.coerceIn(-maxOffsetY, maxOffsetY)
                )
            } else {
                currentOffset = Offset.Zero
            }

            scale = currentScale
            offset = currentOffset
            zoomStates[selectedKey] = currentScale to currentOffset
        }

        selectedPayload?.let { payload ->
            val imageData = remember(data.driveId, data.fileId, selectedKey, selectedPayloadIv) {
                HomebaseImageData(
                    driveId = data.driveId,
                    fileId = data.fileId,
                    payloadKey = selectedKey,
                    previewThumbnail = payload.previewThumbnail?.toEmbeddedThumb(),
                    loadFullPayload = true,
                    lastModified = payload.lastModified,
                    keyHeader = KeyHeader(
                        iv = selectedPayloadIv!!,
                        aesKey = data.keyHeader.aesKey
                    )
                )
            }
            HomebaseImage(
                imageData = imageData,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    )
                    .transformable(state = state)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                scale = if (scale > 1f) 1f else 2f
                                if (scale == 1f) offset = Offset.Zero
                                zoomStates[selectedKey] = scale to offset
                            },
                            onTap = {
                                showUI = !showUI
                            }
                        )
                    },
                contentScale = ContentScale.Fit,
                contentDescription = payload.descriptorContent,
                animatedVisibilityScope = animatedVisibilityScope,
                sharedTransitionScope = sharedTransitionScope,
            )
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
                            onSave = {
                                showMenu = false
                                onSave(data.messageId, data.selectedPayloadKey)
                            },
                            onDelete = {
                                showMenu = false
                                onDelete(data.messageId)
                            }
                        )
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
                    RichText(
                        state = textState,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(16.dp)
                    )
                }
                // Gallery row at bottom
                if (data.payloads.size > 1) {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(
                            16.dp,
                            Alignment.CenterHorizontally
                        )
                    ) {
                        items(data.payloads) { payload ->
                            payload.iv?.let { iv ->
                                val thumbImageData = remember(
                                    data.driveId,
                                    data.fileId,
                                    payload.key,
                                    payload.lastModified
                                ) {
                                    val payloadIv = Base64.decode(iv)
                                    HomebaseImageData(
                                        driveId = data.driveId,
                                        fileId = data.fileId,
                                        payloadKey = payload.key,
                                        previewThumbnail = payload.previewThumbnail?.toEmbeddedThumb(),
                                        lastModified = payload.lastModified,
                                        keyHeader = KeyHeader(
                                            iv = payloadIv,
                                            aesKey = data.keyHeader.aesKey
                                        )
                                    )
                                }
                                HomebaseImage(
                                    imageData = thumbImageData,
                                    modifier = Modifier
                                        .size(60.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .border(
                                            width = if (payload.key == selectedKey) 2.dp else 0.dp,
                                            color = if (payload.key == selectedKey) Color.White else Color.Unspecified,
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .clickable {
                                            selectedKey = payload.key
                                        },
                                    contentScale = ContentScale.Crop,
                                    contentDescription = payload.descriptorContent,
                                    animatedVisibilityScope = animatedVisibilityScope,
                                    sharedTransitionScope = null,
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
                Row(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    IconButton(onClick = { onShare(data.messageId, data.selectedPayloadKey) }) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(MR.string.share))
                    }
                }
            }
        }
    }
}