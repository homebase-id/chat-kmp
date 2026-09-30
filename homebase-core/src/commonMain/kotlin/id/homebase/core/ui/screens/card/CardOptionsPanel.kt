@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package id.homebase.core.ui.screens.card

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Reorder
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Title
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ViewStream
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import id.homebase.resources.profile_card_option_drag
import id.homebase.resources.profile_card_option_move_down
import id.homebase.resources.profile_card_option_move_up
import id.homebase.resources.profile_card_option_portrait_shape
import id.homebase.resources.profile_card_option_socials_style
import id.homebase.resources.profile_card_option_text_font
import id.homebase.resources.profile_card_options_empty
import id.homebase.resources.profile_card_options_empty_title
import id.homebase.resources.profile_card_shape_circle
import id.homebase.resources.profile_card_shape_ellipse
import id.homebase.resources.profile_card_shape_rounded
import id.homebase.resources.profile_card_shape_square
import id.homebase.resources.profile_card_socials_bar
import id.homebase.resources.profile_card_socials_glyphs
import id.homebase.resources.profile_card_socials_handles
import id.homebase.resources.profile_card_socials_wordmark
import id.homebase.resources.profile_card_try_another_design
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import sh.calvin.reorderable.ReorderableRow

internal val TOOLBAR_HEIGHT = 64.dp
internal val SAVE_FAB_SIZE = 56.dp
internal const val MAX_SEGMENT_GROWTH = 1.3f
private val SWATCH_SIZE = 44.dp
private val SWATCH_TARGET = 56.dp
private val FONT_SEGMENT_MIN_WIDTH = 96.dp
private val SOCIALS_SEGMENT_MIN_WIDTH = 72.dp
private val SHAPE_SEGMENT_MIN_WIDTH = 64.dp
private val GLYPH_SIZE = 28.dp
private val TOOL_SIZE = 48.dp
private val FADE_LENGTH = 24.dp
private val OPTION_SIDE_INSET = 16.dp
private val CAPTION_INSET = 24.dp
private const val REVEAL_MARGIN = 0.6f
private val MIN_TILE_LABEL_SIZE = 9.sp

/** Every control comes from the design's [CardDesignSpec]; there is no per-design screen. */
@Composable
internal fun CardOptionsPanel(
    design: String,
    overrides: CardOverrides,
    enabled: Boolean,
    onOption: (CardOption, String?) -> Unit,
    onBlockOrder: (List<String>) -> Unit,
    segmentHeight: Dp,
    gap: Dp,
    save: @Composable () -> Unit,
    onTryAnotherDesign: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spec = CardDesignSpecs.of(design)
    val options = CardOption.entries.filter { spec != null && it in spec.options }
    if (options.isEmpty()) {
        OptionsEmptyState(design = design, save = save, onTryAnotherDesign = onTryAnotherDesign, gap = gap, modifier = modifier)
        return
    }
    var picked by rememberSaveable(design) { mutableStateOf(options.first().name) }
    val current = options.firstOrNull { it.name == picked } ?: options.first()
    val motion = MaterialTheme.motionScheme
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
        AnimatedContent(
            targetState = current,
            transitionSpec = {
                (fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.defaultSpatialSpec(), initialScale = 0.96f))
                    .togetherWith(fadeOut(motion.fastEffectsSpec()))
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.TopStart,
        ) { option ->
            // The caption names the tool picked below, so every tool can stay an icon at any text size.
            Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap, Alignment.CenterVertically)) {
                Text(
                    text = stringResource(optionLabel(option)),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = CAPTION_INSET),
                )
                Box(modifier = Modifier.fillMaxWidth().heightIn(min = segmentHeight), contentAlignment = Alignment.Center) {
                    OptionControl(option, overrides, enabled, segmentHeight, onOption, onBlockOrder)
                }
            }
        }
        OptionToolbar(
            options = options,
            current = current,
            onPick = { picked = it.name },
            save = save,
            modifier = Modifier.fillMaxWidth().height(TOOLBAR_HEIGHT).padding(horizontal = OPTION_SIDE_INSET),
        )
    }
}

