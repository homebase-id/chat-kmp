package id.homebase.chat.agentui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import id.homebase.api.client.KeyHeader
import id.homebase.api.util.truncateToCodePoints
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.ChatMessageActionService
import id.homebase.chat.services.ChatMessageSenderService
import id.homebase.chat.services.ChatProtocol
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private sealed interface TreeLoad {
    data object Loading : TreeLoad
    data object Failed : TreeLoad
    data class Ready(val tree: AgentUiTree, val rev: Int) : TreeLoad
}

@OptIn(ExperimentalUuidApi::class)
@Composable
fun AgentUiBubble(
    descriptor: AgentUiDescriptor?,
    message: MessageUiModel,
    displayOnly: Boolean,
    sentByYou: Boolean,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    if (descriptor == null) {
        UnparseableChip(Icons.Default.SmartToy, modifier)
        return
    }

    val treePayload = message.payloads?.firstOrNull { it.key == ChatProtocol.PAYLOAD_KEY_AGENT_UI }
    val aesKey = message.keyHeader.aesKey
    val images = remember(message.fileId, message.payloads) {
        message.payloads.orEmpty()
            .filter { it.isImage() && AgentUiLimits.IMAGE_KEY.matches(it.key) && it.iv != null }
            .associate { p ->
                p.key to HomebaseImageData(
                    driveId = chatTargetDrive.alias,
                    fileId = message.fileId,
                    payloadKey = p.key,
                    previewThumbnail = p.previewThumbnail?.toEmbeddedThumb(),
                    requestedSize = ImageSize.THUMB_MEDIUM,
                    isEncrypted = true,
                    lastModified = p.lastModified,
                    payloadContentType = p.contentType,
                    keyHeader = KeyHeader(p.iv!!.bytes, aesKey),
                    loadFullPayload = true,
                )
            }
    }
    val actionService: ChatMessageActionService = koinInject()
    val sender: ChatMessageSenderService = koinInject()
    val scope = rememberCoroutineScope()

    val load: State<TreeLoad> = produceState<TreeLoad>(
        TreeLoad.Loading,
        message.fileId, treePayload?.lastModified, treePayload?.iv?.bytes?.contentHashCode(), descriptor.rev, images.keys,
    ) {
        val iv = treePayload?.iv?.bytes
        if (treePayload == null || iv == null) {
            value = TreeLoad.Failed
            return@produceState
        }
        val bytes = try {
            actionService.getPayloadBytesAt(message.fileId, ChatProtocol.PAYLOAD_KEY_AGENT_UI, KeyHeader(iv, aesKey), treePayload.lastModified)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        value = if (bytes == null) {
            TreeLoad.Failed
        } else when (val r = withContext(Dispatchers.Default) { AgentUiTree.decode(bytes, images.keys) }) {
            is AgentUiTreeResult.Valid -> TreeLoad.Ready(r.tree, descriptor.rev)
            is AgentUiTreeResult.Invalid -> TreeLoad.Failed
        }
    }

    val inputs = remember(message.id) { AgentUiInputState() }
    val ready = load.value as? TreeLoad.Ready
    LaunchedEffect(ready?.tree) { ready?.let { inputs.reconcile(it.tree.inputs) } }

    var sentActions by remember(message.id) { mutableStateOf(emptySet<Pair<Int, String>>()) }
    var failed by remember(message.id) { mutableStateOf(false) }

    val onAction: (AgentUiActionRequest) -> Unit = { req ->
        val rev = ready?.rev
        if (rev != null && (rev to req.actionId) !in sentActions) {
            val mark = rev to req.actionId
            sentActions = sentActions + mark
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
                    sentActions = sentActions - mark
                    failed = true
                }
            }
        }
    }

    Column(
        modifier
            .widthIn(max = 360.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(
                if (onLongClick != null) Modifier.pointerInput(onLongClick) { detectTapGestures(onLongPress = { onLongClick() }) }
                else Modifier
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (ready != null) {
            val pending = remember(sentActions, ready.rev) { sentActions.filter { it.first == ready.rev }.mapTo(HashSet()) { it.second } }
            AgentUiTreeView(
                tree = ready.tree,
                inputs = inputs,
                imageData = { images[it] },
                enabled = !displayOnly && !sentByYou,
                pendingActionIds = pending,
                onAction = onAction,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(descriptor.fallback, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
                if (load.value == TreeLoad.Loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }
        if (failed) {
            Text(
                stringResource(MR.string.chat_agent_ui_action_failed),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
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
    Row(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .then(
                if (onLongClick != null) Modifier.pointerInput(onLongClick) { detectTapGestures(onLongPress = { onLongClick() }) }
                else Modifier
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.TouchApp, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
private fun UnparseableChip(icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(MR.string.chat_agent_ui_unparseable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
        )
    }
}
