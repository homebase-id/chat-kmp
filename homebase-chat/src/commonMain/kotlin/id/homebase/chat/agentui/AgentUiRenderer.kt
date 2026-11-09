@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)

package id.homebase.chat.agentui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import id.homebase.api.util.truncateToCodePoints
import id.homebase.chat.widget.ChatMarkdown
import id.homebase.core.image.HomebaseImage
import id.homebase.core.image.HomebaseImageData
import id.homebase.resources.MR
import id.homebase.resources.chat_image_unavailable
import org.jetbrains.compose.resources.stringResource

/** [rollback] restores the inputs this action changed locally if the send fails. */
data class AgentUiActionRequest(
    val actionId: String,
    val label: String,
    val values: Map<String, String>,
    val rollback: Map<String, String> = emptyMap(),
)

private val ListItemWidth = 260.dp

private class Env(
    val tree: AgentUiTree,
    val inputs: AgentUiInputState,
    val imageData: (String) -> HomebaseImageData?,
    val enabled: Boolean,
    val pending: Set<String>,
    val onAction: (AgentUiActionRequest) -> Unit,
) {
    fun usable(action: AgentUiNodeAction?): Boolean = enabled && action?.id !in pending

    fun fire(action: AgentUiNodeAction, nodeId: String, label: String, rollback: Map<String, String> = emptyMap()) {
        onAction(AgentUiActionRequest(action.id, label, tree.collectValues(action, nodeId, inputs.snapshot()), rollback))
    }
}

/** Renders a validated tree; the validator bounds depth, so the recursion is bounded too. */
@Composable
fun AgentUiTreeView(
    tree: AgentUiTree,
    inputs: AgentUiInputState,
    imageData: (String) -> HomebaseImageData?,
    enabled: Boolean,
    pendingActionIds: Set<String>,
    onAction: (AgentUiActionRequest) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentImageData by rememberUpdatedState(imageData)
    val currentOnAction by rememberUpdatedState(onAction)
    // Remembered so an unchanged card skips recomposing its nodes when the bubble around it recomposes.
    val env = remember(tree, inputs, enabled, pendingActionIds) {
        Env(tree, inputs, { currentImageData(it) }, enabled, pendingActionIds) { currentOnAction(it) }
    }
    val root = tree.root
    Box(modifier) {
        if (root is AgentUiNode.Card) CardBody(root.children, env, Arrangement.spacedBy(12.dp)) else NodeView(root, env)
    }
}

/** The frame draws a root card itself, so the card's tone becomes the frame's colour. */
val AgentUiTree.frameTone: Tone get() = (root as? AgentUiNode.Card)?.tone ?: Tone.Default

@Composable
fun agentUiContainerColor(tone: Tone, nested: Boolean = false): Color = when (tone) {
    Tone.Default -> if (nested) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerHigh
    Tone.Primary -> MaterialTheme.colorScheme.primaryContainer
    Tone.Secondary -> MaterialTheme.colorScheme.secondaryContainer
    Tone.Error -> MaterialTheme.colorScheme.errorContainer
}

private fun Spacing.dp(): Dp = when (this) {
    Spacing.None -> 0.dp
    Spacing.Small -> 8.dp
    Spacing.Medium -> 16.dp
}

@Composable
private fun toneColor(tone: Tone): Color = when (tone) {
    Tone.Default -> LocalContentColor.current
    Tone.Primary -> MaterialTheme.colorScheme.primary
    Tone.Secondary -> MaterialTheme.colorScheme.onSurfaceVariant
    Tone.Error -> MaterialTheme.colorScheme.error
}

