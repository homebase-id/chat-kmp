package id.homebase.core.ui.screens.moments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.core.moments.services.MomentFeedItem
import id.homebase.core.ui.screens.moments.widget.MomentMediaGallery
import id.homebase.resources.MR
import id.homebase.resources.moments_create_action
import id.homebase.resources.moments_label
import id.homebase.resources.moments_post_open
import id.homebase.resources.moments_reaction_like
import id.homebase.resources.moments_welcome
import org.jetbrains.compose.resources.stringResource

/**
 * Page 1 — Main Feed / Timeline.
 *
 * Subscribes to [MomentsFeedViewModel] for the live moment list and renders
 * each post via [MomentMediaGallery] (which delegates to MomentMediaItem for
 * the single-payload case). Empty state shows a placeholder + the FAB.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MomentsScreen(
    viewModel: MomentsFeedViewModel,
    onCreateMoment: () -> Unit = {},
    /**
     * Open the detail view for a moment. `payloadKey` is the specific media
     * item the user tapped (so the detail carousel can land on that page);
     * `null` for taps that aren't on a specific cell (e.g. the indicator
     * row), which fall back to page 0.
     */
    onOpenMoment: (momentId: String, payloadKey: String?) -> Unit = { _, _ -> },
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val openLabel = stringResource(MR.string.moments_post_open)

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(MR.string.moments_label)) })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCreateMoment) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(MR.string.moments_create_action),
                )
            }
        },
    ) { innerPadding ->
        if (uiState.moments.isEmpty()) {
            EmptyMomentsState(
                modifier = Modifier
                    .fillMaxSize()
                    .consumeWindowInsets(innerPadding)
                    .padding(innerPadding),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .consumeWindowInsets(innerPadding)
                    .padding(innerPadding),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(uiState.moments, key = { it.id.toString() }) { moment ->
                    MomentPostCard(
                        moment = moment,
                        onCardClick = { onOpenMoment(moment.id.toString(), null) },
                        onMediaClick = { payloadKey ->
                            onOpenMoment(moment.id.toString(), payloadKey)
                        },
                        onClickLabel = openLabel,
                    )
                }
            }
        }
    }
}

@Composable
private fun MomentPostCard(
    moment: MomentFeedItem,
    onCardClick: () -> Unit,
    onMediaClick: (payloadKey: String) -> Unit,
    onClickLabel: String,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(MaterialTheme.shapes.large)
            // Card-level clickable handles taps that aren't on a media cell
            // (overlay indicators, padding, description-only tiles). The
            // gallery's per-cell onMediaClick consumes cell taps and routes
            // them through with the payload key so the detail carousel can
            // land on the specific page the user picked.
            .clickable(onClick = onCardClick, onClickLabel = onClickLabel),
    ) {
        if (moment.payloads.isEmpty()) {
            // Description-only / corrupt-payload moment — still render a tile so
            // the user sees something tappable.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(24.dp),
            ) {
                Text(
                    text = moment.description.ifBlank { "—" },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            MomentMediaGallery(
                payloads = moment.payloads,
                fileId = moment.fileId,
                driveId = moment.driveId,
                previewThumbnail = moment.previewThumbnail,
                keyHeader = moment.keyHeader,
                messageId = moment.id,
                downloadingFiles = emptySet(),
                sharedTransitionScope = null,
                animatedVisibilityScope = null,
                onMediaClick = { payload -> onMediaClick(payload.key) },
            )
        }

        // Overlay indicators in the bottom-right of the thumbnail. Reaction
        // counts / comments toggle aren't wired through the post yet, so the
        // info badge stands in for "has description" and the heart is the
        // toggleable reaction placeholder.
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (moment.description.isNotBlank()) {
                IndicatorBadge(Icons.Outlined.Info)
            }
            // Comments indicator placeholder — shown unconditionally for now.
            // When the comments-enabled flag round-trips through MomentPostContent,
            // gate this on it.
            IndicatorBadge(Icons.Outlined.ChatBubbleOutline)
            IndicatorBadge(
                imageVector = Icons.Outlined.FavoriteBorder,
                contentDescription = stringResource(MR.string.moments_reaction_like),
            )
        }
    }
}

@Composable
private fun EmptyMomentsState(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(96.dp),
            )
            Text(
                text = stringResource(MR.string.moments_welcome),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 24.dp),
            )
        }
    }
}

@Composable
private fun IndicatorBadge(
    imageVector: ImageVector,
    contentDescription: String? = null,
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(16.dp),
        )
    }
}
