package id.homebase.chat.services.calling

import id.homebase.api.crypto.HashUtil
import id.homebase.core.notifications.CallPushRouter
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class HangupReason { Normal, Declined, Busy, Cancelled, Missed, AnsweredElsewhere, DeclinedElsewhere }

/** Sent by the device that took the call to its own identity, so the callee's other devices stop ringing. */
val HangupReason.isElsewhere: Boolean get() = this == HangupReason.AnsweredElsewhere || this == HangupReason.DeclinedElsewhere

/**
 * Call signalling payload, carried as the base64 `blob` of an odin-core LiveRelay message.
 * An [Offer] doubles as the ring (maps to push kind `ring`); a [Hangup] sent before the call was
 * answered maps to push kind `hangup` with the same `tagId` = callId. [Ringing] is the callee's
 * "my phone is ringing" acknowledgement and has nothing to do with the push kind.
 */
@Serializable
sealed class CallSignal {
    abstract val callId: String

    /** [seq] orders re-published offers of one call; the first offer is 0 and is omitted from the wire. */
    @Serializable @SerialName("offer")
    data class Offer(override val callId: String, val sdp: String, val video: Boolean, val seq: Int = 0) : CallSignal()

    @Serializable @SerialName("ringing")
    data class Ringing(override val callId: String) : CallSignal()

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

    /** Caller's ICE-restart offer for an established call. */
    @Serializable @SerialName("restart")
    data class Restart(override val callId: String, val sdp: String) : CallSignal()

    @Serializable @SerialName("restart_answer")
    data class RestartAnswer(override val callId: String, val sdp: String) : CallSignal()
}

data class InboundSignal(val from: String, val signal: CallSignal, val receivedAtMs: Long)

enum class CallPushKind(val wire: String) { Ring("ring"), Hangup("hangup") }

data class CallPush(val kind: CallPushKind, val typeId: String, val tagId: String, val message: String)

/** [channelKey] is the callee's [CallChannel] key; the receiver listens on its own and drops the rest. */
data class OutboundSignal(
    val peer: String,
    val channelKey: String,
    val signal: CallSignal,
    val push: CallPush? = null,
)

interface CallSignaling {
    val incoming: kotlinx.coroutines.flow.Flow<InboundSignal>

    suspend fun send(outbound: OutboundSignal)
}

object CallChannel {
    const val RING_TYPE_ID = CallPushRouter.RING_TYPE_ID
    const val HANGUP_TYPE_ID = CallPushRouter.HANGUP_TYPE_ID

    /** Every signal addressed to an identity travels on that identity's key, so each side opens only its own. */
    fun keyFor(odinId: String): String {
        val hash = HashUtil.sha256Sync("homebase-call-v1:${odinId.lowercase()}".encodeToByteArray())
        val bytes = hash.copyOf(16)
        bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x50).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
        return Uuid.fromByteArray(bytes).toString()
    }

    /** Ring only for the first [CallSignal.Offer] (re-published offers are silent); hangup only for a caller-side retraction (Cancelled/Missed) before the answer. */
    fun outbound(peer: String, signal: CallSignal, selfLabel: String): OutboundSignal {
        val push = when {
            signal is CallSignal.Offer && signal.seq == 0 ->
                CallPush(CallPushKind.Ring, RING_TYPE_ID, signal.callId, "$selfLabel is calling")
            signal is CallSignal.Hangup && (signal.reason == HangupReason.Cancelled || signal.reason == HangupReason.Missed) ->
                CallPush(CallPushKind.Hangup, HANGUP_TYPE_ID, signal.callId, "Missed call from $selfLabel")
            signal is CallSignal.Hangup && signal.reason.isElsewhere ->
                CallPush(CallPushKind.Hangup, HANGUP_TYPE_ID, signal.callId, "Call handled on another device")
            else -> null
        }
        return OutboundSignal(peer, keyFor(peer), signal, push)
    }
}

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

/** Short code both ends derive from the two DTLS fingerprints; reading it aloud detects a signalling-path swap. */
object CallVerification {
    private val fingerprint = Regex("a=fingerprint:(\\S+) (\\S+)")

    fun code(offerSdp: String, answerSdp: String): String? {
        val a = fingerprint.find(offerSdp)?.value ?: return null
        val b = fingerprint.find(answerSdp)?.value ?: return null
        val hash = HashUtil.sha256Sync(listOf(a, b).sorted().joinToString("|").encodeToByteArray())
        val n = ((hash[0].toInt() and 0xFF) shl 16 or ((hash[1].toInt() and 0xFF) shl 8) or (hash[2].toInt() and 0xFF)) % 1_000_000
        val s = n.toString().padStart(6, '0')
        return "${s.substring(0, 3)} ${s.substring(3)}"
    }
}
