@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.card

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.LineHeightStyle
import id.homebase.resources.profile_card_colours_blue
import id.homebase.resources.profile_card_colours_blueprint
import id.homebase.resources.profile_card_colours_blush
import id.homebase.resources.profile_card_colours_butter
import id.homebase.resources.profile_card_colours_charcoal
import id.homebase.resources.profile_card_colours_cloud
import id.homebase.resources.profile_card_colours_cobalt
import id.homebase.resources.profile_card_colours_forest
import id.homebase.resources.profile_card_colours_graphite
import id.homebase.resources.profile_card_colours_ink
import id.homebase.resources.profile_card_colours_kraft
import id.homebase.resources.profile_card_colours_lavender
import id.homebase.resources.profile_card_colours_lemon
import id.homebase.resources.profile_card_colours_lilac
import id.homebase.resources.profile_card_colours_midnight
import id.homebase.resources.profile_card_colours_mint
import id.homebase.resources.profile_card_colours_mist
import id.homebase.resources.profile_card_colours_mono
import id.homebase.resources.profile_card_colours_moss
import id.homebase.resources.profile_card_colours_navy
import id.homebase.resources.profile_card_colours_night
import id.homebase.resources.profile_card_colours_oxblood
import id.homebase.resources.profile_card_colours_paper
import id.homebase.resources.profile_card_colours_peach
import id.homebase.resources.profile_card_colours_pine
import id.homebase.resources.profile_card_colours_plum
import id.homebase.resources.profile_card_colours_purple
import id.homebase.resources.profile_card_colours_rose
import id.homebase.resources.profile_card_colours_rust
import id.homebase.resources.profile_card_colours_sage
import id.homebase.resources.profile_card_colours_sand
import id.homebase.resources.profile_card_colours_sky
import id.homebase.resources.profile_card_colours_slate
import id.homebase.resources.profile_card_colours_terracotta
import id.homebase.resources.profile_card_colours_wine
import id.homebase.resources.profile_card_tool_accent
import id.homebase.resources.profile_card_tool_heading
import id.homebase.resources.profile_card_tool_body
import id.homebase.resources.profile_card_tool_portrait
import id.homebase.resources.profile_card_tool_socials
import id.homebase.resources.profile_card_tool_order
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.ViewAgenda
import id.homebase.resources.profile_card_option_colours
import id.homebase.resources.profile_card_option_colours_swatch
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Title
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.FilledTonalToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import id.homebase.core.widget.MorphShape
import id.homebase.core.widget.connectedButtonShapes
import id.homebase.resources.MR
import id.homebase.resources.card_font_archivo_black
import id.homebase.resources.card_font_caveat
import id.homebase.resources.card_font_newsreader
import id.homebase.resources.card_font_space_mono
import id.homebase.resources.montserrat_alternates_light
import id.homebase.resources.montserrat_regular
import id.homebase.resources.profile_card_block_chat
import id.homebase.resources.profile_card_block_links
import id.homebase.resources.profile_card_block_moments
import id.homebase.resources.profile_card_font_archivo_black
import id.homebase.resources.profile_card_font_caveat
import id.homebase.resources.profile_card_font_montserrat
import id.homebase.resources.profile_card_font_montserrat_alt
import id.homebase.resources.profile_card_font_newsreader
import id.homebase.resources.profile_card_font_space_mono
import id.homebase.resources.profile_card_option_accent
import id.homebase.resources.profile_card_option_accent_swatch
import id.homebase.resources.profile_card_option_block_order
import id.homebase.resources.profile_card_option_block_style
import id.homebase.resources.profile_card_tool_style
import id.homebase.resources.profile_card_tool_presets
import id.homebase.resources.profile_card_option_presets
import id.homebase.resources.profile_card_presentation_bare
import id.homebase.resources.profile_card_presentation_boxed
import id.homebase.resources.profile_card_presentation_row
import id.homebase.resources.profile_card_presentation_button
import id.homebase.resources.profile_card_option_default
import id.homebase.resources.profile_card_option_display_font
import id.homebase.resources.profile_card_option_drag
import id.homebase.resources.profile_card_option_move_down
import id.homebase.resources.profile_card_option_move_up
import id.homebase.resources.profile_card_option_portrait_shape
import id.homebase.resources.profile_card_option_socials_style
import id.homebase.resources.profile_card_option_text_font
import id.homebase.resources.profile_card_shape_circle
import id.homebase.resources.profile_card_shape_ellipse
import id.homebase.resources.profile_card_shape_rounded
import id.homebase.resources.profile_card_shape_square
import id.homebase.resources.profile_card_socials_bar
import id.homebase.resources.profile_card_socials_glyphs
import id.homebase.resources.profile_card_socials_handles
import id.homebase.resources.profile_card_socials_wordmark
import kotlin.uuid.ExperimentalUuidApi
import org.jetbrains.compose.resources.Font
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import sh.calvin.reorderable.ReorderableRow
import id.homebase.core.widget.WithTooltip

