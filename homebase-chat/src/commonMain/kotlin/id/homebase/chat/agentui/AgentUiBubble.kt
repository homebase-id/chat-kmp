package id.homebase.chat.agentui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import co.touchlab.kermit.Logger
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import id.homebase.api.client.KeyHeader
import id.homebase.api.util.truncateToCodePoints
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.ChatMessageSenderService
import id.homebase.chat.services.content.MessageContent
import id.homebase.core.config.chatTargetDrive
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.image.ImageSize
import id.homebase.resources.MR
import id.homebase.resources.chat_agent_ui_action_failed
import id.homebase.resources.chat_agent_ui_action_other
import id.homebase.resources.chat_agent_ui_action_you
import id.homebase.resources.chat_agent_ui_unparseable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal sealed interface TreeLoad {
    data object Loading : TreeLoad
    data object Failed : TreeLoad
    data class Ready(val tree: AgentUiTree, val rev: Int) : TreeLoad
}

internal fun firstFrameTree(content: MessageContent.AgentUi, rev: Int, message: MessageUiModel, loader: AgentUiTreeLoader): TreeLoad =
    content.inlineTree.toLoad(rev)
        ?: loader.cached(message).toLoad(rev)
        ?: if (message.agentUiTreePayload()?.iv == null) TreeLoad.Failed else TreeLoad.Loading

private fun AgentUiTreeResult?.toLoad(rev: Int): TreeLoad? = when (this) {
    is AgentUiTreeResult.Valid -> TreeLoad.Ready(tree, rev)
    is AgentUiTreeResult.Invalid -> TreeLoad.Failed
    null -> null
}