@Composable
private fun OptionControl(
    option: CardOption,
    overrides: CardOverrides,
    enabled: Boolean,
    segmentHeight: Dp,
    onOption: (CardOption, String?) -> Unit,
    onBlockOrder: (List<String>) -> Unit,
) {
    when (option) {
        CardOption.ACCENT -> SwatchRow(overrides.valueOf(option), enabled) { onOption(option, it) }
        CardOption.BLOCK_ORDER -> BlockOrderRow(overrides.blockOrder(), enabled, segmentHeight, onBlockOrder)
        CardOption.PORTRAIT_SHAPE -> SegmentedChoices(
            values = listOf(null) + CardDesignSpecs.PORTRAIT_SHAPES,
            selected = overrides.valueOf(option),
            enabled = enabled,
            minSegmentWidth = SHAPE_SEGMENT_MIN_WIDTH,
            segmentHeight = segmentHeight,
            onSelect = { onOption(option, it) },
        ) { shape ->
            if (shape == null) DefaultGlyph() else ShapeGlyph(shape)
            SegmentLabel(stringResource(shape?.let(::shapeLabel) ?: MR.string.profile_card_option_default))
        }
        CardOption.SOCIALS_STYLE -> SegmentedChoices(
            values = listOf(null) + CardDesignSpecs.SOCIALS_STYLES,
            selected = overrides.valueOf(option),
            enabled = enabled,
            minSegmentWidth = SOCIALS_SEGMENT_MIN_WIDTH,
            segmentHeight = segmentHeight,
            onSelect = { onOption(option, it) },
        ) { style ->
            Icon(socialsIcon(style), contentDescription = null, modifier = Modifier.size(GLYPH_SIZE))
            SegmentLabel(stringResource(style?.let(::socialsLabel) ?: MR.string.profile_card_option_default))
        }
        // A font picker shows each face by setting its own name in it; the name is never cut.
        CardOption.DISPLAY_FONT, CardOption.TEXT_FONT -> SegmentedChoices(
            values = listOf(null) + CardDesignSpecs.FONTS,
            selected = overrides.valueOf(option),
            enabled = enabled,
            minSegmentWidth = FONT_SEGMENT_MIN_WIDTH,
            segmentHeight = segmentHeight,
            onSelect = { onOption(option, it) },
            sidePadding = 20.dp,
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

// The app's floating toolbar: option tools as icons that morph into a filled squircle when picked, with Save as its FAB.
@Composable
private fun OptionToolbar(
    options: List<CardOption>,
    current: CardOption,
    onPick: (CardOption) -> Unit,
    save: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalFloatingToolbar(
            expanded = true,
            colors = FloatingToolbarDefaults.standardFloatingToolbarColors(
                toolbarContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                toolbarContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            contentPadding = PaddingValues(horizontal = 4.dp),
            expandedShadowElevation = 0.dp,
            modifier = Modifier.weight(1f, fill = false),
        ) {
            Row(
                modifier = Modifier.fadingEdges(scroll, horizontal = true).horizontalScroll(scroll).selectableGroup(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                options.forEach { option ->
                    ToolItem(
                        icon = optionIcon(option),
                        description = stringResource(optionLabel(option)),
                        selected = option == current,
                        onClick = { onPick(option) },
                    )
                }
            }
        }
        save()
    }
}

@Composable
private fun ToolItem(
    icon: ImageVector,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val motion = MaterialTheme.motionScheme
    val colors = MaterialTheme.colorScheme
    val corner by animateDpAsState(if (selected) 14.dp else TOOL_SIZE / 2, motion.fastSpatialSpec())
    val inset by animateDpAsState(if (selected) 2.dp else 6.dp, motion.fastSpatialSpec())
    val fill by animateColorAsState(if (selected) colors.secondary else colors.surfaceContainerHighest, motion.fastEffectsSpec())
    val ink by animateColorAsState(if (selected) colors.onSecondary else colors.onSurfaceVariant, motion.fastEffectsSpec())
    Box(
        modifier = Modifier
            .size(TOOL_SIZE)
            .revealWhenSelected(selected)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(inset)
            .background(fill, RoundedCornerShape(corner)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = ink, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun OptionsEmptyState(
    design: String,
    save: @Composable () -> Unit,
    onTryAnotherDesign: () -> Unit,
    gap: Dp,
    modifier: Modifier = Modifier,
) {
    val name = stringResource(designLabel(design))
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
        Row(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = CAPTION_INSET),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
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
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(MR.string.profile_card_options_empty_title, name),
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(MR.string.profile_card_options_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(TOOLBAR_HEIGHT).padding(horizontal = OPTION_SIDE_INSET),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FilledTonalButton(
                onClick = onTryAnotherDesign,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.weight(1f).heightIn(min = SAVE_FAB_SIZE),
            ) {
                Icon(Icons.Outlined.SwapHoriz, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(stringResource(MR.string.profile_card_try_another_design), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            save()
        }
    }
}

/**
 * One connected group for every pick-one option: rounded ends, tight inner corners, and the checked segment springs to a
 * full pill. Segments are at least as wide as their content; when they don't fit, the row scrolls under faded edges.
 */
@Composable
private fun SegmentedChoices(
    values: List<String?>,
    selected: String?,
    enabled: Boolean,
    minSegmentWidth: Dp,
    segmentHeight: Dp,
    onSelect: (String?) -> Unit,
    sidePadding: Dp = 8.dp,
    content: @Composable ColumnScope.(String?) -> Unit,
) {
    val spacing = ButtonGroupDefaults.ConnectedSpaceBetween
    val grow = LocalDensity.current.fontScale.coerceIn(1f, MAX_SEGMENT_GROWTH)
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val available = maxWidth - OPTION_SIDE_INSET * 2 - spacing * (values.size - 1)
        val segmentWidth = maxOf(minSegmentWidth * grow, available / values.size)
        val scroll = rememberScrollState()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .fadingEdges(scroll, horizontal = true)
                .horizontalScroll(scroll)
                .padding(horizontal = OPTION_SIDE_INSET),
            horizontalArrangement = Arrangement.spacedBy(spacing),
        ) {
            values.forEachIndexed { index, value ->
                ToggleButton(
                    checked = value == selected,
                    onCheckedChange = { onSelect(value) },
                    enabled = enabled,
                    shapes = connectedButtonShapes(index, values.size),
                    contentPadding = PaddingValues(horizontal = sidePadding, vertical = 10.dp),
                    modifier = Modifier.revealWhenSelected(value == selected).widthIn(min = segmentWidth).height(segmentHeight),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
                    ) { content(value) }
                }
            }
        }
    }
}

@Composable
private fun SegmentLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        textAlign = TextAlign.Center,
        maxLines = 1,
        softWrap = false,
    )
}

@Composable
private fun DefaultGlyph() {
    Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(GLYPH_SIZE))
}

// A hint of each face from the platform's generic families; the card itself uses the real web font.
@Composable
private fun fontPreviewStyle(font: String?): TextStyle {
    val base = MaterialTheme.typography.titleLarge
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
    val scroll = rememberScrollState()
    val values = listOf<String?>(null) + CardDesignSpecs.ACCENT_SWATCHES
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .fadingEdges(scroll, horizontal = true)
            .horizontalScroll(scroll)
            .padding(horizontal = OPTION_SIDE_INSET - 4.dp)
            .selectableGroup(),
    ) {
        values.forEach { hex ->
            if (hex == null) {
                Swatch(
                    fill = MaterialTheme.colorScheme.surfaceVariant,
                    selected = selected == null,
                    enabled = enabled,
                    description = stringResource(MR.string.profile_card_option_default),
                    onClick = { onSelect(null) },
                ) { NoColourSlash() }
            } else {
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
}

@Composable
private fun NoColourSlash() {
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
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
                .border(2.5.dp, ring.copy(alpha = progress), CircleShape)
                .padding(4.dp)
                .clip(shape)
                .background(fill)
                .border(1.dp, outline.copy(alpha = 0.5f * (1f - progress)), shape),
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
@Composable
private fun BlockOrderRow(order: List<String>, enabled: Boolean, tileHeight: Dp, onChange: (List<String>) -> Unit) {
    val spacing = ButtonGroupDefaults.ConnectedSpaceBetween
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(horizontal = OPTION_SIDE_INSET)) {
        val tileWidth = (maxWidth - spacing * (order.size - 1)) / order.size
        // The list must be a new immutable instance per change: ReorderableRow keys its offsets on the instance.
        ReorderableRow(
            list = order,
            onSettle = { from, to -> if (from != to) onChange(order.toMutableList().apply { add(to, removeAt(from)) }) },
            horizontalArrangement = Arrangement.spacedBy(spacing),
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
                    val elevation by animateDpAsState(if (dragging) 6.dp else 0.dp, motion.fastSpatialSpec())
                    val lift by animateFloatAsState(if (dragging) 1.04f else 1f, motion.fastSpatialSpec())
                    Surface(
                        shape = if (dragging) CircleShape else connectedButtonShapes(index, order.size).shape,
                        color = if (dragging) colors.secondaryContainer else colors.surfaceContainerHighest,
                        contentColor = if (dragging) colors.onSecondaryContainer else colors.onSurface,
                        shadowElevation = elevation,
                        modifier = Modifier
                            .width(tileWidth)
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
                        BlockTile(kind = kind, name = name, position = index + 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun BlockTile(kind: String, name: String, position: Int) {
    val ordinal = position.toString()
    Box(modifier = Modifier.fillMaxSize()) {
        Text(
            text = ordinal,
            style = MaterialTheme.typography.labelLargeEmphasized,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.align(Alignment.BottomCenter).padding(start = 4.dp, end = 4.dp, bottom = 10.dp),
        ) {
            Icon(imageVector = blockIcon(kind), contentDescription = null, modifier = Modifier.size(22.dp))
            val style = MaterialTheme.typography.labelMedium
            // Four tiles share the row, so a long name at a large text size steps down rather than being cut.
            Text(
                text = name,
                style = style,
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = MIN_TILE_LABEL_SIZE, maxFontSize = style.fontSize),
            )
        }
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

// Masks content under a gradient at whichever ends can still scroll, so hidden rows read as "more this way".
@Composable
private fun Modifier.fadingEdges(scroll: ScrollState, horizontal: Boolean): Modifier {
    val fade = with(LocalDensity.current) { FADE_LENGTH.toPx() }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    return graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val atStart = scroll.value > 0
            val atEnd = scroll.value < scroll.maxValue
            val extent = if (horizontal) size.width else size.height
            fun mask(fromEdge: Float, towardInside: Float) = if (horizontal) {
                Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = fromEdge, endX = towardInside)
            } else {
                Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = fromEdge, endY = towardInside)
            }
            // Horizontal scroll runs from the end side in RTL, so "start" is the right edge there.
            val startEdge = if (horizontal && rtl) extent else 0f
            val endEdge = if (horizontal && rtl) 0f else extent
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

private fun hexColor(hex: String): Color = Color(hex.removePrefix("#").toLong(16) or 0xFF000000)

private fun optionLabel(option: CardOption): StringResource = when (option) {
    CardOption.ACCENT -> MR.string.profile_card_option_accent
    CardOption.DISPLAY_FONT -> MR.string.profile_card_option_display_font
    CardOption.TEXT_FONT -> MR.string.profile_card_option_text_font
    CardOption.PORTRAIT_SHAPE -> MR.string.profile_card_option_portrait_shape
    CardOption.SOCIALS_STYLE -> MR.string.profile_card_option_socials_style
    CardOption.BLOCK_ORDER -> MR.string.profile_card_option_block_order
}

private fun optionIcon(option: CardOption): ImageVector = when (option) {
    CardOption.ACCENT -> Icons.Outlined.Palette
    CardOption.DISPLAY_FONT -> Icons.Outlined.Title
    CardOption.TEXT_FONT -> Icons.AutoMirrored.Outlined.Notes
    CardOption.PORTRAIT_SHAPE -> Icons.Outlined.AccountCircle
    CardOption.SOCIALS_STYLE -> Icons.Outlined.Share
    CardOption.BLOCK_ORDER -> Icons.Outlined.Reorder
}

private fun socialsIcon(style: String?): ImageVector = when (style) {
    null -> Icons.Outlined.AutoAwesome
    "bar" -> Icons.Outlined.ViewStream
    "wordmark" -> Icons.Outlined.TextFields
    "handles" -> Icons.Outlined.AlternateEmail
    else -> Icons.Outlined.Apps
}

private fun blockIcon(kind: String): ImageVector = when (kind) {
    "links" -> Icons.Outlined.Link
    "moments" -> Icons.Outlined.PhotoLibrary
    "posts" -> Icons.AutoMirrored.Outlined.Article
    else -> Icons.Outlined.ChatBubbleOutline
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