internal val TOOLBAR_HEIGHT = 64.dp
internal val CHIP_HEIGHT = 40.dp
// Past this text scale, controls stop growing and labels get their own line.
internal const val LARGE_TEXT_SCALE = 1.3f
private val SWATCH_SIZE = 40.dp
private val SWATCH_TARGET = 52.dp
private val INK_DOT_SIZE = 14.dp
private val ACCENT_DOT_SIZE = 24.dp
private val SHAPE_SEGMENT_MIN_WIDTH = 56.dp
private val GLYPH_SIZE = 20.dp
private val TOOL_SIZE = 48.dp
private val TOOL_MIN_PADDING = 4.dp
private val TOOL_PADDING = 10.dp
private val TOOL_LABEL_GAP = 4.dp
private val FADE_LENGTH = 24.dp
private val OPTION_SIDE_INSET = 16.dp
// Seven tools plus the picked label only fit 393dp at this inset.
private val TOOLBAR_SIDE_INSET = 0.dp
private val SWATCH_ROW_INSET = OPTION_SIDE_INSET - 4.dp
private val CAPTION_INSET = 24.dp
private const val REVEAL_MARGIN = 0.6f
private val BLOCK_TILE_SPACING = 6.dp
private val BLOCK_TILE_CORNER = 12.dp

@Composable
internal fun CardOptionsPanel(
    design: String,
    overrides: CardOverrides,
    enabled: Boolean,
    onOption: (CardOption, String?) -> Unit,
    onBlockOrder: (List<String>) -> Unit,
    gap: Dp,
    hasLinks: Boolean,
    styles: List<ResolvedCardStyle>,
    custom: ResolvedCardStyle?,
    onStyle: (ResolvedCardStyle) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spec = CardDesignSpecs.of(design)
    val orderable = spec?.reorderable(hasLinks).orEmpty()
    val options = CardOption.entries.filter { spec != null && it in spec.options && (it != CardOption.BLOCK_ORDER || orderable.size > 1) }
    val tools = remember(options, styles.isEmpty()) {
        listOfNotNull(PRESETS_TOOL.takeIf { styles.isNotEmpty() }) + options.map { PanelTool(it.name, optionIcon(it), toolLabel(it), optionLabel(it)) }
    }
    var picked by rememberSaveable(design) { mutableStateOf(tools.firstOrNull()?.key.orEmpty()) }
    val current = tools.firstOrNull { it.key == picked } ?: tools.firstOrNull() ?: return
    val motion = MaterialTheme.motionScheme
    val chipHeight = CHIP_HEIGHT * LocalDensity.current.fontScale.coerceIn(1f, LARGE_TEXT_SCALE)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
        AnimatedContent(
            targetState = current.key,
            transitionSpec = {
                (fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.defaultSpatialSpec(), initialScale = 0.96f))
                    .togetherWith(fadeOut(motion.fastEffectsSpec()))
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.TopStart,
        ) { shown ->
            // The picked tool names itself in the group below, so the control stands alone here.
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val option = options.firstOrNull { it.name == shown }
                if (option == null) CardStyleRow(styles, overrides, design, enabled, onStyle, custom = custom)
                else OptionControl(option, design, overrides, enabled, chipHeight, orderable, onOption, onBlockOrder)
            }
        }
        OptionToolbar(
            tools = tools,
            current = current,
            onPick = { picked = it.key },
            modifier = Modifier.fillMaxWidth().height(TOOLBAR_HEIGHT).padding(horizontal = TOOLBAR_SIDE_INSET),
        )
    }
}

