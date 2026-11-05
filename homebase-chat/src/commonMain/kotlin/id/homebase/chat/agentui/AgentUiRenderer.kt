@file:OptIn(ExperimentalLayoutApi::class)

package id.homebase.chat.agentui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import id.homebase.api.util.truncateToCodePoints
import id.homebase.chat.widget.ChatMarkdown
import id.homebase.core.image.HomebaseImage
import id.homebase.core.image.HomebaseImageData
import id.homebase.resources.MR
import id.homebase.resources.chat_agent_ui_image_unavailable
import org.jetbrains.compose.resources.stringResource

data class AgentUiActionRequest(val actionId: String, val label: String, val values: Map<String, String>)

private val ListItemWidth = 260.dp

private class Env(
    val tree: AgentUiTree,
    val inputs: AgentUiInputState,
    val imageData: (String) -> HomebaseImageData?,
    val enabled: Boolean,
    val pending: Set<String>,
    val onAction: (AgentUiActionRequest) -> Unit,
) {
    fun usable(action: AgentUiNodeAction?, needsAction: Boolean = false): Boolean =
        enabled && (if (action == null) !needsAction else action.id !in pending)

    fun fire(action: AgentUiNodeAction, nodeId: String, label: String) {
        onAction(AgentUiActionRequest(action.id, label, tree.collectValues(action, nodeId, inputs.snapshot())))
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
    val env = Env(tree, inputs, imageData, enabled, pendingActionIds, onAction)
    Box(modifier) { NodeView(tree.root, env) }
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
        is AgentUiNode.Chip -> Surface(
            modifier = modifier.semantics(mergeDescendants = true) { selected = node.selected },
            shape = MaterialTheme.shapes.small,
            color = if (node.selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            Text(node.label, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
        }
        is AgentUiNode.Divider -> HorizontalDivider(modifier)
        is AgentUiNode.ListNode -> when (node.direction) {
            ListDirection.Vertical -> Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Children(node.children, env)
            }
            ListDirection.Horizontal -> LazyRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(node.children, key = { it.id }) { child -> Box(Modifier.width(ListItemWidth)) { NodeView(child, env) } }
            }
        }
        is AgentUiNode.Table -> TableView(node, modifier)
        is AgentUiNode.Progress -> Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            node.label?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
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
private fun CardView(node: AgentUiNode.Card, env: Env, modifier: Modifier) {
    val container = when (node.tone) {
        Tone.Default -> MaterialTheme.colorScheme.surfaceContainerHighest
        Tone.Primary -> MaterialTheme.colorScheme.primaryContainer
        Tone.Secondary -> MaterialTheme.colorScheme.secondaryContainer
        Tone.Error -> MaterialTheme.colorScheme.errorContainer
    }
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = container)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Children(node.children, env) }
    }
}

@Composable
private fun ImageView(node: AgentUiNode.Image, env: Env, modifier: Modifier) {
    val shape = RoundedCornerShape(12.dp)
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
            error = { AltBox(node.alt, Modifier, stringResource(MR.string.chat_agent_ui_image_unavailable)) },
        )
    }
}

@Composable
private fun AltBox(alt: String, modifier: Modifier, note: String? = null) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(alt, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
            note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun TableView(node: AgentUiNode.Table, modifier: Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (node.header.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (cell in node.header) Text(cell, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            }
            HorizontalDivider()
        }
        for (row in node.rows) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (cell in row) Text(cell, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ButtonView(node: AgentUiNode.Button, env: Env, modifier: Modifier) {
    val enabled = env.usable(node.action, needsAction = true)
    val onClick = { node.action?.let { env.fire(it, node.id, node.label) }; Unit }
    val label: @Composable () -> Unit = { Text(node.label) }
    when (node.style) {
        ButtonStyle.Filled -> Button(onClick, modifier, enabled = enabled) { label() }
        ButtonStyle.Tonal -> FilledTonalButton(onClick, modifier, enabled = enabled) { label() }
        ButtonStyle.Outlined -> OutlinedButton(onClick, modifier, enabled = enabled) { label() }
        ButtonStyle.Text -> TextButton(onClick, modifier, enabled = enabled) { label() }
        ButtonStyle.Destructive -> Button(
            onClick,
            modifier,
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
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
                node.action?.let { a -> env.fire(a, node.id, node.label) }
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
        onValueChange = { env.inputs[node.id] = it.truncateToCodePoints(node.maxLength) },
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
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(node.label, style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (option in node.options) key(option.id) {
                FilterChip(
                    selected = current == option.id,
                    onClick = {
                        env.inputs[node.id] = option.id
                        node.action?.let { env.fire(it, node.id, option.label) }
                    },
                    enabled = enabled,
                    label = { Text(option.label) },
                )
            }
        }
    }
}
