package id.homebase.core.ui.screens.card

import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import id.homebase.resources.MR
import id.homebase.resources.profile_card_style_blossom
import id.homebase.resources.profile_card_style_editorial
import id.homebase.resources.profile_card_style_ember
import id.homebase.resources.profile_card_style_harbour
import id.homebase.resources.profile_card_style_nocturne
import id.homebase.resources.profile_card_style_sunset
import id.homebase.resources.profile_card_style_terminal
import id.homebase.resources.profile_card_style_woodland
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

internal val STYLE_ROW_HEIGHT = 84.dp
private val STYLE_TILE_WIDTH = 76.dp
private val STYLE_SWATCH_HEIGHT = 46.dp
private val STYLE_CORNER = 12.dp
private val STYLE_CORNER_SELECTED = 20.dp
private val STYLE_RING = 2.dp
private val STYLE_RING_GAP = 3.dp
private val STYLE_DOT = 10.dp
private const val STYLE_SAMPLE = "Aa"

@Composable
internal fun CardStyleRow(
    styles: List<ResolvedCardStyle>,
    overrides: CardOverrides,
    enabled: Boolean,
    onStyle: (ResolvedCardStyle) -> Unit,
    modifier: Modifier = Modifier,
) {
    ScrollableChoiceRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = 16.dp,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        styles.forEach { style ->
            StyleTile(style, selected = style.matches(overrides), enabled = enabled, onClick = { onStyle(style) })
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StyleTile(style: ResolvedCardStyle, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val spatial = MaterialTheme.motionScheme.fastSpatialSpec<Dp>()
    val corner by animateDpAsState(if (selected) STYLE_CORNER_SELECTED else STYLE_CORNER, spatial)
    val ring by animateColorAsState(if (selected) colors.primary else Color.Transparent, MaterialTheme.motionScheme.fastEffectsSpec())
    val ink = Color(hexToArgb(style.ink))
    // Width follows the text scale so the name isn't ellipsised at large font sizes.
    val width = STYLE_TILE_WIDTH * LocalDensity.current.fontScale.coerceAtLeast(1f)
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
            text = stringResource(styleLabel(style.id)),
            style = if (selected) MaterialTheme.typography.labelMediumEmphasized else MaterialTheme.typography.labelMedium,
            color = if (selected) colors.primary else colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private fun styleLabel(id: String): StringResource = when (id) {
    "nocturne" -> MR.string.profile_card_style_nocturne
    "ember" -> MR.string.profile_card_style_ember
    "woodland" -> MR.string.profile_card_style_woodland
    "harbour" -> MR.string.profile_card_style_harbour
    "editorial" -> MR.string.profile_card_style_editorial
    "blossom" -> MR.string.profile_card_style_blossom
    "sunset" -> MR.string.profile_card_style_sunset
    else -> MR.string.profile_card_style_terminal
}
