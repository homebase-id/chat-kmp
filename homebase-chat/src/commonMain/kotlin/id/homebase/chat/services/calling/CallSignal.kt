package id.homebase.chat.services.calling

import kotlin.io.encoding.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class HangupReason { Normal, Declined, Busy, Cancelled, Missed }

/**
 * Call signalling payload, carried as the base64 `blob` of an odin-core LiveRelay message.
 * An [Offer] doubles as the ring (signaling maps it to push kind `ring`); a [Hangup] sent before
 * the call was answered maps to push kind `hangup` with the same `tagId` = callId.
 */
@Serializable
sealed class CallSignal {
    abstract val callId: String

    @Serializable @SerialName("offer")
    data class Offer(override val callId: String, val sdp: String, val video: Boolean) : CallSignal()

    @Serializable @SerialName("ring")
    data class Ring(override val callId: String) : CallSignal()

    @Serializable @SerialName("answer")
    data class Answer(override val callId: String, val sdp: String) : CallSignal()

    @Serializable @SerialName("ice")
    data class Ice(
        override val callId: String,
        val candidate: String,
        val sdpMid: String? = null,
        val sdpMLineIndex: Int? = null,
    ) : CallSignal()

    @Serializable @SerialName("hangup")
    data class Hangup(override val callId: String, val reason: HangupReason = HangupReason.Normal) : CallSignal()
}

data class InboundSignal(val from: String, val signal: CallSignal, val receivedAtMs: Long)

object CallSignalCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }

    fun encode(signal: CallSignal): String =
        Base64.encode(json.encodeToString(CallSignal.serializer(), signal).encodeToByteArray())

    fun decode(blob: String): CallSignal? =
        runCatching {
            json.decodeFromString(CallSignal.serializer(), Base64.decode(blob).decodeToString())
        }.getOrNull()
}
