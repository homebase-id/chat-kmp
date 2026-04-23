@file:OptIn(ExperimentalEncodingApi::class)

package id.homebase.core.ui.screens.vault

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.core.image.HomebaseImage
import id.homebase.core.image.HomebaseImageData
import id.homebase.resources.MR
import id.homebase.resources.menu_back
import id.homebase.resources.vault_delete_confirm_action
import id.homebase.resources.vault_gallery_add_page
import id.homebase.resources.vault_gallery_delete_last_page_confirm
import id.homebase.resources.vault_gallery_delete_page
import id.homebase.resources.vault_gallery_delete_page_confirm
import id.homebase.resources.vault_gallery_page_counter
import id.homebase.resources.vault_gallery_share_page
import id.homebase.resources.vault_notes_placeholder
import id.homebase.resources.vault_notes_save
import id.homebase.resources.vault_permission_cancel
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultGalleryOverlay(
    file: VaultFileItem,
    initialPage: Int,
    onDismiss: () -> Unit,
    onSharePage: (payloadKey: String) -> Unit,
    onDeletePage: (payloadKey: String) -> Unit,
    onAppendPages: () -> Unit,
    onUpdateNotes: (String?) -> Unit,
    onDeleteEntry: () -> Unit,
    onRenameEntry: () -> Unit,
) {
    val pages = file.payloadDescriptors
    if (pages.isEmpty()) return

    val scope = rememberCoroutineScope()

    val pagerState = rememberPagerState(
        initialPage = initialPage.coerceIn(0, maxOf(0, pages.size - 1)),
        pageCount = { pages.size },
    )
    val thumbnailListState = rememberLazyListState()

    var showUI by remember { mutableStateOf(true) }
    var pageToDelete by remember { mutableStateOf<String?>(null) }
    var editingNotes by remember { mutableStateOf(false) }
    var notesText by remember(file.notes) { mutableStateOf(file.notes ?: "") }

    // Auto-scroll thumbnail strip to follow pager
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            thumbnailListState.animateScrollToItem(page)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // Main pager area
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
        ) { page ->
            if (pages.isEmpty()) return@HorizontalPager
            val descriptor = pages[page]
            val isImage = descriptor.contentType?.startsWith("image/") == true

            if (isImage) {
                GalleryPageImage(
                    file = file,
                    descriptor = descriptor,
                    onToggleUI = { showUI = !showUI },
                )
            } else {
                GalleryPageNonImage(
                    descriptor = descriptor,
                    onToggleUI = { showUI = !showUI },
                )
            }
        }

        // Top bar
        AnimatedVisibility(
            visible = showUI,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
        ) {
            val currentPage = pagerState.currentPage
            val currentDescriptor = pages.getOrNull(currentPage)

            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = file.fileName,
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (pages.size > 1) {
                            Text(
                                text = stringResource(
                                    MR.string.vault_gallery_page_counter,
                                    currentPage + 1,
                                    pages.size,
                                ),
                                color = Color.White.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(MR.string.menu_back),
                            tint = Color.White,
                        )
                    }
                },
                actions = {
                    if (currentDescriptor != null) {
                        IconButton(onClick = { onSharePage(currentDescriptor.key) }) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = stringResource(MR.string.vault_gallery_share_page),
                                tint = Color.White,
                            )
                        }
                        IconButton(onClick = { pageToDelete = currentDescriptor.key }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = stringResource(MR.string.vault_gallery_delete_page),
                                tint = Color.White,
                            )
                        }
                    }
                    VaultFileDropdownMenu(
                        file = file,
                        onRename = { onRenameEntry() },
                        onShare = { currentDescriptor?.let { onSharePage(it.key) } },
                        onDelete = { onDeleteEntry() },
                        iconTint = Color.White,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.6f),
                ),
            )
        }

        // Bottom panel: thumbnail strip + metadata
        AnimatedVisibility(
            visible = showUI,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(bottom = 16.dp),
            ) {
                // Thumbnail strip
                LazyRow(
                    state = thumbnailListState,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    itemsIndexed(pages) { index, descriptor ->
                        val isSelected = pagerState.currentPage == index
                        val isImage = descriptor.contentType?.startsWith("image/") == true
                        val borderModifier = if (isSelected) {
                            Modifier.border(
                                width = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(6.dp),
                            )
                        } else {
                            Modifier
                        }

                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(
                                    color = Color.White.copy(alpha = 0.1f),
                                    shape = RoundedCornerShape(6.dp),
                                )
                                .then(borderModifier)
                                .clickable {
                                    scope.launch { pagerState.animateScrollToPage(index) }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isImage) {
                                val thumbKeyHeader = descriptor.iv?.let { ivBase64 ->
                                    try {
                                        KeyHeader(Base64.decode(ivBase64), file.keyHeader.aesKey)
                                    } catch (_: Exception) {
                                        file.keyHeader
                                    }
                                } ?: file.keyHeader

                                HomebaseImage(
                                    imageData = HomebaseImageData(
                                        driveId = file.driveId,
                                        fileId = file.fileId,
                                        payloadKey = descriptor.key,
                                        previewThumbnail = descriptor.previewThumbnail?.let {
                                            file.previewThumbnail
                                        } ?: file.previewThumbnail,
                                        isEncrypted = file.isEncrypted,
                                        keyHeader = thumbKeyHeader,
                                    ),
                                    modifier = Modifier
                                        .size(48.dp)
                                        .background(
                                            Color.Transparent,
                                            RoundedCornerShape(6.dp),
                                        ),
                                    contentScale = ContentScale.Crop,
                                    contentDescription = null,
                                )
                            } else {
                                Icon(
                                    imageVector = fileTypeIcon(descriptor.contentType ?: ""),
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }
                    }

                    // [+] append button
                    item {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .border(
                                    width = 1.dp,
                                    color = Color.White.copy(alpha = 0.3f),
                                    shape = RoundedCornerShape(6.dp),
                                )
                                .clickable { onAppendPages() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = stringResource(MR.string.vault_gallery_add_page),
                                tint = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                }

                // Metadata info bar
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    Text(
                        text = file.fileName,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = formatFileInfo(file.sizeBytes, file.createdAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.5f),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    if (editingNotes) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = notesText,
                                onValueChange = { notesText = it },
                                placeholder = {
                                    Text(
                                        stringResource(MR.string.vault_notes_placeholder),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                },
                                textStyle = MaterialTheme.typography.bodySmall.copy(
                                    color = Color.White,
                                ),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                                    cursorColor = Color.White,
                                ),
                                modifier = Modifier.weight(1f),
                                maxLines = 3,
                            )
                            IconButton(
                                onClick = {
                                    val notes = notesText.ifBlank { null }
                                    onUpdateNotes(notes)
                                    editingNotes = false
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = stringResource(MR.string.vault_notes_save),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            IconButton(
                                onClick = {
                                    notesText = file.notes ?: ""
                                    editingNotes = false
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.5f),
                                )
                            }
                        }
                    } else {
                        Text(
                            text = file.notes?.ifBlank { null }
                                ?: stringResource(MR.string.vault_notes_placeholder),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (file.notes.isNullOrBlank()) {
                                Color.White.copy(alpha = 0.3f)
                            } else {
                                Color.White.copy(alpha = 0.7f)
                            },
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { editingNotes = true },
                        )
                    }
                }
            }
        }
    }

    // Delete page confirmation dialog
    pageToDelete?.let { payloadKey ->
        val isLastPage = pages.size <= 1
        val confirmMessage = if (isLastPage) {
            stringResource(MR.string.vault_gallery_delete_last_page_confirm)
        } else {
            stringResource(MR.string.vault_gallery_delete_page_confirm)
        }

        AlertDialog(
            onDismissRequest = { pageToDelete = null },
            title = {
                Text(stringResource(MR.string.vault_gallery_delete_page))
            },
            text = {
                Text(confirmMessage)
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pageToDelete = null
                        onDeletePage(payloadKey)
                    },
                ) {
                    Text(
                        text = stringResource(MR.string.vault_delete_confirm_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pageToDelete = null }) {
                    Text(stringResource(MR.string.vault_permission_cancel))
                }
            },
        )
    }
}

