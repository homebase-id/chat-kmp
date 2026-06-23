package id.homebase.chat.widget

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import id.homebase.api.isIos
import id.homebase.core.gallery.GalleryCache
import id.homebase.core.gallery.GalleryImage
import id.homebase.core.gallery.rememberGalleryPermissionState
import id.homebase.core.ui.theme.Dimens
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.core.util.isMobile
import id.homebase.core.util.noRippleClickable
import id.homebase.resources.MR
import id.homebase.resources.chat_message_attachment_file
import id.homebase.resources.chat_message_attachment_gallery
import id.homebase.resources.chat_dice_share
import id.homebase.resources.chat_event_share
import id.homebase.resources.chat_groodle_share
import id.homebase.resources.chat_poll_share
import id.homebase.resources.chat_location_share
import id.homebase.resources.chat_message_needs_gallery_permission
import id.homebase.resources.chat_message_needs_gallery_permission_button_text
import id.homebase.resources.chat_no_gallery_items
import id.homebase.resources.cd_gallery_thumbnail
import id.homebase.resources.cd_not_selected
import id.homebase.resources.cd_play_video
import id.homebase.resources.chat_gallery_selection_index
import id.homebase.resources.chat_gallery_next_count
import id.homebase.resources.chat_select_more_photos
import id.homebase.resources.go_to_settings
import id.homebase.resources.manage
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentOptionsDisplay(
    modifier: Modifier = Modifier,
    visible: Boolean,
    content: @Composable ColumnScope.() -> Unit
) {
    AnimatedVisibility(visible,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it })
    ) {
        val listState = rememberScrollState()
        Column(
            modifier = modifier
                .verticalScroll(state = listState)
        ) {
            content()
        }
    }
}

