package id.homebase.chat.widget

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Videocam
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import id.homebase.api.isIos
import id.homebase.core.gallery.GalleryImage
import id.homebase.core.gallery.PlatformGalleryManager
import id.homebase.core.gallery.rememberGalleryPermissionState
import id.homebase.core.ui.theme.Dimens
import id.homebase.core.util.isMobile
import id.homebase.core.util.noRippleClickable
import id.homebase.resources.MR
import id.homebase.resources.chat_message_attachment_file
import id.homebase.resources.chat_message_attachment_gallery
import id.homebase.resources.chat_message_needs_gallery_permission
import id.homebase.resources.chat_message_needs_gallery_permission_button_text
import kotlinx.coroutines.launch
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
        val scope = rememberCoroutineScope()
        val galleryLoader = koinInject<PlatformGalleryManager>()
        val galleryItems = remember { mutableStateListOf<GalleryImage>() }
        val galleryPermissionState = rememberGalleryPermissionState(
            onGalleryPermissionGranted = {
                scope.launch {
                    galleryItems.clear()
                    galleryItems.addAll(galleryLoader.fetchGalleryImages())
                }
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
                    Text("No items", modifier = Modifier.align(Alignment.Center))
                } else {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {

                        items(galleryItems.size) { index ->
                            val galleryImage = galleryItems[index]
                            Box {
                                AsyncImage(
                                    imageLoader = imageLoader,
                                    model = galleryImage.file.toString(),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(160.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { onImageSelected(galleryImage) },
                                    contentScale = ContentScale.Crop
                                )
                                Icon(
                                    imageVector = if (galleryImage.isVideo()) Icons.Default.Videocam else Icons.Default.Image,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .size(24.dp)
                                        .padding(top = 4.dp, start = 4.dp)
                                        .align(Alignment.TopStart)
                                )
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
                                Text("Manage")

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
                                    text = { Text("Select more photos") },
                                    onClick = {
                                        showMenu = false
                                        galleryPermissionState.requestPartialGalleryPermission()
                                    }
                                )
                                DropdownMenuItem(
                                    leadingIcon = {
                                        Icon(Icons.Default.Settings, contentDescription = null)
                                    },
                                    text = { Text("Go to settings") },
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
    onLocationClick: () -> Unit
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
                    icon = Icons.Default.Image,
                    label = stringResource(MR.string.chat_message_attachment_gallery),
                    onClick = onGalleryClick
                )
            }
            item {
                AttachmentOption(
                    icon = Icons.Default.UploadFile,
                    label = stringResource(MR.string.chat_message_attachment_file),
                    onClick = onFileClick
                )
            }
//            if (isMobile()) {
//                item {
//                    AttachmentOption(
//                        icon = Icons.Default.AccountCircle,
//                        label = stringResource(MR.string.chat_message_attachment_contact),
//                        onClick = onContactClick
//                    )
//                }
//                item {
//                    AttachmentOption(
//                        icon = Icons.Default.LocationOn,
//                        label = stringResource(MR.string.chat_message_attachment_location),
//                        onClick = onLocationClick
//                    )
//                }
//            }
        }
    }
}

@Composable
private fun AttachmentOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier.width(72.dp).noRippleClickable { onClick() },
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
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}
