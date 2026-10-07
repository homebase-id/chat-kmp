package id.homebase.chat.viewonce

import id.homebase.api.client.KeyHeader
import id.homebase.api.common.OdinId
import id.homebase.chat.conversationlist.FullScreenOverlay
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.content.MessageContent
import id.homebase.core.util.isMobile
import kotlin.io.encoding.Base64

const val VIEW_ONCE_PAYLOAD_KEY = ChatProtocol.PAYLOAD_KEY_MESSAGE_WEB + "0"

/** The viewer to show for a tap, or null when this copy cannot be opened (sent, opened, expired, desktop, no payload). */
fun viewOnceViewerFor(
    message: MessageUiModel,
    nowMs: Long,
    myOdinId: OdinId?,
    mobile: Boolean = isMobile(),
): FullScreenOverlay.ViewOnceViewer? {
    val descriptor = (message.messageContent as? MessageContent.ViewOnce)?.descriptor ?: return null
    if (!mobile || message.isDeleted || message.isFromActiveUser(myOdinId)) return null
    if (ViewOnceRules.stateOf(message, nowMs, myOdinId) != ViewOnceState.Unopened) return null
    val payload = message.payloads?.firstOrNull { it.key == VIEW_ONCE_PAYLOAD_KEY } ?: return null
    val iv = payload.iv?.let { runCatching { Base64.decode(it) }.getOrNull() } ?: return null
    return FullScreenOverlay.ViewOnceViewer(
        messageId = message.id,
        conversationId = message.conversationId,
        fileId = message.fileId,
        payload = payload,
        keyHeader = KeyHeader(iv = iv, aesKey = message.keyHeader.aesKey),
        kind = descriptor.kind,
    )
}