@Composable
private fun OptionControl(
    option: CardOption,
    design: String,
    overrides: CardOverrides,
    enabled: Boolean,
    chipHeight: Dp,
    orderable: List<String>,
    onOption: (CardOption, String?) -> Unit,
    onBlockOrder: (List<String>) -> Unit,
) {
    when (option) {
        CardOption.COLOURS -> SchemeRow(design, overrides.palette, enabled) { onOption(option, it) }
        CardOption.ACCENT -> SwatchRow(design, overrides.valueOf(CardOption.COLOURS), overrides.valueOf(option), enabled) { onOption(option, it) }
        CardOption.BLOCK_ORDER -> BlockOrderRow(overrides.blockOrder().filter { it in orderable }, enabled, chipHeight) {
            onBlockOrder(overrides.withShownBlockOrder(orderable, it).blockOrder())
        }
        // Shapes read as glyphs, so the group stays one row of icons; the name is spoken, not printed.
        CardOption.PORTRAIT_SHAPE -> SegmentedChoices(
            values = listOf(null) + CardDesignSpecs.PORTRAIT_SHAPES,
            selected = overrides.valueOf(option),
            enabled = enabled,
            chipHeight = chipHeight,
            minSegmentWidth = SHAPE_SEGMENT_MIN_WIDTH,
            description = { stringResource(it?.let(::shapeLabel) ?: MR.string.profile_card_option_default) },
            onSelect = { onOption(option, it) },
        ) { shape ->
            if (shape == null) DefaultGlyph() else ShapeGlyph(shape)
        }
        CardOption.BLOCK_STYLE -> SegmentedChoices(
            values = listOf(null) + CardDesignSpecs.PRESENTATIONS,
            selected = overrides.valueOf(option),
            enabled = enabled,
            chipHeight = chipHeight,
            onSelect = { onOption(option, it) },
        ) { style ->
            SegmentLabel(stringResource(style?.let(::presentationLabel) ?: MR.string.profile_card_option_default))
        }
        CardOption.SOCIALS_STYLE -> SegmentedChoices(
            values = listOf(null) + CardDesignSpecs.SOCIALS_STYLES,
            selected = overrides.valueOf(option),
            enabled = enabled,
            chipHeight = chipHeight,
            onSelect = { onOption(option, it) },
        ) { style ->
            SegmentLabel(stringResource(style?.let(::socialsLabel) ?: MR.string.profile_card_option_default))
        }
        // A font picker shows each face by setting its own name in it; the name is never cut.
        CardOption.DISPLAY_FONT, CardOption.TEXT_FONT -> SegmentedChoices(
            values = listOf(null) + CardDesignSpecs.FONTS,
            selected = overrides.valueOf(option),
            enabled = enabled,
            chipHeight = chipHeight,
            connected = false,
            onSelect = { onOption(option, it) },
        ) { font ->
            Text(
                text = stringResource(font?.let(::fontLabel) ?: MR.string.profile_card_option_default),
                style = fontPreviewStyle(font),
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

// When every tool fits it sits still and whole, naming the picked one, with what room is left around that label; otherwise the row scrolls.
@Composable
private fun OptionToolbar(
    tools: List<PanelTool>,
    current: PanelTool,
    onPick: (PanelTool) -> Unit,
    modifier: Modifier = Modifier,
) {
    val count = tools.size
    val labels = tools.map { stringResource(it.label) }
    val descriptions = tools.map { stringResource(it.description) }
    val icons = tools.map { it.icon }
    val pickedIndex = tools.indexOf(current)
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelMedium
    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val widestLabel = with(density) { labels.maxOf { measurer.measure(it, labelStyle).size.width }.toDp() }
        val rowWidth = TOOL_SIZE * (count - 1) + GLYPH_SIZE + TOOL_LABEL_GAP + widestLabel + ButtonGroupDefaults.ConnectedSpaceBetween * (count - 1)
        val fits = rowWidth + TOOL_MIN_PADDING * 2 <= maxWidth
        val padding = if (fits) ((maxWidth - rowWidth) / 2).coerceIn(TOOL_MIN_PADDING, TOOL_PADDING) else TOOL_PADDING
        val tools: @Composable RowScope.() -> Unit = {
            for (index in 0 until count) {
                ToolItem(
                    icon = icons[index],
                    label = labels[index],
                    description = descriptions[index],
                    selected = index == pickedIndex,
                    shapes = connectedButtonShapes(index, count),
                    onClick = { onPick(tools[index]) },
                    padding = padding,
                )
            }
        }
        val arrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween, Alignment.CenterHorizontally)
        if (fits) {
            Row(
                modifier = Modifier.fillMaxWidth().selectableGroup(),
                horizontalArrangement = arrangement,
                verticalAlignment = Alignment.CenterVertically,
                content = tools,
            )
        } else {
            ScrollableChoiceRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = OPTION_SIDE_INSET,
                horizontalArrangement = arrangement,
                verticalAlignment = Alignment.CenterVertically,
                content = tools,
            )
        }
    }
}

@Composable
private fun ToolItem(
    icon: ImageVector,
    label: String,
    description: String,
    selected: Boolean,
    shapes: ToggleButtonShapes,
    onClick: () -> Unit,
    padding: Dp,
) {
    val motion = MaterialTheme.motionScheme
    WithTooltip(description) {
        ToggleButton(
            checked = selected,
            onCheckedChange = { onClick() },
            shapes = shapes,
            // Quiet at rest and a tonal pill when picked, so the picked tool never outweighs the step's own action.
            colors = FilledTonalToggleButtonDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
            contentPadding = PaddingValues(horizontal = padding),
            modifier = Modifier
                .revealWhenSelected(selected)
                .height(TOOL_SIZE)
                .widthIn(min = TOOL_SIZE)
                .clearAndSetSemantics {
                    contentDescription = description
                    role = Role.Tab
                    this.selected = selected
                    onClick { onClick(); true }
                },
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(GLYPH_SIZE))
            AnimatedVisibility(
                visible = selected,
                enter = expandHorizontally(motion.fastSpatialSpec()) + fadeIn(motion.fastEffectsSpec()),
                exit = shrinkHorizontally(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(start = TOOL_LABEL_GAP),
                )
            }
        }
    }
}

