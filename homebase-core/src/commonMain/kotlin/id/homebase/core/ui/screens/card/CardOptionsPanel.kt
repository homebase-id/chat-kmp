@file:OptIn(ExperimentalLayoutApi::class)

package id.homebase.core.ui.screens.card

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import id.homebase.resources.MR
import id.homebase.resources.profile_card_block_chat
import id.homebase.resources.profile_card_block_links
import id.homebase.resources.profile_card_block_moments
import id.homebase.resources.profile_card_block_posts
import id.homebase.resources.profile_card_font_archivo_black
import id.homebase.resources.profile_card_font_caveat
import id.homebase.resources.profile_card_font_montserrat
import id.homebase.resources.profile_card_font_montserrat_alt
import id.homebase.resources.profile_card_font_newsreader
import id.homebase.resources.profile_card_font_space_mono
import id.homebase.resources.profile_card_option_accent
import id.homebase.resources.profile_card_option_accent_swatch
import id.homebase.resources.profile_card_option_block_order
import id.homebase.resources.profile_card_option_default
import id.homebase.resources.profile_card_option_display_font
import id.homebase.resources.profile_card_option_move_down
import id.homebase.resources.profile_card_option_move_up
import id.homebase.resources.profile_card_option_portrait_shape
import id.homebase.resources.profile_card_option_socials_style
import id.homebase.resources.profile_card_option_text_font
import id.homebase.resources.profile_card_options_empty
import id.homebase.resources.profile_card_shape_circle
import id.homebase.resources.profile_card_shape_ellipse
import id.homebase.resources.profile_card_shape_rounded
import id.homebase.resources.profile_card_shape_square
import id.homebase.resources.profile_card_socials_bar
import id.homebase.resources.profile_card_socials_glyphs
import id.homebase.resources.profile_card_socials_handles
import id.homebase.resources.profile_card_socials_wordmark
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private val MAX_OPTIONS_HEIGHT = 260.dp
private val SWATCH_SIZE = 36.dp

/** Every control comes from the design's [CardDesignSpec]; there is no per-design screen. */
@Composable
internal fun CardOptionsPanel(
    design: String,
    overrides: CardOverrides,
    enabled: Boolean,
    onOption: (CardOption, String?) -> Unit,
    onBlockOrder: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spec = CardDesignSpecs.of(design)
    val options = CardOption.entries.filter { spec != null && it in spec.options }
    if (options.isEmpty()) {
        Text(
            text = stringResource(MR.string.profile_card_options_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.fillMaxWidth().padding(vertical = 16.dp),
        )
        return
    }
    Column(
        modifier = modifier.fillMaxWidth().heightIn(max = MAX_OPTIONS_HEIGHT).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        options.forEach { option ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(optionLabel(option)),
                    style = MaterialTheme.typography.titleSmall,
                )
                when (option) {
                    CardOption.ACCENT -> SwatchRow(overrides.valueOf(option), enabled) { onOption(option, it) }
                    CardOption.BLOCK_ORDER -> BlockOrderRow(overrides.blockOrder(), enabled, onBlockOrder)
                    else -> ChoiceRow(
                        choices = choicesOf(option),
                        selected = overrides.valueOf(option),
                        enabled = enabled,
                        onSelect = { onOption(option, it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChoiceRow(
    choices: List<Pair<String, StringResource>>,
    selected: String?,
    enabled: Boolean,
    onSelect: (String?) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            enabled = enabled,
            label = { Text(stringResource(MR.string.profile_card_option_default)) },
        )
        choices.forEach { (value, label) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                enabled = enabled,
                label = { Text(stringResource(label)) },
            )
        }
    }
}

@Composable
private fun SwatchRow(selected: String?, enabled: Boolean, onSelect: (String?) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            enabled = enabled,
            label = { Text(stringResource(MR.string.profile_card_option_default)) },
        )
        CardDesignSpecs.ACCENT_SWATCHES.forEach { hex ->
            val on = selected.equals(hex, ignoreCase = true)
            val description = stringResource(MR.string.profile_card_option_accent_swatch, hex)
            Box(
                modifier = Modifier
                    .size(SWATCH_SIZE)
                    .background(hexColor(hex), CircleShape)
                    .border(
                        BorderStroke(if (on) 3.dp else 1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                        CircleShape,
                    )
                    .selectable(selected = on, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(hex) })
                    .semantics { contentDescription = description },
            )
        }
    }
}

@Composable
private fun BlockOrderRow(order: List<String>, enabled: Boolean, onChange: (List<String>) -> Unit) {
    Column {
        order.forEachIndexed { index, kind ->
            val name = stringResource(blockLabel(kind))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                IconButton(enabled = enabled && index > 0, onClick = { onChange(order.swap(index, index - 1)) }) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(MR.string.profile_card_option_move_up, name))
                }
                IconButton(enabled = enabled && index < order.lastIndex, onClick = { onChange(order.swap(index, index + 1)) }) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(MR.string.profile_card_option_move_down, name))
                }
            }
        }
    }
}

