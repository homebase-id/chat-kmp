@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package id.homebase.core.ui.screens.profile

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import id.homebase.resources.profile_edit_card_shows
import id.homebase.resources.profile_edit_cards_show_all
import org.jetbrains.compose.resources.StringResource
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.carousel.HorizontalUncontainedCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.homebase.core.ui.screens.card.CardCircle
import id.homebase.resources.MR
import id.homebase.resources.profile_edit_card_details
import id.homebase.resources.profile_edit_card_no_details
import id.homebase.resources.profile_edit_cards_desc
import id.homebase.resources.profile_edit_cards_focus
import id.homebase.resources.profile_edit_cards_title
import id.homebase.resources.profile_edit_visibility_public
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** One of the fixed cards: Public ([circle] null) or a Contacts circle's. */
@Immutable
internal data class EditorCard(val circle: CardCircle?) {
    val key: String get() = circle?.id ?: PUBLIC_CARD_KEY
}

internal const val PUBLIC_CARD_KEY = "public-card"

/** What one card would show, so its tile can preview it: the name line and the kinds of detail on it. */
@Immutable
internal class CardContents(val name: String?, val labels: List<StringResource>, val count: Int)

/** Bumped on each save; the cards in [keys] pulse once. */
@Immutable
internal data class CardPulse(val tick: Int = 0, val keys: Set<String> = emptySet())

