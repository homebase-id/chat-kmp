@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)

package id.homebase.core.ui.screens.card

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.FormatColorReset
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import id.homebase.core.widget.connectedButtonShapes
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

private val SWATCH_TARGET = 52.dp
private val SHAPE_GLYPH_SIZE = 24.dp
private val ORDER_ROW_HEIGHT = 48.dp
private val SHAPE_SEGMENT_HEIGHT = 80.dp

/** Every control comes from the design's [CardDesignSpec]; there is no per-design screen. */
@Composable
internal fun CardOptionsPanel(
    design: String,
    overrides: CardOverrides,
    enabled: Boolean,
    onOption: (CardOption, String?) -> Unit,
    onBlockOrder: (List<String>) -> Unit,
    areaHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val spec = CardDesignSpecs.of(design)
    val options = CardOption.entries.filter { spec != null && it in spec.options }
    if (options.isEmpty()) {
        OptionsEmptyState(modifier = modifier.fillMaxWidth().heightIn(min = areaHeight).padding(horizontal = 24.dp))
        return
    }
    var tab by rememberSaveable(design) { mutableIntStateOf(0) }
    val current = options[tab.coerceIn(0, options.lastIndex)]
    val motion = MaterialTheme.motionScheme
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PrimaryScrollableTabRow(
            selectedTabIndex = options.indexOf(current),
            edgePadding = 12.dp,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            divider = {},
        ) {
            options.forEachIndexed { index, option ->
                Tab(
                    selected = option == current,
                    onClick = { tab = index },
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    text = { Text(stringResource(optionLabel(option)), maxLines = 1) },
                )
            }
        }
        AnimatedContent(
            targetState = current,
            transitionSpec = { fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()) },
            modifier = Modifier.fillMaxWidth().height(areaHeight),
            contentAlignment = Alignment.TopStart,
        ) { option ->
            Box(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                when (option) {
                    CardOption.ACCENT -> SwatchRow(overrides.valueOf(option), enabled) { onOption(option, it) }
                    CardOption.BLOCK_ORDER -> BlockOrderList(overrides.blockOrder(), enabled, onBlockOrder)
                    CardOption.PORTRAIT_SHAPE -> ShapeGroup(overrides.valueOf(option), enabled) { onOption(option, it) }
                    else -> ChoiceFlow(
                        choices = choicesOf(option),
                        selected = overrides.valueOf(option),
                        enabled = enabled,
                        styleOf = if (option == CardOption.SOCIALS_STYLE) { _ -> null } else { font -> fontPreviewStyle(font) },
                        onSelect = { onOption(option, it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun OptionsEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer, MaterialShapes.Cookie9Sided.toShape()),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Tune,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(28.dp),
            )
        }
        Text(
            text = stringResource(MR.string.profile_card_options_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ChoiceFlow(
    choices: List<Pair<String, StringResource>>,
    selected: String?,
    enabled: Boolean,
    styleOf: @Composable (String) -> TextStyle?,
    onSelect: (String?) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChoiceToggle(stringResource(MR.string.profile_card_option_default), selected == null, enabled, null) { onSelect(null) }
        choices.forEach { (value, label) ->
            ChoiceToggle(stringResource(label), selected == value, enabled, styleOf(value)) { onSelect(value) }
        }
    }
}

@Composable
private fun ChoiceToggle(label: String, checked: Boolean, enabled: Boolean, style: TextStyle?, onClick: () -> Unit) {
    ToggleButton(
        checked = checked,
        onCheckedChange = { onClick() },
        enabled = enabled,
        shapes = ToggleButtonDefaults.shapes(),
        colors = ToggleButtonDefaults.tonalToggleButtonColors(),
    ) {
        Text(label, style = style ?: MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// A hint of each face from the platform's generic families; the card itself uses the real web font.
@Composable
private fun fontPreviewStyle(font: String): TextStyle {
    val base = MaterialTheme.typography.labelLarge
    return when (font) {
        "newsreader" -> base.copy(fontFamily = FontFamily.Serif)
        "caveat" -> base.copy(fontFamily = FontFamily.Cursive)
        "archivo-black" -> base.copy(fontWeight = FontWeight.Black)
        "space-mono" -> base.copy(fontFamily = FontFamily.Monospace)
        "montserrat-alt" -> base.copy(fontWeight = FontWeight.SemiBold)
        else -> base
    }
}

@Composable
private fun SwatchRow(selected: String?, enabled: Boolean, onSelect: (String?) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Swatch(
            fill = MaterialTheme.colorScheme.surfaceContainerHighest,
            selected = selected == null,
            enabled = enabled,
            description = stringResource(MR.string.profile_card_option_default),
            onClick = { onSelect(null) },
        ) {
            Icon(
                imageVector = Icons.Outlined.FormatColorReset,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
        CardDesignSpecs.ACCENT_SWATCHES.forEach { hex ->
            Swatch(
                fill = hexColor(hex),
                selected = selected.equals(hex, ignoreCase = true),
                enabled = enabled,
                description = stringResource(MR.string.profile_card_option_accent_swatch, hex),
                onClick = { onSelect(hex) },
            )
        }
    }
}

@Composable
private fun Swatch(
    fill: Color,
    selected: Boolean,
    enabled: Boolean,
    description: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit = {},
) {
    val motion = MaterialTheme.motionScheme
    val morph = remember { Morph(MaterialShapes.Circle, MaterialShapes.Cookie9Sided) }
    val progress by animateFloatAsState(if (selected) 1f else 0f, motion.fastSpatialSpec())
    val ring = MaterialTheme.colorScheme.onSurface
    val shape = MorphShape(morph, progress)
    Box(
        modifier = Modifier
            .size(SWATCH_TARGET + 12.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(SWATCH_TARGET)
                .border(2.dp, ring.copy(alpha = progress), CircleShape)
                .padding(4.dp)
                .background(fill, shape)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 1f - progress), shape),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

private class MorphShape(private val morph: Morph, private val progress: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = Path()
        var first = true
        morph.forEachCubic(progress) { cubic ->
            if (first) {
                path.moveTo(cubic.anchor0X, cubic.anchor0Y)
                first = false
            }
            path.cubicTo(cubic.control0X, cubic.control0Y, cubic.control1X, cubic.control1Y, cubic.anchor1X, cubic.anchor1Y)
        }
        path.close()
        path.transform(Matrix().apply { scale(size.width, size.height) })
        return Outline.Generic(path)
    }
}

@Composable
private fun ShapeGroup(selected: String?, enabled: Boolean, onSelect: (String?) -> Unit) {
    val shapes = CardDesignSpecs.PORTRAIT_SHAPES
    val count = shapes.size + 1
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        ToggleButton(
            checked = selected == null,
            onCheckedChange = { onSelect(null) },
            enabled = enabled,
            shapes = connectedButtonShapes(0, count),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
            modifier = Modifier.weight(1.3f).heightIn(min = SHAPE_SEGMENT_HEIGHT),
        ) {
            Text(stringResource(MR.string.profile_card_option_default), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        shapes.forEachIndexed { index, shape ->
            ToggleButton(
                checked = selected == shape,
                onCheckedChange = { onSelect(shape) },
                enabled = enabled,
                shapes = connectedButtonShapes(index + 1, count),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp),
                modifier = Modifier.weight(1f).heightIn(min = SHAPE_SEGMENT_HEIGHT),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ShapeGlyph(shape)
                    Text(
                        text = stringResource(shapeLabel(shape)),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ShapeGlyph(shape: String) {
    val color = LocalContentColor.current
    Canvas(modifier = Modifier.size(SHAPE_GLYPH_SIZE)) {
        drawPortraitShape(shape, color)
    }
}

private fun DrawScope.drawPortraitShape(shape: String, color: Color) {
    when (shape) {
        "square" -> drawRect(color)
        "rounded" -> drawRoundRect(color, cornerRadius = CornerRadius(size.minDimension * 0.28f))
        "ellipse" -> drawOval(color, topLeft = Offset(size.width * 0.18f, 0f), size = Size(size.width * 0.64f, size.height))
        else -> drawCircle(color)
    }
}

@Composable
private fun BlockOrderList(order: List<String>, enabled: Boolean, onChange: (List<String>) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        order.forEachIndexed { index, kind ->
            val name = stringResource(blockLabel(kind))
            val big = 16.dp
            val small = 4.dp
            val shape = RoundedCornerShape(
                topStart = if (index == 0) big else small,
                topEnd = if (index == 0) big else small,
                bottomStart = if (index == order.lastIndex) big else small,
                bottomEnd = if (index == order.lastIndex) big else small,
            )
            Surface(shape = shape, color = colors.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.heightIn(min = ORDER_ROW_HEIGHT).padding(start = 12.dp, end = 4.dp),
                ) {
                    Box(
                        modifier = Modifier.size(28.dp).background(colors.secondaryContainer, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = (index + 1).toString(),
                            style = MaterialTheme.typography.labelLargeEmphasized,
                            color = colors.onSecondaryContainer,
                        )
                    }
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 12.dp),
                    )
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