@OptIn(ExperimentalUuidApi::class)
@Composable
fun AgentUiBubble(
    content: MessageContent.AgentUi,
    message: MessageUiModel,
    displayOnly: Boolean,
    sentByYou: Boolean,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val descriptor = content.descriptor
    if (descriptor == null) {
        UnparseableChip(Icons.Default.SmartToy, modifier)
        return
    }

    val treePayload = message.agentUiTreePayload()
    val aesKey = message.keyHeader.aesKey
    val images = remember(message.fileId, message.payloads) {
        val keys = agentUiImageKeys(message.payloads)
        message.payloads.orEmpty().filter { it.key in keys }.associate { p ->
            p.key to HomebaseImageData.from(
                driveId = chatTargetDrive.alias,
                fileId = message.fileId,
                descriptor = p,
                keyHeader = KeyHeader(p.iv!!.bytes, aesKey),
                requestedSize = ImageSize.THUMB_MEDIUM,
            )
        }
    }
    val loader: AgentUiTreeLoader = koinInject()
    val sender: ChatMessageSenderService = koinInject()
    val scope = rememberCoroutineScope()

    // The inline tree and a tree already in the loader are there on the first frame, so the card is
    // measured at its full height when it scrolls into view. Only a payload tree not loaded yet starts as Loading.
    val loadKeys = arrayOf<Any?>(content.inlineTree, message.fileId, treePayload?.lastModified, treePayload?.iv?.bytes?.contentHashCode(), descriptor.rev, images.keys)
    val firstFrame = remember(*loadKeys) { firstFrameTree(content, descriptor.rev, message, loader) }
    val load: State<TreeLoad> = produceState(firstFrame, *loadKeys) {
        content.inlineTree.toLoad(descriptor.rev)?.let { value = it; return@produceState }
        val results = loader.results(message) ?: run { value = TreeLoad.Failed; return@produceState }
        launch { if (loader.load(message) == null && value == TreeLoad.Loading) value = TreeLoad.Failed }
        // A failed fetch shows the fallback, but a later warm that succeeds still replaces it.
        results.collect { value = it.toLoad(descriptor.rev) ?: TreeLoad.Failed }
    }

    val ready = load.value as? TreeLoad.Ready
    val inputs = rememberSaveable(message.id, saver = AgentUiInputState.Saver) { AgentUiInputState(ready?.tree?.inputs.orEmpty()) }
    LaunchedEffect(ready?.tree) { ready?.let { inputs.reconcile(it.tree.inputs) } }
    LaunchedEffect(message.id, ready != null) {
        if (ready != null) {
            Logger.d(tag = "AgentUi") {
                "ready id=${message.id} source=${if (content.inlineTree != null) "inline" else "payload"}"
            }
        }
    }

    // Saved with the list item, so a sent action stays pending after the card scrolls away and back.
    var sentActions by rememberSaveable(message.id, ready?.rev) { mutableStateOf(emptyList<String>()) }
    var failed by rememberSaveable(message.id) { mutableStateOf(false) }

    val onAction: (AgentUiActionRequest) -> Unit = { req ->
        val rev = ready?.rev
        if (rev != null && req.actionId !in sentActions) {
            sentActions = sentActions + req.actionId
            failed = false
            scope.launch {
                val action = AgentUiActionDescriptor(
                    target = message.id,
                    rev = rev,
                    actionId = req.actionId,
                    label = req.label.truncateToCodePoints(AgentUiActionDescriptor.MAX_LABEL_CP).ifBlank { req.actionId },
                    values = req.values,
                )
                val ok = action.isValid() && try {
                    withContext(NonCancellable) {
                        sender.sendNewTypedMessage(
                            messageUniqueId = Uuid.random(),
                            conversationId = message.conversationId,
                            content = MessageContent.AgentUiAction(action),
                            previousMessageUniqueId = null,
                        )
                    }
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
                if (!ok) {
                    sentActions = sentActions - req.actionId
                    req.rollback.forEach { (id, v) -> inputs[id] = v }
                    failed = true
                }
            }
        }
    }

    AgentUiFrame(ready?.tree?.frameTone ?: Tone.Default, modifier, onLongClick.takeUnless { displayOnly }) {
        if (ready != null) {
            AgentUiTreeView(
                tree = ready.tree,
                inputs = inputs,
                imageData = { images[it] },
                enabled = !displayOnly && !sentByYou,
                pendingActionIds = remember(sentActions) { sentActions.toSet() },
                onAction = onAction,
            )
        } else if (load.value == TreeLoad.Loading) {
            Row(
                Modifier.heightIn(min = LoadingPlaceholderHeight),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(descriptor.fallback, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        } else {
            Text(descriptor.fallback, style = MaterialTheme.typography.bodyMedium)
        }
        if (failed) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.ErrorOutline, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                Text(
                    stringResource(MR.string.chat_agent_ui_action_failed),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

// A payload card holds this height while its tree loads; shorter than most cards, so it grows once and never shrinks.
private val LoadingPlaceholderHeight = 160.dp

@Composable
fun AgentUiFrame(
    tone: Tone,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val container = agentUiContainerColor(tone)
    CompositionLocalProvider(LocalContentColor provides contentColorFor(container)) {
        Column(
            modifier
                .agentUiFrameWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(container)
                .onLongPress(onLongClick)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

// Null off-stream (action-menu preview): any pointer handler there eats the tap the scrim needs to dismiss.
private fun Modifier.onLongPress(onLongClick: (() -> Unit)?): Modifier =
    if (onLongClick != null) combinedClickable(onClick = {}, onLongClick = onLongClick) else this

/** Caps the card at a readable bubble width that grows on wide windows. */
private fun Modifier.agentUiFrameWidth(): Modifier = layout { measurable, constraints ->
    val cap = (if (constraints.maxWidth >= 600.dp.roundToPx()) 520.dp else 360.dp).roundToPx()
    val placeable = measurable.measure(constraints.copy(minWidth = minOf(constraints.minWidth, cap), maxWidth = minOf(constraints.maxWidth, cap)))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

@Composable
fun AgentUiActionBubble(
    descriptor: AgentUiActionDescriptor?,
    authorName: String,
    sentByYou: Boolean,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    if (descriptor == null) {
        UnparseableChip(Icons.Default.TouchApp, modifier)
        return
    }
    val text = if (sentByYou) stringResource(MR.string.chat_agent_ui_action_you, descriptor.label)
    else stringResource(MR.string.chat_agent_ui_action_other, authorName, descriptor.label)
    IconTextChip(
        Icons.Default.TouchApp,
        text,
        MaterialTheme.colorScheme.secondaryContainer,
        MaterialTheme.colorScheme.onSecondaryContainer,
        verticalPadding = 8.dp,
        modifier = modifier,
        onLongClick = onLongClick,
    )
}

@Composable
private fun UnparseableChip(icon: ImageVector, modifier: Modifier) {
    IconTextChip(
        icon,
        stringResource(MR.string.chat_agent_ui_unparseable),
        MaterialTheme.colorScheme.surfaceContainerHigh,
        MaterialTheme.colorScheme.onSurfaceVariant,
        verticalPadding = 10.dp,
        modifier = modifier,
    )
}

@Composable
private fun IconTextChip(icon: ImageVector, text: String, container: Color, onContainer: Color, verticalPadding: Dp, modifier: Modifier, onLongClick: (() -> Unit)? = null) {
    Row(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(container)
            .onLongPress(onLongClick)
            .padding(horizontal = 14.dp, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = onContainer)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = onContainer)
    }
}