private val LOOSE_CHOICE_SHAPES = ToggleButtonShapes(
    shape = RoundedCornerShape(12.dp),
    pressedShape = RoundedCornerShape(8.dp),
    checkedShape = CircleShape,
)

/**
 * Pick-one chips. [connected] makes them one M3 connected group (rounded ends, tight inner corners, the checked one springs to a
 * pill); otherwise each is its own pill. Chips are as wide as their content; when they don't fit, the row scrolls under faded edges.
 */
@Composable
private fun SegmentedChoices(
    values: List<String?>,
    selected: String?,
    enabled: Boolean,
    chipHeight: Dp,
    onSelect: (String?) -> Unit,
    connected: Boolean = true,
    minSegmentWidth: Dp = 0.dp,
    description: (@Composable (String?) -> String)? = null,
    content: @Composable RowScope.(String?) -> Unit,
) {
    val spacing = if (connected) ButtonGroupDefaults.ConnectedSpaceBetween else 8.dp
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // A connected group that fits shares the row out evenly, so it reads as one control rather than loose buttons.
        val even = (maxWidth - OPTION_SIDE_INSET * 2 - spacing * (values.size - 1)) / values.size
        val segmentWidth = if (connected) maxOf(minSegmentWidth, even) else 0.dp
        ScrollableChoiceRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = OPTION_SIDE_INSET,
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            values.forEachIndexed { index, value ->
                val label = description?.invoke(value)
                ToggleButton(
                    checked = value == selected,
                    onCheckedChange = { onSelect(value) },
                    enabled = enabled,
                    shapes = if (connected) connectedButtonShapes(index, values.size) else LOOSE_CHOICE_SHAPES,
                    colors = ToggleButtonDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier
                        .revealWhenSelected(value == selected)
                        .widthIn(min = segmentWidth)
                        .height(chipHeight)
                        .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier),
                ) { content(value) }
            }
        }
    }
}