// Mirror Signal: cap a single multi-select batch so we never stage more than the
// downstream editor / sender is built to handle in one go.
private const val MaxGallerySelection = 32

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AttachmentGallery(
    onImagesSelected: (List<GalleryImage>) -> Unit,
) {
    if (isMobile()) {
        // Get ImageLoader with HomebaseImageFetcher from Koin DI
        val imageLoader: ImageLoader = koinInject()
        val galleryCache = koinInject<GalleryCache>()
        val galleryItems by galleryCache.items.collectAsStateWithLifecycle()
        val galleryPermissionState = rememberGalleryPermissionState(
            onGalleryPermissionGranted = {
                // Permission flipped on — kick the cache to refresh now that we can read.
                galleryCache.refresh()
            }
        )

        // Multi-select state. selectedIds is an ORDERED list keyed on GalleryImage.id —
        // its position drives the numbered send-order badge (computed at render, never
        // stored). multiSelect distinguishes the fast single-tap path (tap attaches now)
        // from the batch path (long-press enters multi-select; tap then toggles).
        var multiSelect by remember { mutableStateOf(false) }
        var selectedIds by remember { mutableStateOf(emptyList<String>()) }

        fun resetSelection() {
            multiSelect = false
            selectedIds = emptyList()
        }

        if (!galleryPermissionState.hasGalleryPermission && !galleryPermissionState.hasPartialGalleryPermission) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(MR.string.chat_message_needs_gallery_permission),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))
                ElevatedButton(onClick = { galleryPermissionState.requestGalleryPermission() }) {
                    Text(stringResource(MR.string.chat_message_needs_gallery_permission_button_text))
                }
            }
        } else {
            // Gallery strip and confirm control stacked in a Column so the Send-(N)
            // pill sits in its own row BELOW the 160dp thumbnail strip and never
            // overlaps (occludes) the leading thumbnails.
            Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
            ) {
                if (galleryItems.isEmpty()) {
                    Text(stringResource(MR.string.chat_no_gallery_items), modifier = Modifier.align(Alignment.Center))
                } else {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {

                        items(galleryItems, key = { it.id }) { galleryImage ->
                            val isSelected = galleryImage.id in selectedIds
                            // Send-order number, computed at render from list position — never stored.
                            val selectionNumber = selectedIds.indexOf(galleryImage.id) + 1
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(160.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .then(
                                        if (isSelected) {
                                            Modifier.border(
                                                width = 2.dp,
                                                color = MaterialTheme.colorScheme.primary,
                                                shape = RoundedCornerShape(8.dp)
                                            )
                                        } else {
                                            Modifier
                                        }
                                    )
                                    .combinedClickable(
                                        onClick = {
                                            if (multiSelect) {
                                                // In multi-select, tap toggles membership. Gate adds
                                                // at the max so a batch never exceeds the cap.
                                                selectedIds = if (isSelected) {
                                                    selectedIds - galleryImage.id
                                                } else if (selectedIds.size < MaxGallerySelection) {
                                                    selectedIds + galleryImage.id
                                                } else {
                                                    selectedIds
                                                }
                                            } else {
                                                // Fast path: first tap attaches immediately.
                                                onImagesSelected(listOf(galleryImage))
                                            }
                                        },
                                        onLongClick = {
                                            // Long-press enters multi-select and selects the
                                            // long-pressed item first.
                                            if (!multiSelect) {
                                                multiSelect = true
                                                selectedIds = listOf(galleryImage.id)
                                            }
                                        }
                                    )
                            ) {
                                AsyncImage(
                                    imageLoader = imageLoader,
                                    // Prefer the platform thumbnail URI (content://… on Android,
                                    // ph://… on iOS) over the PlatformFile itself so videos in
                                    // the gallery grid render their poster via the system
                                    // thumbnail path (ContentResolver / PHImageManager) instead
                                    // of routing through PlatformFileFetcher, which would
                                    // readBytes() the whole video and paint a black frame.
                                    // Mirrors FullScreenAttachmentEditor.kt:266,439.
                                    model = galleryImage.thumbnailUri ?: galleryImage.file,
                                    contentDescription = stringResource(MR.string.cd_gallery_thumbnail),
                                    modifier = Modifier
                                        .size(160.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop
                                )
                                if (galleryImage.isVideo()) {
                                    Icon(
                                        Icons.Default.PlayCircle,
                                        contentDescription = stringResource(MR.string.cd_play_video),
                                        tint = Color.White.copy(alpha = 0.85f)
                                    )
                                    val duration = galleryImage.durationMs
                                    if (duration != null && duration > 0) {
                                        Text(
                                            text = formatDuration(duration),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.White,
                                            modifier = Modifier
                                                .align(Alignment.BottomEnd)
                                                .padding(6.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(Color.Black.copy(alpha = 0.55f))
                                                .padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                                if (multiSelect) {
                                    SelectionBadge(
                                        isSelected = isSelected,
                                        selectionNumber = selectionNumber,
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(6.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                if (isIos() || (!galleryPermissionState.hasGalleryPermission && galleryPermissionState.hasPartialGalleryPermission)) {
                    var showMenu by remember { mutableStateOf(false) }
                    Box(modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
                        ElevatedButton(

                            onClick = { showMenu = true },
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Settings, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(MR.string.manage))

                            }
                        }

                        if (showMenu) {
                            DropdownMenu(
                                shape = RoundedCornerShape(Dimens.Message.cornerRadius),
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                            ) {
                                DropdownMenuItem(
                                    leadingIcon = {
                                        Icon(Icons.Default.Image, contentDescription = null)
                                    },
                                    text = { Text(stringResource(MR.string.chat_select_more_photos)) },
                                    onClick = {
                                        showMenu = false
                                        galleryPermissionState.requestPartialGalleryPermission()
                                    }
                                )
                                DropdownMenuItem(
                                    leadingIcon = {
                                        Icon(Icons.Default.Settings, contentDescription = null)
                                    },
                                    text = { Text(stringResource(MR.string.go_to_settings)) },
                                    onClick = {
                                        showMenu = false
                                        galleryPermissionState.launchSettings()
                                    }
                                )
                            }
                        }
                    }
                }
            }
            // Confirm control: while in multi-select with a non-empty ordered selection,
            // resolve selectedIds to items (associate by id, ordered by selectedIds) and
            // hand the batch to the existing send path, then reset. Anchored in its own
            // row BELOW the 160dp strip so it never covers the thumbnails.
            if (multiSelect && selectedIds.isNotEmpty()) {
                val byId = galleryItems.associateBy { it.id }
                val sendCount = selectedIds.size
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    // "Next", not "Send": tapping opens the full-screen attachment editor.
                    // Reuse the "+" button's blue so it reads as the primary action.
                    Button(
                        onClick = {
                            val ordered = selectedIds.mapNotNull { byId[it] }
                            resetSelection()
                            if (ordered.isNotEmpty()) {
                                onImagesSelected(ordered)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = HomebaseTheme.extendedColors.bubbleSentSurface,
                            contentColor = HomebaseTheme.extendedColors.bubbleSentOnSurface
                        )
                    ) {
                        Text(stringResource(MR.string.chat_gallery_next_count, sendCount))
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null
                        )
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun SelectionBadge(
    isSelected: Boolean,
    selectionNumber: Int,
    modifier: Modifier = Modifier,
) {
    if (isSelected) {
        Box(
            modifier = modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(MR.string.chat_gallery_selection_index, selectionNumber),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary,
                textAlign = TextAlign.Center,
            )
        }
    } else {
        // Unselected affordance — mirrors CreateConversationScreen's selection markers
        // (CheckCircle when chosen / outlined Circle when not) for accessibility parity.
        Icon(
            imageVector = Icons.Outlined.Circle,
            contentDescription = stringResource(MR.string.cd_not_selected),
            tint = Color.White.copy(alpha = 0.85f),
            modifier = modifier.size(24.dp)
        )
    }
}

@Composable
fun AttachmentOptions(
    onGalleryClick: () -> Unit,
    onFileClick: () -> Unit,
    onContactClick: () -> Unit,
    onLocationClick: () -> Unit,
    onEventClick: () -> Unit,
    onGroodleClick: () -> Unit,
    onDicesClick: () -> Unit,
    onPollClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        ) {
            item {
                AttachmentOption(
                    modifier = Modifier.testTag("attachment_gallery"),
                    icon = Icons.Default.Image,
                    label = stringResource(MR.string.chat_message_attachment_gallery),
                    onClick = onGalleryClick
                )
            }
            item {
                AttachmentOption(
                    modifier = Modifier.testTag("attachment_file"),
                    icon = Icons.Default.UploadFile,
                    label = stringResource(MR.string.chat_message_attachment_file),
                    onClick = onFileClick
                )
            }
            if (isMobile()) {
                item {
                    AttachmentOption(
                        modifier = Modifier.testTag("attachment_location"),
                        icon = Icons.Default.LocationOn,
                        label = stringResource(MR.string.chat_location_share),
                        onClick = onLocationClick,
                    )
                }
            }
            item {
                AttachmentOption(
                    modifier = Modifier.testTag("attachment_event"),
                    icon = Icons.Default.Event,
                    label = stringResource(MR.string.chat_event_share),
                    onClick = onEventClick,
                )
            }
            item {
                AttachmentOption(
                    modifier = Modifier.testTag("attachment_groodle"),
                    icon = Icons.Default.CalendarMonth,
                    label = stringResource(MR.string.chat_groodle_share),
                    onClick = onGroodleClick,
                )
            }
            item {
                AttachmentOption(
                    modifier = Modifier.testTag("attachment_dices"),
                    icon = Icons.Default.Casino,
                    label = stringResource(MR.string.chat_dice_share),
                    onClick = onDicesClick,
                )
            }
            item {
                AttachmentOption(
                    modifier = Modifier.testTag("attachment_poll"),
                    icon = Icons.Default.BarChart,
                    label = stringResource(MR.string.chat_poll_share),
                    onClick = onPollClick,
                )
            }
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    }
}

@Composable
private fun AttachmentOption(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    contentDescription: String? = null,
) {
    Column(
        modifier = modifier.width(72.dp).noRippleClickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        IconButton(
            modifier = Modifier.size(48.dp),
            onClick = onClick,
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
            shape = RoundedCornerShape(24.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
