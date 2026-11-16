@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package id.homebase.core.ui.screens.card

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.homebase.core.util.isDesktopOrWeb
import id.homebase.resources.MR
import id.homebase.resources.profile_card_new
import id.homebase.resources.profile_card_intro_open
import org.jetbrains.compose.resources.stringResource
import kotlin.math.max

private val OVERVIEW_MIN_TILE_WIDTH = 150.dp
private val OVERVIEW_MAX_TILE_WIDTH = 240.dp
private val OVERVIEW_TILE_CORNER = 20.dp
private val OVERVIEW_TOP_ROOM = 72.dp
private val TILE_ICON_SIZE = 18.sp

@Composable
internal fun CardOverview(
    uiState: ProfileCardUiState,
    tiles: Map<String, ImageBitmap>,
    host: CardHost?,
    onOpen: (CardAudience) -> Unit,
    onCapture: suspend () -> Unit,
    onNewCard: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val cards = uiState.cards
    val capturing = host != null && cards.isNotEmpty()
    LaunchedEffect(capturing, host, cards) { if (capturing) onCapture() }
    val fontScale = LocalDensity.current.fontScale
    Box(modifier) {
        // Occluded by the opaque grid: the host only has to be attached for the snapshot. Desktop and web export the still from the page.
        if (capturing && host != null && !isDesktopOrWeb()) {
            CardHostView(
                host = host,
                modifier = Modifier.size(width = CAPTURE_HOST_WIDTH, height = CAPTURE_HOST_WIDTH / CARD_THUMB_ASPECT),
                layoutWidth = CAPTURE_LAYOUT_WIDTH,
            )
        }
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)) {
            if (cards.isEmpty()) {
                ContainedLoadingIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                LazyVerticalGrid(
                    columns = OverviewCells(
                        minSize = OVERVIEW_MIN_TILE_WIDTH * max(1f, fontScale),
                        maxSize = OVERVIEW_MAX_TILE_WIDTH * max(1f, fontScale),
                        count = cards.size + if (onNewCard != null) 1 else 0,
                    ),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = OVERVIEW_TOP_ROOM, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                ) {
                    items(cards, key = { audienceKey(it.audience) }) { card ->
                        OverviewTile(
                            card = card,
                            emoji = uiState.circles.emojiOf(card.audience),
                            still = tiles[audienceKey(card.audience)],
                            onClick = { onOpen(card.audience) },
                        )
                    }
                    if (onNewCard != null) {
                        item(key = "new-circle") { NewCardTile(onClick = onNewCard) }
                    }
                }
            }
        }
    }
}

// Adaptive, but never more columns than tiles and never wider than a tile should be.
private class OverviewCells(private val minSize: Dp, private val maxSize: Dp, private val count: Int) : GridCells {
    override fun Density.calculateCrossAxisCellSizes(availableSize: Int, spacing: Int): List<Int> {
        val fits = ((availableSize + spacing) / (minSize.roundToPx() + spacing)).coerceAtLeast(1)
        val columns = fits.coerceAtMost(count.coerceAtLeast(1))
        val size = ((availableSize - spacing * (columns - 1)) / columns).coerceAtMost(maxSize.roundToPx())
        return List(columns) { size }
    }

    override fun equals(other: Any?) =
        other is OverviewCells && other.minSize == minSize && other.maxSize == maxSize && other.count == count

    override fun hashCode() = (minSize.hashCode() * 31 + maxSize.hashCode()) * 31 + count
}

@Composable
private fun OverviewTile(card: ProfileCard, emoji: String?, still: ImageBitmap?, onClick: () -> Unit) {
    val title = audienceLabel(card.audience)
    val open = stringResource(MR.string.profile_card_intro_open, title)
    val shape = RoundedCornerShape(OVERVIEW_TILE_CORNER)
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .clip(shape)
            .clickable(role = Role.Button, onClick = onClick)
            .handCursor()
            .semantics(mergeDescendants = true) { contentDescription = open }
            .padding(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AudienceGlyph(
                icon = audienceIcon(card.audience),
                emoji = emoji,
                size = with(LocalDensity.current) { TILE_ICON_SIZE.toDp() },
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMediumEmphasized,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        CardStill(
            card = card,
            still = still,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(CARD_THUMB_ASPECT)
                .clip(shape)
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape),
        )
    }
}

@Composable
private fun NewCardTile(onClick: () -> Unit) {
    val title = stringResource(MR.string.profile_card_new)
    val shape = RoundedCornerShape(OVERVIEW_TILE_CORNER)
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .clip(shape)
            .clickable(role = Role.Button, onClick = onClick)
            .handCursor()
            .semantics(mergeDescendants = true) { contentDescription = title }
            .padding(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                Icons.Outlined.Add,
                contentDescription = null,
                modifier = Modifier.size(with(LocalDensity.current) { TILE_ICON_SIZE.toDp() }),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMediumEmphasized,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(CARD_THUMB_ASPECT)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape),
        ) {
            CookieBadge(
                icon = Icons.Outlined.Add,
                container = MaterialTheme.colorScheme.primaryContainer,
                content = MaterialTheme.colorScheme.onPrimaryContainer,
                size = 64.dp,
                iconSize = 28.dp,
            )
        }
    }
}
