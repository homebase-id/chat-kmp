@file:OptIn(ExperimentalComposeUiApi::class)

package id.homebase.core.ui.screens.vault.gallery

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.core.media.MediaPager
import id.homebase.api.file.FileOperationsProvider
import id.homebase.core.ui.screens.vault.VaultUploaderService
import id.homebase.core.ui.screens.vault.components.VaultFileDropdownMenu
import id.homebase.core.ui.screens.vault.components.fileTypeIcon
import id.homebase.core.ui.screens.vault.model.VaultEntry
import id.homebase.core.util.CONTENT_TYPE_MARKDOWN
import id.homebase.resources.MR
import id.homebase.resources.menu_back
import id.homebase.resources.vault_delete_confirm_action
import id.homebase.resources.vault_delete_confirm_message
import id.homebase.resources.vault_delete_confirm_title
import id.homebase.resources.vault_gallery_delete_last_page_confirm
import id.homebase.resources.vault_gallery_delete_page
import id.homebase.resources.vault_gallery_delete_page_confirm
import id.homebase.resources.vault_gallery_page_counter
import id.homebase.resources.vault_gallery_save_page
import id.homebase.resources.vault_gallery_share_page
import id.homebase.resources.vault_note_edit
import id.homebase.resources.vault_permission_cancel
import id.homebase.chat.services.LocalAttachmentContextStore
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultGalleryScreen(
    file: VaultEntry,
    initialPage: Int,
    onDismiss: () -> Unit,
    onSharePage: (payloadKey: String) -> Unit,
    onSavePage: (payloadKey: String) -> Unit,
    onDeletePage: (payloadKey: String) -> Unit,
    onAppendPages: () -> Unit,
    onUpdateLabel: (String?) -> Unit,
    onUpdateNotes: (String?) -> Unit,
    onDeleteEntry: () -> Unit,
    onEditNote: () -> Unit = {},
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
) {
    val localAttachmentStore = koinInject<LocalAttachmentContextStore>()
    val uploaderService = koinInject<VaultUploaderService>()
    val fileOperationsProvider = koinInject<FileOperationsProvider>()
    val pages = file.payloadDescriptors
    if (pages.isEmpty()) return

    val pagerState = rememberPagerState(
        initialPage = initialPage.coerceIn(0, maxOf(0, pages.size - 1)),
        pageCount = { pages.size },
    )

    // When a page is deleted, keep the pager on the nearest valid page
    LaunchedEffect(pages.size) {
        if (pagerState.currentPage >= pages.size && pages.isNotEmpty()) {
            pagerState.scrollToPage(pages.size - 1)
        }
    }

    // Hoisted so the top bar title reflects live edits
    var labelText by remember(file.label) { mutableStateOf(file.label ?: "") }

    var showUI by remember { mutableStateOf(true) }
    var pageToDelete by remember { mutableStateOf<String?>(null) }
    var showDeleteEntryConfirm by remember { mutableStateOf(false) }
    val sheetPeekHeight by animateDpAsState(if (showUI) 120.dp else 0.dp)
    val scaffoldState = rememberBottomSheetScaffoldState()
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current

    // Force dark theme — gallery always displays images on a dark background
    MaterialTheme(colorScheme = darkColorScheme()) {
        LaunchedEffect(scaffoldState.bottomSheetState) {
            snapshotFlow { scaffoldState.bottomSheetState.currentValue }.collect { value ->
                if (value == SheetValue.PartiallyExpanded || value == SheetValue.Hidden) {
                    keyboardController?.hide()
                }
            }
        }

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
                VaultGalleryDetailSheet(
                    file = file,
                    pages = pages,
                    pagerState = pagerState,
                    labelText = labelText,
                    onLabelTextChange = { labelText = it },
                    onAppendPages = onAppendPages,
                    onUpdateLabel = onUpdateLabel,
                    onUpdateNotes = onUpdateNotes,
                    localAttachmentStore = localAttachmentStore,
                )
            },
            containerColor = MaterialTheme.colorScheme.scrim,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                MediaPager(
                    pageCount = pages.size,
                    state = pagerState,
                    swipeToDismiss = true,
                    swipeToDismissEnabled = scaffoldState.bottomSheetState.currentValue != SheetValue.Expanded,
                    onDismiss = onDismiss,
                ) { page ->
                    if (pages.isEmpty()) return@MediaPager
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
                        VaultZoomableImage(
                            file = file,
                            descriptor = descriptor,
                            localAttachmentStore = localAttachmentStore,
                            onToggleUI = onTapImage,
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = animatedVisibilityScope,
                        )
                    } else if (descriptor.contentType == CONTENT_TYPE_MARKDOWN) {
                        NoteViewerPage(
                            file = file,
                            uploaderService = uploaderService,
                            fileOperationsProvider = fileOperationsProvider,
                            onToggleUI = onTapImage,
                        )
                    } else if (file.isPdf) {
                        PdfViewerPage(
                            file = file,
                            uploaderService = uploaderService,
                            fileOperationsProvider = fileOperationsProvider,
                            onToggleUI = onTapImage,
                        )
                    } else if (file.isText) {
                        TextViewerPage(
                            file = file,
                            uploaderService = uploaderService,
                            fileOperationsProvider = fileOperationsProvider,
                            onToggleUI = onTapImage,
                        )
                    } else {
                        GalleryPageNonImage(
                            descriptor = descriptor,
                            onToggleUI = onTapImage,
                        )
                    }
                }

                if (pages.size > 1) {
                    AnimatedVisibility(
                        visible = !showUI,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 140.dp),
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        PageIndicatorPill(
                            currentPage = pagerState.currentPage + 1,
                            totalPages = pages.size,
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
                                    text = labelText.ifBlank { null }
                                        ?: file.noteDisplayTitle
                                        ?: file.fileName,
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
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
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
                                )
                            }
                        },
                        actions = {
                            if (currentDescriptor?.contentType == CONTENT_TYPE_MARKDOWN) {
                                IconButton(onClick = onEditNote) {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = stringResource(MR.string.vault_note_edit),
                                    )
                                }
                            }
                            if (currentDescriptor != null) {
                                IconButton(onClick = { onSharePage(currentDescriptor.key) }) {
                                    Icon(
                                        imageVector = Icons.Default.Share,
                                        contentDescription = stringResource(MR.string.vault_gallery_share_page),
                                    )
                                }
                                IconButton(onClick = { onSavePage(currentDescriptor.key) }) {
                                    Icon(
                                        imageVector = Icons.Outlined.Download,
                                        contentDescription = stringResource(MR.string.vault_gallery_save_page),
                                    )
                                }
                            }
                            VaultFileDropdownMenu(
                                file = file,
                                onShare = { currentDescriptor?.let { onSharePage(it.key) } },
                                onDelete = { showDeleteEntryConfirm = true },
                                onDeletePage = { currentDescriptor?.let { pageToDelete = it.key } },
                                iconTint = MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                        ),
                    )
                }
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

    if (showDeleteEntryConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteEntryConfirm = false },
            title = {
                Text(stringResource(MR.string.vault_delete_confirm_title))
            },
            text = {
                Text(stringResource(MR.string.vault_delete_confirm_message, file.label ?: file.fileName))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteEntryConfirm = false
                        onDeleteEntry()
                    },
                ) {
                    Text(
                        text = stringResource(MR.string.vault_delete_confirm_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteEntryConfirm = false }) {
                    Text(stringResource(MR.string.vault_permission_cancel))
                }
            },
        )
    }
}

@Composable
private fun PageIndicatorPill(
    currentPage: Int,
    totalPages: Int,
) {
    Box(
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                shape = RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = stringResource(MR.string.vault_gallery_page_counter, currentPage, totalPages),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
        )
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
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(24.dp),
                ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = fileTypeIcon(descriptor.contentType ?: ""),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(48.dp),
                )
            }
            descriptor.contentType?.let { ct ->
                Text(
                    text = ct,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
        }
    }
}
