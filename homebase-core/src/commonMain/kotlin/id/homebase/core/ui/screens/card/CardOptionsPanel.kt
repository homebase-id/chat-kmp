@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)

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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.FormatColorReset
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Reorder
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Title
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ViewStream
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
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
import id.homebase.resources.profile_card_font_sample
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
import id.homebase.resources.profile_card_shape_circle
import id.homebase.resources.profile_card_shape_ellipse
import id.homebase.resources.profile_card_shape_rounded
import id.homebase.resources.profile_card_shape_square
import id.homebase.resources.profile_card_socials_bar
import id.homebase.resources.profile_card_socials_glyphs
import id.homebase.resources.profile_card_socials_handles
import id.homebase.resources.profile_card_socials_wordmark
import id.homebase.resources.profile_card_tool_accent
import id.homebase.resources.profile_card_tool_block_order
import id.homebase.resources.profile_card_tool_display_font
import id.homebase.resources.profile_card_tool_portrait_shape
import id.homebase.resources.profile_card_tool_socials_style
import id.homebase.resources.profile_card_tool_text_font
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import sh.calvin.reorderable.ReorderableColumn

private val SWATCH_SIZE = 48.dp
private val SWATCH_TARGET = 60.dp
private val SEGMENT_HEIGHT = 88.dp
private val FONT_SEGMENT_MIN_WIDTH = 120.dp
private val FONT_SAMPLE_HEIGHT = 32.dp
private val SOCIALS_SEGMENT_MIN_WIDTH = 68.dp
private val SHAPE_SEGMENT_MIN_WIDTH = 60.dp
private val GLYPH_SIZE = 28.dp
private val ORDER_ROW_HEIGHT = 48.dp
private val TOOL_INDICATOR_WIDTH = 52.dp
private val TOOL_INDICATOR_HEIGHT = 32.dp
private val FADE_LENGTH = 24.dp
private val OPTION_SIDE_INSET = 16.dp
private const val TOOL_LABELS_MAX_FONT_SCALE = 1.3f
private const val MAX_SEGMENT_GROWTH = 1.3f

/** Every control comes from the design's [CardDesignSpec]; there is no per-design screen. */
@Composable
internal fun CardOptionsPanel(
    design: String,
    overrides: CardOverrides,
    enabled: Boolean,
    onOption: (CardOption, String?) -> Unit,
    onBlockOrder: (List<String>) -> Unit,
    optionsHeight: Dp,
    toolbarHeight: Dp,
    gap: Dp,
    modifier: Modifier = Modifier,
) {
    val spec = CardDesignSpecs.of(design)
    val options = CardOption.entries.filter { spec != null && it in spec.options }
    if (options.isEmpty()) {
        OptionsEmptyState(modifier = modifier.padding(horizontal = 24.dp))
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
            modifier = Modifier.fillMaxWidth().height(optionsHeight),
            contentAlignment = Alignment.Center,
        ) { option ->
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when (option) {
                    CardOption.ACCENT -> SwatchGrid(overrides.valueOf(option), enabled) { onOption(option, it) }
                    CardOption.BLOCK_ORDER -> BlockOrderList(overrides.blockOrder(), enabled, onBlockOrder)
                    CardOption.PORTRAIT_SHAPE -> SegmentedChoices(
                        values = listOf(null) + CardDesignSpecs.PORTRAIT_SHAPES,
                        selected = overrides.valueOf(option),
                        enabled = enabled,
                        minSegmentWidth = SHAPE_SEGMENT_MIN_WIDTH,
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
                        onSelect = { onOption(option, it) },
                    ) { style ->
                        Icon(socialsIcon(style), contentDescription = null, modifier = Modifier.size(GLYPH_SIZE))
                        SegmentLabel(stringResource(style?.let(::socialsLabel) ?: MR.string.profile_card_option_default))
                    }
                    CardOption.DISPLAY_FONT, CardOption.TEXT_FONT -> SegmentedChoices(
                        values = listOf(null) + CardDesignSpecs.FONTS,
                        selected = overrides.valueOf(option),
                        enabled = enabled,
                        minSegmentWidth = FONT_SEGMENT_MIN_WIDTH,
                        onSelect = { onOption(option, it) },
                    ) { font ->
                        // Fixed so faces with tall ascenders don't push their name out of line with the rest.
                        val sampleHeight = FONT_SAMPLE_HEIGHT * LocalDensity.current.fontScale.coerceAtLeast(1f)
                        Box(modifier = Modifier.height(sampleHeight), contentAlignment = Alignment.Center) {
                            Text(
                                text = stringResource(MR.string.profile_card_font_sample),
                                style = fontPreviewStyle(font),
                                maxLines = 1,
                            )
                        }
                        SegmentLabel(stringResource(font?.let(::fontLabel) ?: MR.string.profile_card_option_default))
                    }
                }
            }
        }
        OptionToolbar(
            options = options,
            current = current,
            onPick = { picked = it.name },
            modifier = Modifier.fillMaxWidth().height(toolbarHeight).padding(horizontal = OPTION_SIDE_INSET),
        )
    }
}

