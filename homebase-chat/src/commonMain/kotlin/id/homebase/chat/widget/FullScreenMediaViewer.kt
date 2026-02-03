package id.homebase.chat.widget

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichText
import id.homebase.chat.FullScreenMessageData
import id.homebase.core.image.HomebaseImage
import id.homebase.core.image.HomebaseImageData
import id.homebase.resources.MR
import id.homebase.resources.chat_options
import id.homebase.resources.menu_back
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullScreenMediaViewer(
    modifier: Modifier = Modifier,
    data: FullScreenMessageData,
    onDismiss: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    var selectedKey by remember(data) { mutableStateOf(data.selectedPayloadKey) }
    val textState = RichTextState()
    textState.config.listIndent = 0
    textState.setHtml(data.content)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        // Main large image
        val selectedPayload = data.payloads.firstOrNull { it.key == selectedKey }

        if (selectedPayload != null) {
            HomebaseImage(
                imageData = HomebaseImageData(
                    driveId = data.driveId,
                    fileId = data.fileId,
                    payloadKey = selectedKey,
                    previewThumbnail = selectedPayload.previewThumbnail?.toEmbeddedThumb(),
                    lastModified = selectedPayload.lastModified,
                    isEncrypted = true,
                ),
                modifier = Modifier
                    .fillMaxSize(),
                contentScale = ContentScale.Fit,
                contentDescription = selectedPayload.descriptorContent,
                animatedVisibilityScope = animatedVisibilityScope,
                sharedTransitionScope = sharedTransitionScope,
            )
        }

        TopAppBar(
            modifier = Modifier.align(Alignment.TopCenter),
            title = {
                Text(
                    text = data.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

            }, navigationIcon = {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.ChevronLeft,
                        contentDescription = stringResource(MR.string.menu_back)
                    )
                }

            }, actions = {
                IconButton(onClick = {

                }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = stringResource(MR.string.chat_options)
                    )
                }
            }, colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
            )
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            if (data.content.isNotBlank()) {
                RichText(
                    state = textState,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            // Gallery row at bottom
            if (data.payloads.size > 1) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(data.payloads) { payload ->

                        HomebaseImage(
                            imageData = HomebaseImageData(
                                driveId = data.driveId,
                                fileId = data.fileId,
                                payloadKey = payload.key,
                                previewThumbnail = payload.previewThumbnail?.toEmbeddedThumb(),
                                lastModified = payload.lastModified,
                                isEncrypted = true,
                            ),
                            modifier = Modifier
                                .size(60.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(
                                    width = if (payload.key == selectedKey) 2.dp else 0.dp,
                                    color = Color.White,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable { selectedKey = payload.key },
                            contentScale = ContentScale.Crop,
                            contentDescription = payload.descriptorContent,
                            animatedVisibilityScope = animatedVisibilityScope,
                            sharedTransitionScope = null,
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = {}) {
                    Icon(Icons.Default.Share, contentDescription = null)
                }
                IconButton(onClick = {}) {
                    Icon(Icons.AutoMirrored.Filled.Forward, contentDescription = null)
                }
            }
        }
    }
}