@Composable
private fun SegmentLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        textAlign = TextAlign.Center,
        maxLines = 1,
        softWrap = false,
    )
}

@Composable
private fun DefaultGlyph() {
    Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(GLYPH_SIZE))
}

// Each name set in the card's own face (Latin subsets bundled from the web card's fonts), so look-alike names still differ.
@Composable
private fun fontPreviewStyle(font: String?): TextStyle {
    val base = MaterialTheme.typography.titleMedium
    val face = when (font) {
        "montserrat" -> MR.font.montserrat_regular
        "montserrat-alt" -> MR.font.montserrat_alternates_light
        "newsreader" -> MR.font.card_font_newsreader
        "caveat" -> MR.font.card_font_caveat
        "archivo-black" -> MR.font.card_font_archivo_black
        "space-mono" -> MR.font.card_font_space_mono
        else -> return base.copy(lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both))
    }
    val font = Font(face)
    val family = remember(font) { FontFamily(font) }
    // Each face has its own ascent; centring the trimmed line keeps every name on the chips' common midline.
    return base.copy(fontFamily = family, lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both))
}

// Each swatch is the scheme in small: its ground with an ink dot, so the pairing is judged before it is picked.
@Composable
private fun SchemeRow(design: String, palette: CardPalette?, enabled: Boolean, onSelect: (String?) -> Unit) {
    val schemes = CardDesignSpecs.of(design)?.schemes.orEmpty()
    val selected = palette?.ground
    val saved = selected?.takeIf { ground -> schemes.none { it.ground.equals(ground, ignoreCase = true) } }
    ScrollableChoiceRow(modifier = Modifier.fillMaxWidth(), contentPadding = SWATCH_ROW_INSET, horizontalArrangement = Arrangement.Center) {
        Swatch(
            fill = Color(CardDesign.baseArgb(design)),
            selected = selected == null,
            enabled = enabled,
            description = stringResource(MR.string.profile_card_option_default),
            onClick = { onSelect(null) },
        ) { InkDot(Color(CardDesign.inkArgb(design))) }
        // A saved ground outside this design's set still shows as what the card is wearing, never as Default.
        saved?.let { ground ->
            Swatch(
                fill = Color(hexToArgb(ground)),
                selected = true,
                enabled = enabled,
                description = stringResource(MR.string.profile_card_option_colours_swatch, ground),
                onClick = {},
            ) { InkDot(Color(palette.ink?.let(::hexToArgb) ?: CardDesign.inkArgb(design))) }
        }
        schemes.forEach { scheme ->
            Swatch(
                fill = Color(hexToArgb(scheme.ground)),
                selected = scheme.ground.equals(selected, ignoreCase = true),
                enabled = enabled,
                description = stringResource(MR.string.profile_card_option_colours_swatch, stringResource(schemeLabel(scheme.id))),
                onClick = { onSelect(scheme.ground) },
            ) { InkDot(Color(hexToArgb(scheme.ink))) }
        }
    }
}

@Composable
private fun InkDot(ink: Color, size: Dp = INK_DOT_SIZE, edge: Color = Color.Transparent) {
    Box(Modifier.size(size).background(ink, CircleShape).border(1.dp, edge, CircleShape))
}

// Accents are curated per ground and vanish on the app's own surface, so each is shown on the scheme it is meant for.
@Composable
private fun SwatchRow(design: String, colours: String?, selected: String?, enabled: Boolean, onSelect: (String?) -> Unit) {
    val scheme = CardDesignSpecs.schemeFor(design, colours)
    val ground = Color(scheme?.let { hexToArgb(it.ground) } ?: CardDesign.baseArgb(design))
    val ink = Color(scheme?.let { hexToArgb(it.ink) } ?: CardDesign.inkArgb(design))
    val accents = CardDesignSpecs.accentsFor(design, colours)
    val saved = selected?.takeIf { s -> accents.none { it.equals(s, ignoreCase = true) } }
    val values = listOf<String?>(null) + listOfNotNull(saved) + accents
    ScrollableChoiceRow(modifier = Modifier.fillMaxWidth(), contentPadding = SWATCH_ROW_INSET, horizontalArrangement = Arrangement.Center) {
        values.forEach { hex ->
            if (hex == null) {
                Swatch(
                    fill = ground,
                    selected = selected == null,
                    enabled = enabled,
                    description = stringResource(MR.string.profile_card_option_default),
                    onClick = { onSelect(null) },
                ) { NoColourSlash(ink) }
            } else {
                Swatch(
                    fill = ground,
                    selected = selected.equals(hex, ignoreCase = true),
                    enabled = enabled,
                    description = stringResource(MR.string.profile_card_option_accent_swatch, hex),
                    onClick = { onSelect(hex) },
                    // The ground's ink rings the dot, so a saved accent close to the ground stays visible.
                ) { InkDot(Color(hexToArgb(hex)), ACCENT_DOT_SIZE, edge = ink.copy(alpha = 0.45f)) }
            }
        }
    }
}

