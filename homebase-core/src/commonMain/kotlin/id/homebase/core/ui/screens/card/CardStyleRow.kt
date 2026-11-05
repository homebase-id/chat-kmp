package id.homebase.core.ui.screens.card

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import id.homebase.resources.MR
import id.homebase.resources.profile_card_option_default
import id.homebase.resources.profile_card_style_custom
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private val STYLE_THUMB_WIDTH = 54.dp
private val STYLE_THUMB_HEIGHT = STYLE_THUMB_WIDTH / CARD_THUMB_ASPECT
private val STYLE_CORNER = 8.dp
private val STYLE_CORNER_SELECTED = 14.dp
private val STYLE_RING = 2.dp
private val STYLE_RING_GAP = 3.dp
private val STYLE_LABEL_GAP = 4.dp
private val STYLE_LABEL_LINE = 16.dp
private val STYLE_SPACING = 8.dp
private val STYLE_ROW_INSET = 12.dp
internal const val CUSTOM_STYLE_ID = "custom"
internal const val DEFAULT_STYLE_ID = "default"

internal fun styleRowHeight(fontScale: Float): Dp =
    (STYLE_RING_GAP * 2 + STYLE_THUMB_HEIGHT + STYLE_LABEL_GAP + STYLE_LABEL_LINE * fontScale)

/** Every tile in one still row when they fit; otherwise a scrolling row. */
@Composable
internal fun CardStyleRow(
    styles: List<ResolvedCardStyle>,
    overrides: CardOverrides,
    design: String,
    enabled: Boolean,
    onStyle: (ResolvedCardStyle) -> Unit,
    modifier: Modifier = Modifier,
    custom: ResolvedCardStyle? = null,
) {
    val tiles = listOfNotNull(custom) + styles
    val selectedId = custom?.id ?: styles.firstOrNull { it.matches(overrides) }?.id
    val labels = tiles.map { stringResource(styleLabel(it.id, it.isDefault)) }
    val labelStyle = MaterialTheme.typography.labelMedium
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // A tile widens to its own name so none is cut; the drawing stays one size so the row's height is known.
    val widths = remember(labels, labelStyle, density) {
        labels.map { maxOf(STYLE_THUMB_WIDTH + STYLE_RING_GAP * 2, with(density) { measurer.measure(it, labelStyle).size.width.toDp() }) }
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val fits = widths.fold(STYLE_SPACING * (tiles.size - 1)) { sum, w -> sum + w } <= maxWidth - STYLE_ROW_INSET * 2
        val content: @Composable () -> Unit = {
            tiles.forEachIndexed { i, style ->
                StyleTile(
                    style = style,
                    design = design,
                    label = labels[i],
                    labelStyle = labelStyle,
                    selected = style.id == selectedId,
                    enabled = enabled,
                    width = widths[i],
                    onClick = { if (style.id != CUSTOM_STYLE_ID) onStyle(style) },
                )
            }
        }
        if (fits) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = STYLE_ROW_INSET).selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(STYLE_SPACING, Alignment.CenterHorizontally),
            ) { content() }
        } else {
            ScrollableChoiceRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = STYLE_ROW_INSET,
                horizontalArrangement = Arrangement.spacedBy(STYLE_SPACING),
            ) { content() }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StyleTile(
    style: ResolvedCardStyle,
    design: String,
    label: String,
    labelStyle: TextStyle,
    selected: Boolean,
    enabled: Boolean,
    width: Dp,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val spatial = MaterialTheme.motionScheme.fastSpatialSpec<Dp>()
    val corner by animateDpAsState(if (selected) STYLE_CORNER_SELECTED else STYLE_CORNER, spatial)
    val ring by animateColorAsState(if (selected) colors.primary else Color.Transparent, MaterialTheme.motionScheme.fastEffectsSpec())
    val shape = RoundedCornerShape(corner)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(width)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
    ) {
        Box(
            Modifier
                .border(STYLE_RING, ring, RoundedCornerShape(corner + STYLE_RING_GAP))
                .padding(STYLE_RING_GAP),
        ) {
            // With no accent of its own the tile draws the design's preset accent, as the card does.
            CardDesignThumbnail(
                design = design,
                groundOverride = Color(hexToArgb(style.ground)),
                inkOverride = Color(hexToArgb(style.ink)),
                accentOverride = style.accent?.let { Color(hexToArgb(it)) },
                portrait = style.portrait,
                blocks = style.blocks,
                socials = style.socials,
                modifier = Modifier
                    .size(STYLE_THUMB_WIDTH, STYLE_THUMB_HEIGHT)
                    .clip(shape)
                    .border(1.dp, colors.outlineVariant, shape),
            )
        }
        Text(
            text = label,
            style = labelStyle,
            color = if (selected) colors.primary else colors.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = STYLE_LABEL_GAP),
        )
    }
}

private fun styleLabel(id: String, isDefault: Boolean): StringResource = when {
    isDefault -> MR.string.profile_card_option_default
    id == CUSTOM_STYLE_ID -> MR.string.profile_card_style_custom
    else -> schemeLabel(id)
}
