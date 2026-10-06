@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalComposeUiApi::class)

package id.homebase.core.ui.screens.card

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import id.homebase.core.util.isDesktopOrWeb
import kotlinx.coroutines.flow.first
import kotlin.math.max
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.homebase.resources.MR
import id.homebase.resources.close
import id.homebase.resources.profile_edit_load_failed
import id.homebase.resources.profile_card_intro_hint
import id.homebase.resources.profile_card_intro_open
import id.homebase.resources.profile_card_intro_title
import org.jetbrains.compose.resources.stringResource

private val INTRO_MIN_TILE_WIDTH = 150.dp
private val INTRO_MAX_WIDTH = 960.dp
private val INTRO_TILE_CORNER = 20.dp
private val CAPTURE_HOST_WIDTH = 240.dp
private val CAPTURE_LAYOUT_WIDTH = 360.dp
private val TILE_ICON_SIZE = 18.sp

@Composable
@Suppress("DEPRECATION")
internal fun CardIntro(
    uiState: ProfileCardUiState,
    tiles: Map<String, ImageBitmap>,
    host: CardHost?,
    revision: Int,
    onOpen: (CardAudience) -> Unit,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onCapture: suspend () -> Unit,
    onTilePainted: (CardAudience) -> Unit,
) {
    BackHandler(onBack = onClose)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Attaching the WebView costs frames, which would swallow the enter transition.
    var attached by remember { mutableStateOf(false) }
    LaunchedEffect(lifecycle) {
        lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
        attached = true
    }
    // Desktop and web hand back no snapshot, and their native view cannot sit under Compose content.
    val capturing = host != null && attached && !uiState.viewing && uiState.cards.isNotEmpty() && !isDesktopOrWeb()
    LaunchedEffect(capturing, host, revision, uiState.cards) {
        if (capturing) onCapture()
    }
    val fontScale = LocalDensity.current.fontScale
    CardExpressiveTheme {
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)) {
            // Occluded by the opaque content below: it only has to be attached and laid out for the snapshot.
            if (capturing && host != null) {
                CardHostView(
                    host = host,
                    modifier = Modifier.size(width = CAPTURE_HOST_WIDTH, height = CAPTURE_HOST_WIDTH / CARD_THUMB_ASPECT),
                    layoutWidth = CAPTURE_LAYOUT_WIDTH,
                )
            }
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = INTRO_MAX_WIDTH)
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 8.dp, top = 8.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(MR.string.profile_card_intro_title),
                            style = MaterialTheme.typography.headlineMediumEmphasized,
                            modifier = Modifier.semantics { heading() },
                        )
                        Text(
                            text = stringResource(MR.string.profile_card_intro_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(MR.string.close))
                    }
                }
                when {
                    uiState.loadFailed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CardErrorPlate(message = MR.string.profile_edit_load_failed, onRetry = onRetry)
                    }
                    uiState.cards.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        ContainedLoadingIndicator()
                    }
                    else -> LazyVerticalGrid(
                        // Large type needs a wider tile, or a circle name breaks mid-word.
                        columns = GridCells.Adaptive(INTRO_MIN_TILE_WIDTH * max(1f, fontScale)),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.navigationBars),
                    ) {
                        items(uiState.cards, key = { audienceKey(it.audience) }) { card ->
                            IntroTile(
                                card = card,
                                still = tiles[audienceKey(card.audience)],
                                onClick = { onOpen(card.audience) },
                                onPainted = { onTilePainted(card.audience) },
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun audienceKey(audience: CardAudience): String = when (audience) {
    CardAudience.Public -> "public"
    is CardAudience.Circle -> audience.id.lowercase()
}

@Composable
private fun IntroTile(card: ProfileCard, still: ImageBitmap?, onClick: () -> Unit, onPainted: () -> Unit) {
    val title = audienceLabel(card.audience)
    val open = stringResource(MR.string.profile_card_intro_open, title)
    val palette = card.overrides.palette
    val shape = RoundedCornerShape(INTRO_TILE_CORNER)
    val cardModifier = Modifier
        .fillMaxWidth()
        .aspectRatio(CARD_THUMB_ASPECT)
        .clip(shape)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
    LaunchedEffect(still) {
        if (still != null) {
            withFrameNanos { }
            onPainted()
        }
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .clip(shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = open }
            .padding(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                audienceIcon(card.audience),
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
        if (still != null) {
            Image(bitmap = still, contentDescription = null, contentScale = ContentScale.Crop, modifier = cardModifier)
        } else {
            CardDesignThumbnail(
                design = card.design,
                groundOverride = palette?.ground?.let { Color(hexToArgb(it)) },
                inkOverride = palette?.ink?.let { Color(hexToArgb(it)) },
                modifier = cardModifier,
            )
        }
    }
}
