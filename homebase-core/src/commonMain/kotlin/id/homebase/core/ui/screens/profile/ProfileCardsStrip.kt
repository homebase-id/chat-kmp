@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package id.homebase.core.ui.screens.profile

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.material.icons.outlined.Check
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
import androidx.compose.ui.graphics.vector.ImageVector
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

/** What one card would show, so its tile can preview it. */
@Immutable
internal class CardContents(val name: String?, val icons: List<ImageVector>, val count: Int)

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
    Column(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(MR.string.profile_edit_cards_title),
                style = MaterialTheme.typography.titleLargeEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val focused = cards.firstOrNull { it.key == selected }
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
        if (cards.size == 1) {
            val card = cards.single()
            CardTile(
                card = card,
                contents = contents(card),
                selected = card.key == selected,
                pulse = pulse,
                onClick = { onSelect(if (card.key == selected) null else card.key) },
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .height(CARD_TILE_HEIGHT)
                    .clip(MaterialTheme.shapes.extraLarge),
            )
        } else {
            // Uncontained keeps every tile full size; a multi-browse carousel squeezes the edge tiles and clips their text.
            HorizontalUncontainedCarousel(
                state = rememberCarouselState { cards.size },
                itemWidth = CARD_TILE_WIDTH,
                itemSpacing = 8.dp,
                contentPadding = PaddingValues(horizontal = 16.dp),
                modifier = Modifier.fillMaxWidth().height(CARD_TILE_HEIGHT),
            ) { index ->
                val card = cards[index]
                CardTile(
                    card = card,
                    contents = contents(card),
                    selected = card.key == selected,
                    pulse = pulse,
                    onClick = { onSelect(if (card.key == selected) null else card.key) },
                    modifier = Modifier.fillMaxSize().maskClip(MaterialTheme.shapes.extraLarge),
                )
            }
        }
    }
}

@Composable
private fun cardLabel(card: EditorCard): String =
    card.circle?.name ?: stringResource(MR.string.profile_edit_visibility_public)

@Composable
private fun CardTile(
    card: EditorCard,
    contents: CardContents,
    selected: Boolean,
    pulse: CardPulse,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val motion = MaterialTheme.motionScheme
    val colors = MaterialTheme.colorScheme
    val isPublic = card.circle == null
    val container = if (isPublic) colors.primaryContainer else colors.secondaryContainer
    val content = if (isPublic) colors.onPrimaryContainer else colors.onSecondaryContainer
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
    val selectedRing by animateDpAsState(if (selected) 3.dp else 0.dp, motion.fastSpatialSpec())
    val ring = selectedRing + 4.dp * pulseLevel.value

    Box(
        modifier = modifier
            .background(container)
            .border(ring, if (ring > 0.dp) accent else Color.Transparent, MaterialTheme.shapes.extraLarge)
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
                    modifier = Modifier.size(40.dp).background(accent, MaterialShapes.Cookie9Sided.toShape()),
                    contentAlignment = Alignment.Center,
                ) {
                    AnimatedContent(
                        targetState = selected,
                        transitionSpec = {
                            (scaleIn(motion.fastSpatialSpec()) + fadeIn(motion.fastEffectsSpec())) togetherWith
                                (scaleOut(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()))
                        },
                    ) { on ->
                        Icon(
                            imageVector = when {
                                on -> Icons.Outlined.Check
                                isPublic -> Icons.Outlined.Public
                                else -> Icons.Outlined.Groups
                            },
                            contentDescription = null,
                            tint = onAccent,
                            modifier = Modifier.size(20.dp),
                        )
                    }
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
                text = cardLabel(card),
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
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                contents.icons.take(MAX_TILE_ICONS).forEach { icon ->
                    Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

private val CARD_TILE_WIDTH = 184.dp
private val CARD_TILE_HEIGHT = 176.dp
private const val MAX_TILE_ICONS = 7
