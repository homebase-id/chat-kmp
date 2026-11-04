package id.homebase.core.ui.screens.card

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
private val STYLE_SWATCH_HEIGHT = 52.dp
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

@Composable
private fun StyleTile(style: ResolvedCardStyle, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val ground = Color(hexToArgb(style.ground))
    val ink = Color(hexToArgb(style.ink))
    val name = stringResource(styleLabel(style.id))
    val border = if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(STYLE_TILE_WIDTH)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
    ) {
        Surface(shape = shape, color = ground, border = border, modifier = Modifier.fillMaxWidth().height(STYLE_SWATCH_HEIGHT)) {
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
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
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