@Composable
private fun NodeView(node: AgentUiNode, env: Env, modifier: Modifier = Modifier) {
    when (node) {
        is AgentUiNode.Column -> Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(node.spacing.dp())) {
            Children(node.children, env)
        }
        is AgentUiNode.Row -> FlowRow(
            modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(node.spacing.dp()),
            verticalArrangement = Arrangement.spacedBy(node.spacing.dp()),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) { Children(node.children, env) }
        is AgentUiNode.Card -> CardView(node, env, modifier)
        is AgentUiNode.Text -> ChatMarkdown(
            content = node.markdown,
            modifier = modifier,
            color = toneColor(node.tone),
            style = when (node.style) {
                TextStyleKind.Title -> MaterialTheme.typography.titleMedium
                TextStyleKind.Body -> MaterialTheme.typography.bodyMedium
                TextStyleKind.Label -> MaterialTheme.typography.labelLarge
                TextStyleKind.Caption -> MaterialTheme.typography.bodySmall
            },
        )
        is AgentUiNode.Image -> ImageView(node, env, modifier)
        is AgentUiNode.Chip -> AgentChip(node.label, node.selected, modifier)
        is AgentUiNode.Divider -> HorizontalDivider(modifier, color = MaterialTheme.colorScheme.outlineVariant)
        is AgentUiNode.ListNode -> when (node.direction) {
            ListDirection.Vertical -> Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Children(node.children, env)
            }
            ListDirection.Horizontal -> LazyRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(node.children, key = { it.id }) { child -> Box(Modifier.width(ListItemWidth)) { NodeView(child, env) } }
            }
        }
        is AgentUiNode.Table -> TableView(node, modifier)
        is AgentUiNode.Progress -> Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            node.label?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (node.value == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { node.value }, modifier = Modifier.fillMaxWidth())
        }
        is AgentUiNode.Button -> ButtonView(node, env, modifier)
        is AgentUiNode.Toggle -> ToggleView(node, env, modifier)
        is AgentUiNode.TextInput -> TextInputView(node, env, modifier)
        is AgentUiNode.Select -> SelectView(node, env, modifier)
        is AgentUiNode.Unknown -> node.fallback?.let {
            Text(it, modifier, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Children(children: List<AgentUiNode>, env: Env) {
    for (child in children) key(child.id) { NodeView(child, env) }
}

@Composable
private fun CardBody(children: List<AgentUiNode>, env: Env, spacing: Arrangement.Vertical, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = spacing) { Children(children, env) }
}

@Composable
private fun CardView(node: AgentUiNode.Card, env: Env, modifier: Modifier) {
    val container = agentUiContainerColor(node.tone, nested = true)
    Card(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = container, contentColor = contentColorFor(container)),
    ) {
        CompositionLocalProvider(LocalAgentUiContainer provides container) {
            if (node.tone == Tone.Error) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.ErrorOutline, contentDescription = null, modifier = Modifier.size(20.dp))
                    CardBody(node.children, env, Arrangement.spacedBy(8.dp), Modifier.weight(1f))
                }
            } else {
                CardBody(node.children, env, Arrangement.spacedBy(8.dp), Modifier.padding(16.dp))
            }
        }
    }
}

/** The container the current card draws, so a selected chip can pick a colour that stands out from it. */
internal val LocalAgentUiContainer = compositionLocalOf { Color.Unspecified }

/** One chip for display chips and select options. */
@Composable
private fun AgentChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val onSecondary = LocalAgentUiContainer.current == scheme.secondaryContainer
    val selectedContainer = if (onSecondary) scheme.secondary else scheme.secondaryContainer
    val selectedContent = if (onSecondary) scheme.onSecondary else scheme.onSecondaryContainer
    val text: @Composable () -> Unit = { Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    val check: (@Composable () -> Unit)? = if (selected) {
        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
    } else null
    if (onClick == null) {
        val container = if (selected) selectedContainer else LocalAgentUiContainer.current.takeOrElse { scheme.surface }
        Surface(
            modifier.semantics(mergeDescendants = true) { this.selected = selected },
            shape = FilterChipDefaults.shape,
            color = container,
            contentColor = if (selected) selectedContent else scheme.onSurfaceVariant,
            border = if (selected) null else BorderStroke(1.dp, scheme.outlineVariant),
        ) {
            Row(
                Modifier.height(ChipHeight).widthIn(min = 48.dp).padding(start = if (selected) 8.dp else 16.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                check?.invoke()
                text()
            }
        }
    } else {
        FilterChip(
            selected = selected,
            onClick = onClick,
            label = text,
            modifier = modifier.trimTouchTarget(ChipHeight).semantics { role = Role.RadioButton },
            enabled = enabled,
            leadingIcon = check,
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = selectedContainer,
                selectedLabelColor = selectedContent,
                selectedLeadingIconColor = selectedContent,
            ),
        )
    }
}

private val ChipHeight = 32.dp

/** Lays out a control at its visual height; its 48dp touch target bleeds into the surrounding gap. */
private fun Modifier.trimTouchTarget(visualHeight: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val trim = ((placeable.height - visualHeight.roundToPx()) / 2).coerceAtLeast(0)
    layout(placeable.width, placeable.height - 2 * trim) { placeable.place(0, -trim) }
}

@Composable
private fun ImageView(node: AgentUiNode.Image, env: Env, modifier: Modifier) {
    val shape = MaterialTheme.shapes.large
    val frame = modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(shape)
    val data = env.imageData(node.payloadKey)
    if (data == null) {
        AltBox(node.alt, frame)
    } else {
        HomebaseImage(
            imageData = data,
            modifier = frame,
            contentDescription = node.alt,
            contentScale = ContentScale.Crop,
            error = { AltBox(node.alt, Modifier, stringResource(MR.string.chat_image_unavailable)) },
        )
    }
}

@Composable
private fun AltBox(alt: String, modifier: Modifier, note: String? = null) {
    val onColor = MaterialTheme.colorScheme.onSecondaryContainer
    Box(modifier.background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.TopStart) {
        Column(Modifier.fillMaxHeight().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.BrokenImage, contentDescription = null, tint = onColor)
            Text(alt, style = MaterialTheme.typography.bodyMedium, color = onColor, maxLines = 4, overflow = TextOverflow.Ellipsis)
            note?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = onColor) }
        }
    }
}

private val NumericCell = Regex("""[-+]?[$€£]?\d[\d.,\s]*[kKmMbB%]?""")

