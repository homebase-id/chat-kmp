@file:OptIn(ExperimentalEncodingApi::class, ExperimentalComposeUiApi::class)

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
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
import id.homebase.resources.vault_label_placeholder
import id.homebase.resources.vault_notes_placeholder
import id.homebase.resources.vault_error_image_unavailable
import id.homebase.resources.vault_permission_cancel
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

//TODO(2002Bishwajeet): Lets find a way to modularise it with the chat gallery
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultGalleryOverlay(
    file: VaultFileItem,
    initialPage: Int,
    onDismiss: () -> Unit,
    onSharePage: (payloadKey: String) -> Unit,
    onDeletePage: (payloadKey: String) -> Unit,
    onAppendPages: () -> Unit,
    onUpdateLabel: (String?) -> Unit,
    onUpdateNotes: (String?) -> Unit,
    onDeleteEntry: () -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
) {
    val pages = file.payloadDescriptors
    if (pages.isEmpty()) return

    val pagerState = rememberPagerState(
        initialPage = initialPage.coerceIn(0, maxOf(0, pages.size - 1)),
        pageCount = { pages.size },
    )

    // Hoisted so the top bar title reflects live edits
    var labelText by remember(file.label) { mutableStateOf(file.label ?: "") }

    var showUI by remember { mutableStateOf(true) }
    var pageToDelete by remember { mutableStateOf<String?>(null) }
    val sheetPeekHeight by animateDpAsState(if (showUI) 120.dp else 0.dp)
    val scaffoldState = rememberBottomSheetScaffoldState()
    val scope = rememberCoroutineScope()

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = sheetPeekHeight,
        sheetShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        sheetContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        sheetContentColor = MaterialTheme.colorScheme.onSurface,
        sheetShadowElevation = 8.dp,
        sheetDragHandle = {
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier.size(width = 36.dp, height = 4.dp).background(
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(2.dp),
                    ),
                )
            }
        },
        sheetContent = {
            GalleryDetailSheet(
                file = file,
                pages = pages,
                pagerState = pagerState,
                labelText = labelText,
                onLabelTextChange = { labelText = it },
                onAppendPages = onAppendPages,
                onUpdateLabel = onUpdateLabel,
                onUpdateNotes = onUpdateNotes,
            )
        },
        containerColor = Color.Black,
        contentColor = Color.White,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
            ) { page ->
                if (pages.isEmpty()) return@HorizontalPager
                val descriptor = pages[page]
                val isImage = descriptor.contentType?.startsWith("image/") == true

                val onTapImage: () -> Unit = {
                    val sheetState = scaffoldState.bottomSheetState
                    if (sheetState.currentValue == SheetValue.Expanded) {
                        scope.launch { sheetState.partialExpand() }
                    } else {
                        showUI = !showUI
                    }
                }

                if (isImage) {
                    GalleryPageImage(
                        file = file,
                        descriptor = descriptor,
                        onToggleUI = onTapImage,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                    )
                } else {
                    GalleryPageNonImage(
                        descriptor = descriptor,
                        onToggleUI = onTapImage,
                    )
                }
            }

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
                                text = labelText.ifBlank { null } ?: file.fileName,
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
        }
    }

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
private fun GalleryDetailSheet(
    file: VaultFileItem,
    pages: List<PayloadDescriptor>,
    pagerState: PagerState,
    labelText: String,
    onLabelTextChange: (String) -> Unit,
    onAppendPages: () -> Unit,
    onUpdateLabel: (String?) -> Unit,
    onUpdateNotes: (String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val thumbnailListState = rememberLazyListState()
    val keyboardController = LocalSoftwareKeyboardController.current
    var notesText by remember(file.notes) { mutableStateOf(file.notes ?: "") }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            thumbnailListState.animateScrollToItem(page)
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()),
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

                val pageLabel = stringResource(
                    MR.string.vault_gallery_page_counter,
                    index + 1,
                    pages.size,
                )
                Box(
                    modifier = Modifier.size(48.dp).background(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = RoundedCornerShape(6.dp),
                    ).then(borderModifier).clickable(onClickLabel = pageLabel) {
                        scope.launch { pagerState.animateScrollToPage(index) }
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    if (isImage) {
                        val thumbImageData = remember(file.fileId, descriptor.key, descriptor.lastModified) {
                            val payloadIv = descriptor.iv?.let {
                                try {
                                    Base64.decode(it)
                                } catch (_: Exception) {
                                    null
                                }
                            } ?: return@remember null
                            HomebaseImageData(
                                driveId = file.driveId,
                                fileId = file.fileId,
                                payloadKey = descriptor.key,
                                previewThumbnail = file.previewThumbnail,
                                lastModified = descriptor.lastModified,
                                isEncrypted = file.isEncrypted,
                                keyHeader = KeyHeader(
                                    iv = payloadIv, aesKey = file.keyHeader.aesKey
                                ),
                            )
                        }
                        if (thumbImageData != null) {
                            HomebaseImage(
                                imageData = thumbImageData,
                                modifier = Modifier.size(48.dp)
                                    .background(Color.Transparent, RoundedCornerShape(6.dp)),
                                contentScale = ContentScale.Crop,
                                contentDescription = null,
                            )
                        }
                    } else {
                        Icon(
                            imageVector = fileTypeIcon(descriptor.contentType ?: ""),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }

            item {
                Box(
                    modifier = Modifier.size(48.dp).border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(6.dp),
                    ).clickable { onAppendPages() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(MR.string.vault_gallery_add_page),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }

        // Label field — always editable, saves only when value changed
        val labelPlaceholder = stringResource(MR.string.vault_label_placeholder)
        val originalLabel = remember(file.uniqueId) { file.label ?: "" }
        var labelHasFocused by remember { mutableStateOf(false) }
        BasicTextField(
            value = labelText,
            onValueChange = onLabelTextChange,
            textStyle = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    keyboardController?.hide()
                    if (labelText != originalLabel) {
                        onUpdateLabel(labelText.ifBlank { null })
                    }
                },
            ),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .padding(horizontal = 16.dp)
                .onFocusChanged { focusState ->
                    if (focusState.isFocused) {
                        labelHasFocused = true
                    } else if (labelHasFocused && labelText != originalLabel) {
                        onUpdateLabel(labelText.ifBlank { null })
                    }
                },
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (labelText.isEmpty()) {
                        Text(
                            text = labelPlaceholder,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                            ),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        )
                    }
                    innerTextField()
                }
            },
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Description field — always editable, saves only when value changed
        val notesPlaceholder = stringResource(MR.string.vault_notes_placeholder)
        val originalNotes = remember(file.uniqueId) { file.notes ?: "" }
        var notesHasFocused by remember { mutableStateOf(false) }
        BasicTextField(
            value = notesText,
            onValueChange = { notesText = it },
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            maxLines = 4,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .padding(horizontal = 16.dp)
                .onFocusChanged { focusState ->
                    if (focusState.isFocused) {
                        notesHasFocused = true
                    } else if (notesHasFocused && notesText != originalNotes) {
                        onUpdateNotes(notesText.ifBlank { null })
                    }
                },
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (notesText.isEmpty()) {
                        Text(
                            text = notesPlaceholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                        )
                    }
                    innerTextField()
                }
            },
        )

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun GalleryPageImage(
    file: VaultFileItem,
    descriptor: PayloadDescriptor,
    onToggleUI: () -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
) {
    var scale by remember(descriptor.key) { mutableStateOf(1f) }
    var offset by remember(descriptor.key) { mutableStateOf(Offset.Zero) }

    val pageImageData = remember(file.fileId, descriptor.key, descriptor.iv, descriptor.lastModified) {
        val payloadIv = descriptor.iv?.let {
            try {
                Base64.decode(it)
            } catch (_: Exception) {
                null
            }
        } ?: return@remember null
        HomebaseImageData(
            driveId = file.driveId,
            fileId = file.fileId,
            payloadKey = descriptor.key,
            previewThumbnail = file.previewThumbnail,
            loadFullPayload = true,
            lastModified = descriptor.lastModified,
            isEncrypted = file.isEncrypted,
            keyHeader = KeyHeader(iv = payloadIv, aesKey = file.keyHeader.aesKey),
        )
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

        if (pageImageData != null) {
            HomebaseImage(
                imageData = pageImageData,
                modifier = Modifier.fillMaxSize().graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y,
                ).transformable(state = transformState).pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            scale = if (scale > 1f) 1f else 2f
                            if (scale == 1f) offset = Offset.Zero
                        },
                        onTap = { onToggleUI() },
                    )
                },
                contentScale = ContentScale.Fit,
                contentDescription = file.label?.ifBlank { null } ?: file.fileName,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTapGestures(onTap = { onToggleUI() })
                },
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = fileTypeIcon(descriptor.contentType ?: ""),
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(64.dp),
                    )
                    Text(
                        text = stringResource(MR.string.vault_error_image_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.5f),
                    )
                }
            }
        }
    }
}

@Composable
private fun GalleryPageNonImage(
    descriptor: PayloadDescriptor,
    onToggleUI: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures(onTap = { onToggleUI() })
        },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier.size(96.dp).background(
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