// Categories read as tools on a floating bar rather than tabs; the selected one fills and squares off like the toggles above it.
@Composable
private fun OptionToolbar(
    options: List<CardOption>,
    current: CardOption,
    onPick: (CardOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    val showLabels = LocalDensity.current.fontScale < TOOL_LABELS_MAX_FONT_SCALE
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp).selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEach { option ->
                ToolItem(
                    icon = optionIcon(option),
                    label = stringResource(toolLabel(option)),
                    description = stringResource(optionLabel(option)),
                    selected = option == current,
                    showLabel = showLabels,
                    onClick = { onPick(option) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun ToolItem(
    icon: ImageVector,
    label: String,
    description: String,
    selected: Boolean,
    showLabel: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = MaterialTheme.motionScheme
    val colors = MaterialTheme.colorScheme
    val corner by animateDpAsState(if (selected) 10.dp else TOOL_INDICATOR_HEIGHT / 2, motion.fastSpatialSpec())
    val fill by animateColorAsState(if (selected) colors.primary else colors.surfaceContainerHigh, motion.fastEffectsSpec())
    val ink by animateColorAsState(if (selected) colors.onPrimary else colors.onSurfaceVariant, motion.fastEffectsSpec())
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = description },
    ) {
        Box(
            modifier = Modifier
                .size(width = TOOL_INDICATOR_WIDTH, height = TOOL_INDICATOR_HEIGHT)
                .background(fill, RoundedCornerShape(corner)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
        }
        if (showLabel) {
            Text(
                text = label,
                style = if (selected) MaterialTheme.typography.labelSmallEmphasized else MaterialTheme.typography.labelSmall,
                color = if (selected) colors.primary else colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp, start = 2.dp, end = 2.dp),
            )
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
                .size(72.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer, MaterialShapes.Cookie9Sided.toShape()),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Tune,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(32.dp),
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

/**
 * One connected group for every pick-one option, so selection always looks the same: the checked segment turns primary
 * and springs to its checked shape. Segments share a fixed height; when they don't fit, the row scrolls under faded edges.
 */
@Composable
private fun SegmentedChoices(
    values: List<String?>,
    selected: String?,
    enabled: Boolean,
    minSegmentWidth: Dp,
    onSelect: (String?) -> Unit,
    content: @Composable ColumnScope.(String?) -> Unit,
) {
    val spacing = ButtonGroupDefaults.ConnectedSpaceBetween
    // Larger text gets proportionally larger segments rather than clipped glyphs.
    val grow = LocalDensity.current.fontScale.coerceIn(1f, MAX_SEGMENT_GROWTH)
    val segmentHeight = SEGMENT_HEIGHT * grow
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val available = maxWidth - OPTION_SIDE_INSET * 2 - spacing * (values.size - 1)
        val segmentWidth = maxOf(minSegmentWidth * grow, available / values.size)
        val scroll = rememberScrollState()
        val density = LocalDensity.current
        val selectedIndex = values.indexOf(selected).coerceAtLeast(0)
        LaunchedEffect(Unit) {
            val step = with(density) { (segmentWidth + spacing).toPx() }
            scroll.scrollTo((step * (selectedIndex - 1)).toInt().coerceAtLeast(0))
        }
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
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp),
                    modifier = Modifier.width(segmentWidth).height(segmentHeight),
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
        overflow = TextOverflow.Ellipsis,
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
private fun SwatchGrid(selected: String?, enabled: Boolean, onSelect: (String?) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = OPTION_SIDE_INSET).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        maxItemsInEachRow = 5,
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
    val ring = MaterialTheme.colorScheme.primary
    val shape = MorphShape(morph, progress)
    Box(
        modifier = Modifier
            .size(SWATCH_TARGET)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(SWATCH_SIZE + 8.dp)
                .border(2.5.dp, ring.copy(alpha = progress), CircleShape)
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

// Drag is the direct way to reorder; the arrow moves stay as accessibility actions for anyone who can't drag.
@Composable
private fun BlockOrderList(order: List<String>, enabled: Boolean, onChange: (List<String>) -> Unit) {
    val scroll = rememberScrollState()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .fadingEdges(scroll, horizontal = false)
            .verticalScroll(scroll),
    ) {
        // The list must be a new immutable instance per change: ReorderableColumn keys its offsets on the instance.
        ReorderableColumn(
            list = order,
            onSettle = { from, to -> if (from != to) onChange(order.toMutableList().apply { add(to, removeAt(from)) }) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = OPTION_SIDE_INSET, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) { index, kind, dragging ->
            key(kind) {
                ReorderableItem {
                    val name = stringResource(blockLabel(kind))
                    val moveUp = stringResource(MR.string.profile_card_option_move_up, name)
                    val moveDown = stringResource(MR.string.profile_card_option_move_down, name)
                    val big = 20.dp
                    val small = 4.dp
                    val first = index == 0
                    val last = index == order.lastIndex
                    val shape = if (dragging) {
                        RoundedCornerShape(big)
                    } else {
                        RoundedCornerShape(
                            topStart = if (first) big else small,
                            topEnd = if (first) big else small,
                            bottomStart = if (last) big else small,
                            bottomEnd = if (last) big else small,
                        )
                    }
                    val elevation by animateDpAsState(if (dragging) 6.dp else 0.dp, MaterialTheme.motionScheme.fastSpatialSpec())
                    Surface(
                        shape = shape,
                        color = if (dragging) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        shadowElevation = elevation,
                        modifier = Modifier
                            .fillMaxWidth()
                            .longPressDraggableHandle(enabled = enabled)
                            .semantics {
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.height(ORDER_ROW_HEIGHT).padding(start = 12.dp),
                        ) {
                            Icon(
                                imageVector = blockIcon(kind),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(start = 16.dp),
                            )
                            Box(
                                modifier = Modifier
                                    .size(ORDER_ROW_HEIGHT)
                                    .draggableHandle(enabled = enabled),
                                contentAlignment = Alignment.Center,
                            ) {
                                DragHandleIcon(name)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DragHandleIcon(name: String) {
    Icon(
        imageVector = Icons.Outlined.DragIndicator,
        contentDescription = stringResource(MR.string.profile_card_option_drag, name),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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

private fun toolLabel(option: CardOption): StringResource = when (option) {
    CardOption.ACCENT -> MR.string.profile_card_tool_accent
    CardOption.DISPLAY_FONT -> MR.string.profile_card_tool_display_font
    CardOption.TEXT_FONT -> MR.string.profile_card_tool_text_font
    CardOption.PORTRAIT_SHAPE -> MR.string.profile_card_tool_portrait_shape
    CardOption.SOCIALS_STYLE -> MR.string.profile_card_tool_socials_style
    CardOption.BLOCK_ORDER -> MR.string.profile_card_tool_block_order
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