@Composable
private fun GalleryPageImage(
    file: VaultFileItem,
    descriptor: PayloadDescriptor,
    onToggleUI: () -> Unit,
) {
    var scale by remember(descriptor.key) { mutableStateOf(1f) }
    var offset by remember(descriptor.key) { mutableStateOf(Offset.Zero) }

    // Resolve per-page key header from descriptor IV
    val pageKeyHeader = remember(descriptor.iv, file.keyHeader) {
        descriptor.iv?.let { ivBase64 ->
            try {
                KeyHeader(Base64.decode(ivBase64), file.keyHeader.aesKey)
            } catch (_: Exception) {
                file.keyHeader
            }
        } ?: file.keyHeader
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val viewportWidth = constraints.maxWidth.toFloat()
        val viewportHeight = constraints.maxHeight.toFloat()

        val transformState = rememberTransformableState { zoomChange, offsetChange, _ ->
            scale = (scale * zoomChange).coerceIn(1f, 5f)
            if (scale > 1f) {
                val velocityFactor = 2f
                val newOffset = offset + (offsetChange * velocityFactor)
                val maxOffsetX = (viewportWidth * scale - viewportWidth) / 2f
                val maxOffsetY = (viewportHeight * scale - viewportHeight) / 2f
                offset = Offset(
                    x = newOffset.x.coerceIn(-maxOffsetX, maxOffsetX),
                    y = newOffset.y.coerceIn(-maxOffsetY, maxOffsetY),
                )
            } else {
                offset = Offset.Zero
            }
        }

        HomebaseImage(
            imageData = HomebaseImageData(
                driveId = file.driveId,
                fileId = file.fileId,
                payloadKey = descriptor.key,
                previewThumbnail = file.previewThumbnail,
                loadFullPayload = true,
                isEncrypted = file.isEncrypted,
                keyHeader = pageKeyHeader,
            ),
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y,
                )
                .transformable(state = transformState)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            scale = if (scale > 1f) 1f else 2f
                            if (scale == 1f) offset = Offset.Zero
                        },
                        onTap = { onToggleUI() },
                    )
                },
            contentScale = ContentScale.Fit,
            contentDescription = file.fileName,
        )
    }
}

@Composable
private fun GalleryPageNonImage(
    descriptor: PayloadDescriptor,
    onToggleUI: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onToggleUI() })
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .background(
                        color = Color.White.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(24.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = fileTypeIcon(descriptor.contentType ?: ""),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(48.dp),
                )
            }
            descriptor.contentType?.let { ct ->
                Text(
                    text = ct,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
        }
    }
}