@Composable
private fun TableView(node: AgentUiNode.Table, modifier: Modifier) {
    val divider = MaterialTheme.colorScheme.outlineVariant
    val columns = maxOf(node.header.size, node.rows.maxOfOrNull { it.size } ?: 0)
    val numeric = List(columns) { c ->
        val cells = node.rows.mapNotNull { it.getOrNull(c)?.trim()?.takeIf(String::isNotEmpty) }
        cells.isNotEmpty() && cells.all(NumericCell::matches)
    }
    @Composable
    fun TableRow(cells: List<String>, style: TextStyle, color: Color) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            List(columns) { cells.getOrElse(it) { "" } }.forEachIndexed { c, cell ->
                val isNumber = numeric.getOrElse(c) { false }
                Text(
                    cell,
                    Modifier.weight(if (isNumber) 1f else 2f),
                    style = style,
                    color = color,
                    textAlign = if (isNumber) TextAlign.End else TextAlign.Start,
                )
            }
        }
    }
    Column(modifier.fillMaxWidth()) {
        if (node.header.isNotEmpty()) {
            TableRow(node.header, MaterialTheme.typography.labelLarge, MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(color = divider)
        }
        node.rows.forEachIndexed { i, row ->
            if (i > 0) HorizontalDivider(color = divider)
            TableRow(row, MaterialTheme.typography.bodyMedium, LocalContentColor.current)
        }
    }
}

@Composable
private fun ButtonView(node: AgentUiNode.Button, env: Env, modifier: Modifier) {
    val busy = node.action?.id in env.pending
    val enabled = node.action != null && env.usable(node.action)
    val onClick = { node.action?.let { env.fire(it, node.id, node.label) }; Unit }
    val shapes = ButtonDefaults.shapes()
    val m = modifier.trimTouchTarget(ButtonDefaults.MinHeight)
    val label: @Composable () -> Unit = {
        if (busy) {
            LoadingIndicator(Modifier.size(ButtonDefaults.IconSize + 6.dp), color = LocalContentColor.current)
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        }
        Text(node.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    // A sent action stays disabled, but keeps its colours so it reads as in progress, not unavailable.
    fun ButtonColors.busyAware() = if (busy) copy(disabledContainerColor = containerColor, disabledContentColor = contentColor) else this
    when (node.style) {
        ButtonStyle.Filled -> Button(onClick, shapes, m, enabled, ButtonDefaults.buttonColors().busyAware()) { label() }
        ButtonStyle.Tonal -> FilledTonalButton(onClick, shapes, m, enabled, ButtonDefaults.filledTonalButtonColors().busyAware()) { label() }
        ButtonStyle.Outlined -> OutlinedButton(onClick, shapes, m, enabled, ButtonDefaults.outlinedButtonColors().busyAware()) { label() }
        ButtonStyle.Text -> TextButton(onClick, shapes, m, enabled, ButtonDefaults.textButtonColors().busyAware()) { label() }
        ButtonStyle.Destructive -> OutlinedButton(
            onClick,
            shapes,
            m,
            enabled,
            ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error).busyAware(),
            border = BorderStroke(1.dp, if (enabled || busy) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant),
        ) { label() }
    }
}

@Composable
private fun ToggleView(node: AgentUiNode.Toggle, env: Env, modifier: Modifier) {
    val checked = (env.inputs[node.id] ?: node.value.toString()) == "true"
    val enabled = env.usable(node.action)
    Row(
        modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch) {
                env.inputs[node.id] = it.toString()
                node.action?.let { a -> env.fire(a, node.id, node.label, mapOf(node.id to checked.toString())) }
            }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(node.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun TextInputView(node: AgentUiNode.TextInput, env: Env, modifier: Modifier) {
    val enabled = env.usable(node.action)
    val send = !node.multiline && node.action != null
    OutlinedTextField(
        value = env.inputs[node.id] ?: node.value,
        onValueChange = {
            env.inputs[node.id] = env.tree.fitText(node.id, it.truncateToCodePoints(node.maxLength), env.inputs.snapshot())
        },
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        label = { Text(node.label) },
        singleLine = !node.multiline,
        minLines = if (node.multiline) 2 else 1,
        maxLines = if (node.multiline) 6 else 1,
        keyboardOptions = KeyboardOptions(imeAction = if (send) ImeAction.Send else if (node.multiline) ImeAction.Default else ImeAction.Done),
        keyboardActions = KeyboardActions(onSend = { node.action?.let { env.fire(it, node.id, node.label) } }),
    )
}

@Composable
private fun SelectView(node: AgentUiNode.Select, env: Env, modifier: Modifier) {
    val enabled = env.usable(node.action)
    val current = env.inputs[node.id] ?: node.value
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(node.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (option in node.options) key(option.id) {
                AgentChip(option.label, current == option.id, enabled = enabled) {
                    env.inputs[node.id] = option.id
                    node.action?.let { env.fire(it, node.id, option.label, mapOf(node.id to current)) }
                }
            }
        }
    }
}
