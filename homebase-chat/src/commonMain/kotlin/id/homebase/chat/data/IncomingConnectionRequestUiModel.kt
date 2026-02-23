package id.homebase.chat.data

import androidx.compose.runtime.Immutable
import kotlin.uuid.Uuid
import id.homebase.api.common.OdinId
import id.homebase.api.common.time.UnixTimeUtc

@Immutable
data class IncomingConnectionRequestUiModel(
    val contactData: String? = null,
    val senderName: String,
    val senderOdinId: OdinId,
    val circleIds: List<Uuid>? = null,
    val message: String? = null,
    val introducerOdinId: OdinId? = null,
    val receivedTimestampMilliseconds: UnixTimeUtc,
//    val connectionRequestOrigin: String,
)



@Immutable
data class OutgoingConnectionRequestUiModel(
    val message: String? = null,
    val introducerOdinId: OdinId? = null,
    val receivedTimestampMilliseconds: UnixTimeUtc,
    val recipientOdinId: OdinId,
    val recipientName: String,
//    val senderOdinId: OdinId,
//    val circleIds: List<Uuid>? = null,
//    val connectionRequestOrigin: String,
//    val contactData: String? = null,
    )