@Composable
private fun NoColourSlash(ink: Color) {
    Canvas(modifier = Modifier.fillMaxSize().padding(10.dp)) {
        drawLine(
            color = ink,
            start = Offset(size.width, 0f),
            end = Offset(0f, size.height),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
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
    val ring = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    val shape = MorphShape(morph, progress)
    Box(
        modifier = Modifier
            .size(SWATCH_TARGET)
            .revealWhenSelected(selected)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(SWATCH_SIZE + 8.dp)
                .border(2.5.dp, ring.copy(alpha = progress), shape)
                .padding(4.dp)
                .clip(shape)
                .background(fill)
                .border(1.dp, outline.copy(alpha = 0.5f * (1f - progress)), shape),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

@Composable
private fun ShapeGlyph(shape: String) {
    val color = LocalContentColor.current
    Canvas(modifier = Modifier.size(GLYPH_SIZE)) {
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

// The card's sections as tiles in the order they appear, dragged along the row; move up/down stay as accessibility actions.
// Tiles keep their full label at a fixed size; when they don't fit, the row scrolls instead of shrinking them.
@Composable
private fun BlockOrderRow(order: List<String>, enabled: Boolean, tileHeight: Dp, onChange: (List<String>) -> Unit) {
    val scroll = rememberScrollState()
    Box(modifier = Modifier.fillMaxWidth()) {
        // The list must be a new immutable instance per change: ReorderableRow keys its offsets on the instance.
        ReorderableRow(
            list = order,
            onSettle = { from, to -> if (from != to) onChange(order.toMutableList().apply { add(to, removeAt(from)) }) },
            modifier = Modifier.fillMaxWidth().fadingEdges(scroll).horizontalScroll(scroll).padding(horizontal = OPTION_SIDE_INSET),
            horizontalArrangement = Arrangement.spacedBy(BLOCK_TILE_SPACING, Alignment.CenterHorizontally),
        ) { index, kind, dragging ->
            key(kind) {
                ReorderableItem {
                    val name = stringResource(blockLabel(kind))
                    val moveUp = stringResource(MR.string.profile_card_option_move_up, name)
                    val moveDown = stringResource(MR.string.profile_card_option_move_down, name)
                    val drag = stringResource(MR.string.profile_card_option_drag, name)
                    val first = index == 0
                    val last = index == order.lastIndex
                    val colors = MaterialTheme.colorScheme
                    val motion = MaterialTheme.motionScheme
                    val elevation by animateDpAsState(if (dragging) 6.dp else 1.dp, motion.fastSpatialSpec())
                    val lift by animateFloatAsState(if (dragging) 1.06f else 1f, motion.fastSpatialSpec())
                    val corner by animateDpAsState(if (dragging) tileHeight / 2 else BLOCK_TILE_CORNER, motion.fastSpatialSpec())
                    Surface(
                        shape = RoundedCornerShape(corner),
                        color = if (dragging) colors.secondaryContainer else colors.surfaceContainerHighest,
                        contentColor = if (dragging) colors.onSecondaryContainer else colors.onSurface,
                        shadowElevation = elevation,
                        modifier = Modifier
                            .height(tileHeight)
                            .graphicsLayer {
                                scaleX = lift
                                scaleY = lift
                            }
                            .draggableHandle(enabled = enabled)
                            .clearAndSetSemantics {
                                contentDescription = "$name, $drag"
                                customActions = buildList {
                                    if (enabled && !first) {
                                        add(CustomAccessibilityAction(moveUp) { onChange(order.swap(index, index - 1)); true })
                                    }
                                    if (enabled && !last) {
                                        add(CustomAccessibilityAction(moveDown) { onChange(order.swap(index, index + 1)); true })
                                    }
                                }
                            },
                    ) {
                        BlockTile(name = name)
                    }
                }
            }
        }
    }
}

@Composable
private fun BlockTile(name: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        modifier = Modifier.padding(start = 8.dp, end = 14.dp),
    ) {
        Icon(
            Icons.Outlined.DragIndicator,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Text(text = name, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
    }
}

// Scrolls a picked item into view with some of its neighbours, both on first show and when picked.
@Composable
private fun Modifier.revealWhenSelected(selected: Boolean): Modifier {
    val requester = remember { BringIntoViewRequester() }
    var width by remember { mutableIntStateOf(0) }
    LaunchedEffect(selected, width) {
        if (selected && width > 0) requester.bringIntoView(Rect(-width * REVEAL_MARGIN, 0f, width * (1 + REVEAL_MARGIN), 1f))
    }
    return bringIntoViewRequester(requester).onSizeChanged { width = it.width }
}

@Composable
internal fun ScrollableChoiceRow(
    modifier: Modifier = Modifier,
    contentPadding: Dp = 0.dp,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    content: @Composable RowScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    Row(
        modifier = modifier
            .fadingEdges(scroll)
            .horizontalScroll(scroll)
            .padding(horizontal = contentPadding)
            .selectableGroup(),
        horizontalArrangement = horizontalArrangement,
        verticalAlignment = verticalAlignment,
        content = content,
    )
}

// Masks content under a gradient at whichever ends can still scroll, so hidden rows read as "more this way".
@Composable
internal fun Modifier.fadingEdges(scroll: ScrollState, vertical: Boolean = false): Modifier {
    val fade = with(LocalDensity.current) { FADE_LENGTH.toPx() }
    val rtl = !vertical && LocalLayoutDirection.current == LayoutDirection.Rtl
    return graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val atStart = scroll.value > 0
            val atEnd = scroll.value < scroll.maxValue
            val extent = if (vertical) size.height else size.width
            fun mask(fromEdge: Float, towardInside: Float) = if (vertical) {
                Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = fromEdge, endY = towardInside)
            } else {
                Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = fromEdge, endX = towardInside)
            }
            // Horizontal scroll runs from the end side in RTL, so "start" is the right edge there.
            val startEdge = if (rtl) extent else 0f
            val endEdge = if (rtl) 0f else extent
            val inward = { edge: Float -> if (edge == 0f) fade else extent - fade }
            if (atStart) drawRect(mask(startEdge, inward(startEdge)), blendMode = BlendMode.DstIn)
            if (atEnd) drawRect(mask(endEdge, inward(endEdge)), blendMode = BlendMode.DstIn)
        }
}

private fun List<String>.swap(a: Int, b: Int): List<String> {
    val moved = toMutableList()
    moved[a] = this[b]
    moved[b] = this[a]
    return moved
}

private data class PanelTool(val key: String, val icon: ImageVector, val label: StringResource, val description: StringResource)

private val PRESETS_TOOL = PanelTool("presets", Icons.Outlined.Layers, MR.string.profile_card_tool_presets, MR.string.profile_card_option_presets)

private fun optionLabel(option: CardOption): StringResource = when (option) {
    CardOption.COLOURS -> MR.string.profile_card_option_colours
    CardOption.ACCENT -> MR.string.profile_card_option_accent
    CardOption.DISPLAY_FONT -> MR.string.profile_card_option_display_font
    CardOption.TEXT_FONT -> MR.string.profile_card_option_text_font
    CardOption.PORTRAIT_SHAPE -> MR.string.profile_card_option_portrait_shape
    CardOption.SOCIALS_STYLE -> MR.string.profile_card_option_socials_style
    CardOption.BLOCK_ORDER -> MR.string.profile_card_option_block_order
    CardOption.BLOCK_STYLE -> MR.string.profile_card_option_block_style
}

private fun toolLabel(option: CardOption): StringResource = when (option) {
    CardOption.COLOURS -> MR.string.profile_card_option_colours
    CardOption.ACCENT -> MR.string.profile_card_tool_accent
    CardOption.DISPLAY_FONT -> MR.string.profile_card_tool_heading
    CardOption.TEXT_FONT -> MR.string.profile_card_tool_body
    CardOption.PORTRAIT_SHAPE -> MR.string.profile_card_tool_portrait
    CardOption.SOCIALS_STYLE -> MR.string.profile_card_tool_socials
    CardOption.BLOCK_ORDER -> MR.string.profile_card_tool_order
    CardOption.BLOCK_STYLE -> MR.string.profile_card_tool_style
}

private fun optionIcon(option: CardOption): ImageVector = when (option) {
    CardOption.COLOURS -> Icons.Outlined.Palette
    CardOption.ACCENT -> Icons.Outlined.Colorize
    CardOption.DISPLAY_FONT -> Icons.Outlined.Title
    CardOption.TEXT_FONT -> Icons.Outlined.FormatSize
    CardOption.PORTRAIT_SHAPE -> Icons.Outlined.AccountCircle
    CardOption.SOCIALS_STYLE -> Icons.Outlined.AlternateEmail
    CardOption.BLOCK_ORDER -> Icons.Outlined.ViewAgenda
    CardOption.BLOCK_STYLE -> Icons.Outlined.Style
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

private fun presentationLabel(style: String): StringResource = when (style) {
    "boxed" -> MR.string.profile_card_presentation_boxed
    "row" -> MR.string.profile_card_presentation_row
    "button" -> MR.string.profile_card_presentation_button
    else -> MR.string.profile_card_presentation_bare
}

private fun blockLabel(kind: String): StringResource = when (kind) {
    "links" -> MR.string.profile_card_block_links
    "moments" -> MR.string.profile_card_block_moments
    else -> MR.string.profile_card_block_chat
}

internal fun schemeLabel(id: String): StringResource = when (id) {
    "wine" -> MR.string.profile_card_colours_wine
    "pine" -> MR.string.profile_card_colours_pine
    "cobalt" -> MR.string.profile_card_colours_cobalt
    "rust" -> MR.string.profile_card_colours_rust
    "slate" -> MR.string.profile_card_colours_slate
    "plum" -> MR.string.profile_card_colours_plum
    "forest" -> MR.string.profile_card_colours_forest
    "terracotta" -> MR.string.profile_card_colours_terracotta
    "charcoal" -> MR.string.profile_card_colours_charcoal
    "sky" -> MR.string.profile_card_colours_sky
    "butter" -> MR.string.profile_card_colours_butter
    "blush" -> MR.string.profile_card_colours_blush
    "purple" -> MR.string.profile_card_colours_purple
    "sage" -> MR.string.profile_card_colours_sage
    "mist" -> MR.string.profile_card_colours_mist
    "rose" -> MR.string.profile_card_colours_rose
    "lilac" -> MR.string.profile_card_colours_lilac
    "kraft" -> MR.string.profile_card_colours_kraft
    "night" -> MR.string.profile_card_colours_night
    "mono" -> MR.string.profile_card_colours_mono
    "blue" -> MR.string.profile_card_colours_blue
    "ink" -> MR.string.profile_card_colours_ink
    "moss" -> MR.string.profile_card_colours_moss
    "oxblood" -> MR.string.profile_card_colours_oxblood
    "graphite" -> MR.string.profile_card_colours_graphite
    "paper" -> MR.string.profile_card_colours_paper
    "blueprint" -> MR.string.profile_card_colours_blueprint
    "sand" -> MR.string.profile_card_colours_sand
    "cloud" -> MR.string.profile_card_colours_cloud
    "mint" -> MR.string.profile_card_colours_mint
    "peach" -> MR.string.profile_card_colours_peach
    "lavender" -> MR.string.profile_card_colours_lavender
    "navy" -> MR.string.profile_card_colours_navy
    "lemon" -> MR.string.profile_card_colours_lemon
    else -> MR.string.profile_card_colours_midnight
}