@Composable
internal fun ProfileCardsStrip(
    cards: List<EditorCard>,
    contents: (EditorCard) -> CardContents,
    selected: String?,
    onSelect: (String?) -> Unit,
    pulse: CardPulse,
    modifier: Modifier = Modifier,
) {
    val motion = MaterialTheme.motionScheme
    // Large text grows the tiles instead of truncating them.
    val growth = LocalDensity.current.fontScale.coerceIn(1f, MAX_TILE_GROWTH)
    val tileHeight = CARD_TILE_HEIGHT * growth
    val tileWidth = CARD_TILE_WIDTH * growth.coerceAtMost(MAX_TILE_WIDTH_GROWTH)
    Column(modifier = modifier.fillMaxWidth()) {
        val focused = cards.firstOrNull { it.key == selected }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(MR.string.profile_edit_cards_title),
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                AnimatedContent(
                    targetState = focused,
                    transitionSpec = { fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()) },
                ) { card ->
                    Text(
                        text = if (card == null) {
                            stringResource(MR.string.profile_edit_cards_desc)
                        } else {
                            stringResource(MR.string.profile_edit_cards_focus, cardLabel(card))
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            AnimatedVisibility(
                visible = focused != null,
                enter = scaleIn(motion.fastSpatialSpec()) + fadeIn(motion.fastEffectsSpec()),
                exit = scaleOut(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
            ) {
                TextButton(onClick = { onSelect(null) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(MR.string.profile_edit_cards_show_all), maxLines = 1)
                }
            }
        }
        if (cards.size == 1) {
            val card = cards.single()
            val corner = tileCorner(card.key == selected)
            CardTile(
                card = card,
                contents = contents(card),
                selected = card.key == selected,
                muted = selected != null && card.key != selected,
                pulse = pulse,
                corner = corner,
                onClick = { onSelect(if (card.key == selected) null else card.key) },
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .height(tileHeight)
                    .clip(RoundedCornerShape(corner)),
            )
        } else {
            // Uncontained keeps every tile full size; a multi-browse carousel squeezes the edge tiles and clips their text.
            HorizontalUncontainedCarousel(
                state = rememberCarouselState { cards.size },
                itemWidth = tileWidth,
                itemSpacing = 8.dp,
                contentPadding = PaddingValues(horizontal = 16.dp),
                modifier = Modifier.fillMaxWidth().height(tileHeight),
            ) { index ->
                val card = cards[index]
                val corner = tileCorner(card.key == selected)
                CardTile(
                    card = card,
                    contents = contents(card),
                    selected = card.key == selected,
                    muted = selected != null && card.key != selected,
                    pulse = pulse,
                    corner = corner,
                    onClick = { onSelect(if (card.key == selected) null else card.key) },
                    modifier = Modifier.fillMaxSize().maskClip(RoundedCornerShape(corner)),
                )
            }
        }
    }
}

/** A picked card's corners open up on the spatial spring, so the selection reads as a change of shape, not an outline. */
@Composable
private fun tileCorner(selected: Boolean): Dp =
    animateDpAsState(if (selected) 40.dp else 20.dp, MaterialTheme.motionScheme.defaultSpatialSpec()).value

@Composable
private fun cardLabel(card: EditorCard): String =
    card.circle?.name ?: stringResource(MR.string.profile_edit_visibility_public)

@Composable
private fun CardTile(
    card: EditorCard,
    contents: CardContents,
    selected: Boolean,
    muted: Boolean,
    pulse: CardPulse,
    corner: Dp,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val motion = MaterialTheme.motionScheme
    val colors = MaterialTheme.colorScheme
    val isPublic = card.circle == null
    val container by animateColorAsState(
        if (selected) colors.primaryContainer else colors.surfaceContainerHigh,
        motion.defaultEffectsSpec(),
    )
    val content by animateColorAsState(
        if (selected) colors.onPrimaryContainer else colors.onSurface,
        motion.defaultEffectsSpec(),
    )
    val accent = if (isPublic) colors.primary else colors.secondary
    val onAccent = if (isPublic) colors.onPrimary else colors.onSecondary

    // A save pulses the cards the detail now appears on: the content swells and a ring flashes, then both settle.
    val pulseLevel = remember { Animatable(0f) }
    LaunchedEffect(pulse.tick) {
        if (pulse.tick > 0 && card.key in pulse.keys) {
            pulseLevel.animateTo(1f, motion.fastSpatialSpec())
            pulseLevel.animateTo(0f, motion.slowSpatialSpec())
        }
    }
    val focusRing by animateDpAsState(if (selected) 3.dp else 0.dp, motion.defaultSpatialSpec())
    val ring = maxOf(focusRing, 4.dp * pulseLevel.value)
    val ringColor = if (selected) colors.primary else accent
    // The other cards step back with the same dimming the details off the picked card get.
    val alpha by animateFloatAsState(if (muted) MUTED_TILE_ALPHA else 1f, motion.defaultEffectsSpec())
    val badgeTurn by animateFloatAsState(if (selected) BADGE_TURN_DEGREES else 0f, motion.defaultSpatialSpec())
    val badgeScale by animateFloatAsState(if (selected) 1.12f else 1f, motion.fastSpatialSpec())
    val label = cardLabel(card)
    val labels = contents.labels.map { stringResource(it) }
    val summary = labels.joinToString(", ")

    Box(
        modifier = modifier
            .graphicsLayer { this.alpha = alpha }
            // The tile's own shape, not only the carousel mask, so the morph shows even where the mask lags.
            .clip(RoundedCornerShape(corner))
            .background(container)
            .border(ring, if (ring > 0.dp) ringColor else Color.Transparent, RoundedCornerShape(corner))
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val scale = 1f + 0.05f * pulseLevel.value
                    scaleX = scale
                    scaleY = scale
                }
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(44.dp).graphicsLayer {
                        scaleX = badgeScale
                        scaleY = badgeScale
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer { rotationZ = badgeTurn }
                            .background(accent, MaterialShapes.Cookie9Sided.toShape()),
                    )
                    Icon(
                        imageVector = if (isPublic) Icons.Outlined.Public else Icons.Outlined.Groups,
                        contentDescription = null,
                        tint = onAccent,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                if (contents.count > 0) {
                    Text(
                        text = pluralStringResource(MR.plurals.profile_edit_card_details, contents.count, contents.count),
                        style = MaterialTheme.typography.labelLarge,
                        color = content,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = label,
                style = MaterialTheme.typography.titleLargeEmphasized,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            (contents.name ?: stringResource(MR.string.profile_edit_card_no_details).takeIf { contents.count == 0 })?.let { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = content,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (summary.isNotEmpty()) {
                val shows = stringResource(MR.string.profile_edit_card_shows, summary)
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = content.copy(alpha = 0.78f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp).semantics { contentDescription = shows },
                )
            }
        }
    }
}

private const val BADGE_TURN_DEGREES = 40f
private const val MUTED_TILE_ALPHA = 0.6f
private const val MAX_TILE_GROWTH = 1.7f
private const val MAX_TILE_WIDTH_GROWTH = 1.3f
private val CARD_TILE_WIDTH = 184.dp
private val CARD_TILE_HEIGHT = 176.dp
