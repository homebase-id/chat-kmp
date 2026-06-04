package id.homebase.chat.widget

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.UploadFile
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
import id.homebase.core.util.isMobile
import id.homebase.core.util.noRippleClickable
import id.homebase.resources.MR
import id.homebase.resources.chat_message_attachment_file
import id.homebase.resources.chat_message_attachment_gallery
import id.homebase.resources.chat_dice_share
import id.homebase.resources.chat_event_share
import id.homebase.resources.chat_groodle_share
import id.homebase.resources.chat_location_share
import id.homebase.resources.chat_message_needs_gallery_permission
import id.homebase.resources.chat_message_needs_gallery_permission_button_text
import id.homebase.resources.chat_no_gallery_items
import id.homebase.resources.cd_gallery_thumbnail
import id.homebase.resources.cd_play_video
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

@Composable
fun AttachmentGallery(
    onImageSelected: (GalleryImage) -> Unit,
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
                            Box(
                                contentAlignment = Alignment.Center
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
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { onImageSelected(galleryImage) },
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
        }
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
    onClick: () -> Unit
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
                contentDescription = null,
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
