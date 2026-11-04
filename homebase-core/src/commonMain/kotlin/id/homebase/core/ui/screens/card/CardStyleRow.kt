package id.homebase.core.ui.screens.card

import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import id.homebase.resources.MR
import id.homebase.resources.profile_card_style_blossom
import id.homebase.resources.profile_card_style_custom
import id.homebase.resources.profile_card_style_editorial
import id.homebase.resources.profile_card_style_ember
import id.homebase.resources.profile_card_style_harbour
import id.homebase.resources.profile_card_style_nocturne
import id.homebase.resources.profile_card_style_sunset
import id.homebase.resources.profile_card_style_terminal
import id.homebase.resources.profile_card_style_woodland
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private val STYLE_TILE_WIDTH = 76.dp
private val STYLE_TILE_MIN_WIDTH = 60.dp
private val STYLE_SWATCH_HEIGHT = 46.dp
private val STYLE_CORNER = 12.dp
private val STYLE_CORNER_SELECTED = 20.dp
private val STYLE_RING = 2.dp
private val STYLE_RING_GAP = 3.dp
private val STYLE_DOT = 10.dp
private val STYLE_LABEL_GAP = 4.dp
private val STYLE_LABEL_LINE = 16.dp
private val STYLE_SPACING = 8.dp
private val STYLE_ROW_INSET = 12.dp
private const val STYLE_SAMPLE = "Aa"
internal const val CUSTOM_STYLE_ID = "custom"

internal fun styleRowHeight(fontScale: Float, rows: Int = 1): Dp =
    (STYLE_RING_GAP * 2 + STYLE_SWATCH_HEIGHT + STYLE_LABEL_GAP + STYLE_LABEL_LINE * fontScale) * rows + STYLE_SPACING * (rows - 1)

/** Every tile in one still row when they fit; otherwise two rows when [wrap], or a scrolling row. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CardStyleRow(
    styles: List<ResolvedCardStyle>,
    overrides: CardOverrides,
    enabled: Boolean,
    onStyle: (ResolvedCardStyle) -> Unit,
    modifier: Modifier = Modifier,
    custom: ResolvedCardStyle? = null,
    wrap: Boolean = false,
    onRows: (Int) -> Unit = {},
) {
    val tiles = listOfNotNull(custom) + styles
    val selectedId = custom?.id ?: styles.firstOrNull { it.matches(overrides) }?.id
    // Tiles widen with the text so a name is never cut; past what fits they scroll instead.
    val grow = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val labels = tiles.map { stringResource(styleLabel(it.id)) }
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelMediumEmphasized
    val density = LocalDensity.current
    val widestLabel = remember(labels, labelStyle, density) {
        with(density) { labels.maxOf { measurer.measure(it, labelStyle).size.width }.toDp() }
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val room = maxWidth - STYLE_ROW_INSET * 2
        fun widthFor(perRow: Int) = (room - STYLE_SPACING * (perRow - 1)) / perRow
        val minWidth = maxOf(STYLE_TILE_MIN_WIDTH * grow, widestLabel)
        val widest = maxOf(STYLE_TILE_WIDTH * grow, minWidth)
        val perRow = (tiles.size + 1) / 2
        val single = widthFor(tiles.size) >= minWidth
        val twoRows = !single && wrap && widthFor(perRow) >= minWidth
        val rows = if (twoRows) 2 else 1
        SideEffect { onRows(rows) }
        val tile: @Composable (ResolvedCardStyle, Dp) -> Unit = { style, width ->
            StyleTile(
                style = style,
                label = labels[tiles.indexOf(style)],
                selected = style.id == selectedId,
                enabled = enabled,
                width = width,
                onClick = { if (style.id != CUSTOM_STYLE_ID) onStyle(style) },
            )
        }
        when {
            single -> Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = STYLE_ROW_INSET).selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(STYLE_SPACING, Alignment.CenterHorizontally),
            ) { tiles.forEach { tile(it, widthFor(tiles.size).coerceAtMost(widest)) } }
            twoRows -> FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = STYLE_ROW_INSET).selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(STYLE_SPACING, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(STYLE_SPACING),
                maxItemsInEachRow = perRow,
            ) { tiles.forEach { tile(it, widthFor(perRow).coerceAtMost(widest)) } }
            else -> ScrollableChoiceRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = STYLE_ROW_INSET,
                horizontalArrangement = Arrangement.spacedBy(STYLE_SPACING),
            ) { tiles.forEach { tile(it, minWidth) } }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StyleTile(style: ResolvedCardStyle, label: String, selected: Boolean, enabled: Boolean, width: Dp, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val spatial = MaterialTheme.motionScheme.fastSpatialSpec<Dp>()
    val corner by animateDpAsState(if (selected) STYLE_CORNER_SELECTED else STYLE_CORNER, spatial)
    val ring by animateColorAsState(if (selected) colors.primary else Color.Transparent, MaterialTheme.motionScheme.fastEffectsSpec())
    val ink = Color(hexToArgb(style.ink))
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(width)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .border(STYLE_RING, ring, RoundedCornerShape(corner + STYLE_RING_GAP))
                .padding(STYLE_RING_GAP),
        ) {
            Surface(
                shape = RoundedCornerShape(corner),
                color = Color(hexToArgb(style.ground)),
                border = BorderStroke(1.dp, colors.outlineVariant),
                modifier = Modifier.fillMaxWidth().height(STYLE_SWATCH_HEIGHT),
            ) {
                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = STYLE_SAMPLE,
                        style = fontPreviewStyle(style.display).copy(color = ink),
                        maxLines = 1,
                        softWrap = false,
                    )
                    style.accent?.let {
                        Spacer(Modifier.size(6.dp))
                        Box(Modifier.size(STYLE_DOT).background(Color(hexToArgb(it)), CircleShape))
                    }
                }
            }
        }
        Text(
            text = label,
            style = if (selected) MaterialTheme.typography.labelMediumEmphasized else MaterialTheme.typography.labelMedium,
            color = if (selected) colors.primary else colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = STYLE_LABEL_GAP),
        )
    }
}

private fun styleLabel(id: String): StringResource = when (id) {
    CUSTOM_STYLE_ID -> MR.string.profile_card_style_custom
    "nocturne" -> MR.string.profile_card_style_nocturne
    "ember" -> MR.string.profile_card_style_ember
    "woodland" -> MR.string.profile_card_style_woodland
    "harbour" -> MR.string.profile_card_style_harbour
    "editorial" -> MR.string.profile_card_style_editorial
    "blossom" -> MR.string.profile_card_style_blossom
    "sunset" -> MR.string.profile_card_style_sunset
    else -> MR.string.profile_card_style_terminal
}