private fun List<String>.swap(a: Int, b: Int): List<String> {
    val moved = toMutableList()
    moved[a] = this[b]
    moved[b] = this[a]
    return moved
}

private fun hexColor(hex: String): Color = Color(hex.removePrefix("#").toLong(16) or 0xFF000000)

private fun optionLabel(option: CardOption): StringResource = when (option) {
    CardOption.ACCENT -> MR.string.profile_card_option_accent
    CardOption.DISPLAY_FONT -> MR.string.profile_card_option_display_font
    CardOption.TEXT_FONT -> MR.string.profile_card_option_text_font
    CardOption.PORTRAIT_SHAPE -> MR.string.profile_card_option_portrait_shape
    CardOption.SOCIALS_STYLE -> MR.string.profile_card_option_socials_style
    CardOption.BLOCK_ORDER -> MR.string.profile_card_option_block_order
}

private fun choicesOf(option: CardOption): List<Pair<String, StringResource>> = when (option) {
    CardOption.DISPLAY_FONT, CardOption.TEXT_FONT -> CardDesignSpecs.FONTS.map { it to fontLabel(it) }
    CardOption.PORTRAIT_SHAPE -> CardDesignSpecs.PORTRAIT_SHAPES.map { it to shapeLabel(it) }
    CardOption.SOCIALS_STYLE -> CardDesignSpecs.SOCIALS_STYLES.map { it to socialsLabel(it) }
    CardOption.ACCENT, CardOption.BLOCK_ORDER -> emptyList()
}

private fun fontLabel(font: String): StringResource = when (font) {
    "montserrat-alt" -> MR.string.profile_card_font_montserrat_alt
    "newsreader" -> MR.string.profile_card_font_newsreader
    "caveat" -> MR.string.profile_card_font_caveat
    "archivo-black" -> MR.string.profile_card_font_archivo_black
    "space-mono" -> MR.string.profile_card_font_space_mono
    else -> MR.string.profile_card_font_montserrat
}

private fun shapeLabel(shape: String): StringResource = when (shape) {
    "square" -> MR.string.profile_card_shape_square
    "rounded" -> MR.string.profile_card_shape_rounded
    "ellipse" -> MR.string.profile_card_shape_ellipse
    else -> MR.string.profile_card_shape_circle
}

private fun socialsLabel(style: String): StringResource = when (style) {
    "bar" -> MR.string.profile_card_socials_bar
    "wordmark" -> MR.string.profile_card_socials_wordmark
    "handles" -> MR.string.profile_card_socials_handles
    else -> MR.string.profile_card_socials_glyphs
}

private fun blockLabel(kind: String): StringResource = when (kind) {
    "links" -> MR.string.profile_card_block_links
    "moments" -> MR.string.profile_card_block_moments
    "posts" -> MR.string.profile_card_block_posts
    else -> MR.string.profile_card_block_chat
}
