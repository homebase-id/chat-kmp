package id.homebase.chat.agentui

import id.homebase.chat.widget.UnparseableTypedChip
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
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.util.truncateToCodePoints
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.ChatMessageSenderService
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.content.MessageContent
import id.homebase.core.config.chatTargetDrive
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.ui.theme.Dimens
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.core.util.supportsTouchGestures
import id.homebase.resources.MR
import id.homebase.resources.chat_agent_ui_action_failed
import id.homebase.resources.chat_agent_ui_action_other
import id.homebase.resources.chat_agent_ui_action_other_on
import id.homebase.resources.chat_agent_ui_action_you
import id.homebase.resources.chat_agent_ui_action_you_on
import id.homebase.resources.chat_agent_ui_open_card
import id.homebase.resources.chat_agent_ui_unparseable
import id.homebase.resources.chat_message_options
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

/** The payloads a card may show as images, in message order; the full-screen viewer pages through these. */
internal fun MessageUiModel.agentUiImagePayloads(): List<PayloadDescriptor> {
    val keys = agentUiImageKeys(payloads)
    return payloads.orEmpty().filter { it.key in keys }
}

private const val TAG = "AgentUi"

private fun logFallback(message: MessageUiModel, rev: Int, why: String) {
    Logger.w(tag = TAG) { "card shows its fallback id=${message.id} rev=$rev: $why" }
}

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
    onImageClick: ((PayloadDescriptor) -> Unit)? = null,
    onImageSecondaryPress: ((PayloadDescriptor) -> Unit)? = null,
    shape: Shape = RoundedCornerShape(Dimens.AgentUi.cardCornerRadius),
    authorName: String? = null,
    authorColor: Color? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    val descriptor = content.descriptor
    if (descriptor == null) {
        UnparseableTypedChip(Icons.Default.SmartToy, stringResource(MR.string.chat_agent_ui_unparseable), modifier)
        return
    }

    val treePayload = message.agentUiTreePayload()
    val aesKey = message.keyHeader.aesKey
    val imagePayloads = remember(message.fileId, message.payloads) { message.agentUiImagePayloads().associateBy { it.key } }
    val images = remember(message.fileId, imagePayloads) {
        imagePayloads.mapValues { (_, p) ->
            HomebaseImageData.from(
                driveId = chatTargetDrive.alias,
                fileId = message.fileId,
                descriptor = p,
                keyHeader = KeyHeader(p.iv!!.bytes, aesKey),
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
        fun fail(why: String) {
            logFallback(message, descriptor.rev, why)
            value = TreeLoad.Failed
        }
        fun show(result: AgentUiTreeResult, source: String) {
            when (result) {
                is AgentUiTreeResult.Valid -> value = TreeLoad.Ready(result.tree, descriptor.rev)
                is AgentUiTreeResult.Invalid -> fail("$source tree invalid: ${result.reason}")
            }
        }
        content.inlineTree?.let { show(it, "inline"); return@produceState }
        val results = loader.results(message)
            ?: return@produceState fail("no inline tree and no ${ChatProtocol.PAYLOAD_KEY_AGENT_UI} payload")
        launch { if (loader.load(message) == null && value == TreeLoad.Loading) fail("tree payload could not be fetched") }
        // A failed fetch shows the fallback, but a later warm that succeeds still replaces it.
        results.collect { show(it, "payload") }
    }

    val ready = load.value as? TreeLoad.Ready
    val inputs = rememberSaveable(message.id, saver = AgentUiInputState.Saver) { AgentUiInputState(ready?.tree?.inputs.orEmpty()) }
    LaunchedEffect(ready?.tree) { ready?.let { inputs.reconcile(it.tree.inputs) } }
    LaunchedEffect(message.id, ready != null) {
        if (ready != null) {
            Logger.d(tag = TAG) {
                "ready id=${message.id} source=${if (content.inlineTree != null) "inline" else "payload"}"
            }
        }
    }

    // Saved with the list item, so a sent action stays pending after the card scrolls away and back.
    var sentActions by rememberSaveable(message.id, ready?.rev) { mutableStateOf(emptyList<String>()) }
    // Sends that succeeded: the control settles on a check instead of spinning until the agent edits the card.
    var deliveredActions by rememberSaveable(message.id, ready?.rev) { mutableStateOf(emptyList<String>()) }
    var failed by rememberSaveable(message.id) { mutableStateOf(false) }

    // Replies already in the conversation outlive this item's saved state, so a reopened chat still sends each action once per rev.
    val conversation = LocalAgentUiConversation.current
    val replied = remember(conversation, message.id, ready?.rev) { ready?.let { conversation.answeredByYou(message.id, it.rev) }.orEmpty() }
    val answered = remember(replied, deliveredActions) { replied + deliveredActions }

    val onAction: (AgentUiActionRequest) -> Unit = { req ->
        val rev = ready?.rev
        if (rev != null && req.actionId !in sentActions && req.actionId !in answered) {
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
                val valid = action.isValid()
                if (!valid) Logger.w(tag = TAG) { "action not sent id=${message.id} action=${req.actionId}: descriptor over its limits" }
                val ok = valid && try {
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
                    Logger.w(e, tag = TAG) { "action send failed id=${message.id} action=${req.actionId}" }
                    false
                }
                if (ok) {
                    deliveredActions = deliveredActions + req.actionId
                } else {
                    sentActions = sentActions - req.actionId
                    req.rollback.forEach { (id, v) -> inputs[id] = v }
                    failed = true
                }
            }
        }
    }

    AgentUiFrame(sentByYou, modifier, shape, authorName, authorColor, footer) {
        if (ready != null) {
            AgentUiTreeView(
                tree = ready.tree,
                inputs = inputs,
                imageData = { images[it] },
                enabled = !displayOnly && !sentByYou,
                copyEnabled = !displayOnly,
                pendingActionIds = remember(sentActions, answered) { sentActions.toSet() + answered },
                answeredActionIds = answered,
                onAction = onAction,
                onImageClick = onImageClick?.let { open -> { key -> imagePayloads[key]?.let(open) } },
                onLongClick = onLongClick.takeUnless { displayOnly },
                onImageSecondaryPress = onImageSecondaryPress?.takeUnless { displayOnly }?.let { menu -> { key -> imagePayloads[key]?.let(menu) } },
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

/**
 * The card's bubble. It takes no gesture of its own, like the dice and unknown bubbles, so long-press, double-tap
 * to react and right-click reach the message wrapper around it, and nothing highlights the whole card.
 * Controls inside keep their own clicks.
 */
@Composable
fun AgentUiFrame(
    sentByYou: Boolean,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Dimens.AgentUi.cardCornerRadius),
    authorName: String? = null,
    authorColor: Color? = null,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val container = if (sentByYou) HomebaseTheme.extendedColors.bubbleSentSurface else MaterialTheme.colorScheme.surfaceContainerHigh
    val onContainer = if (sentByYou) HomebaseTheme.extendedColors.bubbleSentOnSurface else MaterialTheme.colorScheme.onSurface
    CompositionLocalProvider(LocalContentColor provides onContainer, LocalAgentUiContainer provides container) {
        Column(
            modifier
                .agentUiFrameWidth()
                .clip(shape)
                .background(container)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (authorName != null) {
                Text(
                    authorName,
                    style = MaterialTheme.typography.labelLarge,
                    color = authorColor ?: onContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            content()
            if (footer != null) Box(Modifier.align(Alignment.End)) { footer() }
        }
    }
}

/** Caps the card at a readable bubble width that grows on wide windows. */
private fun Modifier.agentUiFrameWidth(): Modifier = layout { measurable, constraints ->
    val cap = (if (constraints.maxWidth >= Dimens.AgentUi.wideBreakpoint.roundToPx()) Dimens.AgentUi.cardMaxWidthWide else Dimens.AgentUi.cardMaxWidth).roundToPx()
    val placeable = measurable.measure(constraints.copy(minWidth = minOf(constraints.minWidth, cap), maxWidth = minOf(constraints.maxWidth, cap)))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/**
 * A person's action reply, drawn as a quiet line under the card rather than a bubble: it names the card it
 * answered when that card is loaded ("You chose Keep on “Post plan”"), and tapping it jumps to the card.
 */
@Composable
fun AgentUiActionBubble(
    descriptor: AgentUiActionDescriptor?,
    authorName: String,
    sentByYou: Boolean,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onOpenCard: ((Uuid) -> Unit)? = null,
) {
    if (descriptor == null) {
        UnparseableTypedChip(Icons.Default.TouchApp, stringResource(MR.string.chat_agent_ui_unparseable), modifier)
        return
    }
    val subject = LocalAgentUiConversation.current.subjects[descriptor.target]
    val text = when {
        subject != null && sentByYou -> stringResource(MR.string.chat_agent_ui_action_you_on, descriptor.label, subject)
        subject != null -> stringResource(MR.string.chat_agent_ui_action_other_on, authorName, descriptor.label, subject)
        sentByYou -> stringResource(MR.string.chat_agent_ui_action_you, descriptor.label)
        else -> stringResource(MR.string.chat_agent_ui_action_other, authorName, descriptor.label)
    }
    val values = remember(descriptor) { descriptor.shortValues() }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val open = onOpenCard?.takeIf { subject != null }
    // The link takes the press, so it carries the touch menu too; right-click still reaches the message wrapper.
    val longPress = onLongClick?.takeIf { supportsTouchGestures() }
    Row(
        modifier
            .clip(RoundedCornerShape(Dimens.AgentUi.actionCornerRadius))
            .then(
                // Without a card to open it takes no gesture, so the message wrapper gets long-press and right-click.
                if (open != null) {
                    Modifier.combinedClickable(
                        onClickLabel = stringResource(MR.string.chat_agent_ui_open_card),
                        role = Role.Button,
                        onLongClickLabel = longPress?.let { stringResource(MR.string.chat_message_options) },
                        onLongClick = longPress,
                        onClick = { open(descriptor.target) },
                    )
                } else Modifier,
            )
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.TouchApp, contentDescription = null, modifier = Modifier.size(14.dp), tint = muted)
        Spacer(Modifier.width(6.dp))
        Column {
            Text(text, style = MaterialTheme.typography.labelMedium, color = muted)
            values?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 1) }
        }
    }
}
