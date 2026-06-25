@file:OptIn(ExperimentalEncodingApi::class, ExperimentalComposeUiApi::class)

package id.homebase.core.ui.screens.vault.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.chat.services.LocalAttachmentContext
import id.homebase.chat.services.LocalAttachmentContextStore
import id.homebase.core.image.HomebaseImage
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.image.thumbSizesFrom
import id.homebase.core.ui.screens.vault.VaultEditorTool
import id.homebase.core.ui.screens.vault.components.fileTypeIcon
import id.homebase.core.ui.screens.vault.model.VaultEntry
import id.homebase.resources.MR
import id.homebase.resources.vault_edit_crop
import id.homebase.resources.vault_edit_draw
import id.homebase.resources.vault_edit_picture
import id.homebase.resources.vault_gallery_add_page
import id.homebase.resources.vault_gallery_page_counter
import id.homebase.resources.vault_label_placeholder
import id.homebase.resources.vault_notes_placeholder
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
fun VaultGalleryDetailSheet(
    file: VaultEntry,
    pages: List<PayloadDescriptor>,
    pagerState: PagerState,
    labelText: String,
    onLabelTextChange: (String) -> Unit,
    onAppendPages: () -> Unit,
    onUpdateLabel: (String?) -> Unit,
    onUpdateNotes: (String?) -> Unit,
    onEditPage: (payloadKey: String, tool: VaultEditorTool) -> Unit,
    localAttachmentStore: LocalAttachmentContextStore,
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
                    val isPdf = descriptor.contentType == "application/pdf"
                    if (isImage || isPdf) {
                        val localImage by localAttachmentStore.observe(file.uniqueId, descriptor.key)
                            .collectAsStateWithLifecycle(
                                initialValue = localAttachmentStore.get(file.uniqueId, descriptor.key),
                            )
                        val localFilePath = (localImage as? LocalAttachmentContext.Image)?.localFilePath

                        val isPending = descriptor.iv == null
                        if (localFilePath != null) {
                            AsyncImage(
                                model = localFilePath,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                contentScale = ContentScale.Crop,
                            )
                            if (isPending) {
                                Box(
                                    modifier = Modifier.fillMaxSize()
                                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.38f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.inversePrimary,
                                    )
                                }
                            }
                        } else {
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
                                    availableThumbSizes = thumbSizesFrom(descriptor.thumbnails),
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

        // Edit-picture actions — only for the current image page. Routes the
        // stored payload through the shared crop/draw editor; the result
        // replaces this page's payload in place.
        val currentDescriptor = pages.getOrNull(pagerState.currentPage)
        val currentIsImage = currentDescriptor?.contentType?.startsWith("image/") == true
        if (currentDescriptor != null && currentIsImage) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(MR.string.vault_edit_picture),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(onClick = { onEditPage(currentDescriptor.key, VaultEditorTool.Crop) }) {
                    Icon(
                        imageVector = Icons.Outlined.Crop,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(stringResource(MR.string.vault_edit_crop))
                }
                FilledTonalButton(onClick = { onEditPage(currentDescriptor.key, VaultEditorTool.Draw) }) {
                    Icon(
                        imageVector = Icons.Outlined.Draw,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(stringResource(MR.string.vault_edit_draw))
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}
