package id.homebase.chat.services

import id.homebase.api.client.drives.files.RecipientTransferHistoryEntry
import id.homebase.api.client.drives.files.TransferStatus
import id.homebase.resources.MR
import id.homebase.resources.error_access_denied
import id.homebase.resources.error_delivery_failed
import id.homebase.resources.error_server_not_responding
import id.homebase.resources.error_too_many_attempts
import org.jetbrains.compose.resources.StringResource

fun RecipientTransferHistoryEntry.toChatDeliveryStatus(): ChatDeliveryStatus = when {
    TransferStatus.isFailedStatus(latestTransferStatus) -> ChatDeliveryStatus.Failed
    isRead -> ChatDeliveryStatus.Read
    latestTransferStatus == TransferStatus.Delivered -> ChatDeliveryStatus.Delivered
    isInOutbox -> ChatDeliveryStatus.Sending
    else -> ChatDeliveryStatus.Sent
}

fun TransferStatus.toErrorDetailRes(): StringResource? = when (this) {
    TransferStatus.RecipientServerNotResponding -> MR.string.error_server_not_responding
    TransferStatus.RecipientIdentityReturnedAccessDenied -> MR.string.error_access_denied
    TransferStatus.SendingServerTooManyAttempts -> MR.string.error_too_many_attempts
    else -> if (TransferStatus.isFailedStatus(this)) MR.string.error_delivery_failed else null
}

enum class ChatDeliveryStatus(val value: Int) {
    /** Message is currently being sent; Used for optimistic updates */
    Sending(15),

    /** Message has been sent and delivered to your identity */
    Sent(20),

    /** Message has been delivered to the recipient's inbox */
    Delivered(30),

    /** Message has been read by the recipient */
    Read(40),

    /** Message failed to send to the recipient */
    Failed(50);

    companion object {
        fun fromValue(value: Int): ChatDeliveryStatus? = entries.find { it.value == value }
    }
}