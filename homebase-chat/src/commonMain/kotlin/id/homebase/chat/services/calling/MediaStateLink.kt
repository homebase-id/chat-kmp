package id.homebase.chat.services.calling

import co.touchlab.kermit.Logger
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class MediaStateMessage(val video: Boolean, val audio: Boolean, val seq: Long)

/** Both ends create the in-call data channel themselves with this id, so it needs no in-band open handshake. */
internal const val MEDIA_STATE_CHANNEL_ID = 0
internal const val MEDIA_STATE_CHANNEL_LABEL = "media-state"
internal const val MEDIA_STATE_RESEND_MS = 1_000L

/**
 * The camera and mic state each side tells the other over the in-call data channel, shaped like Signal's SenderStatus:
 * a change bumps `seq`, the engine resends the current state every [MEDIA_STATE_RESEND_MS], and the receiver keeps only
 * a newer `seq`. It is the only source of the peer's video state: every call negotiates a video track at setup, so a
 * remote track arriving says nothing about whether the peer's camera is on.
 */
@OptIn(ExperimentalAtomicApi::class)
class MediaStateLink(private val tag: String, private val emit: (MediaEvent) -> Unit) {
    private data class Local(
        val camera: Boolean = false,
        val mic: Boolean = true,
        val interrupted: Boolean = false,
        val capturerEnded: Boolean = false,
        val seq: Long = 0,
    ) {
        val video get() = camera && !interrupted && !capturerEnded
        fun message() = MediaStateMessage(video, mic, seq)
    }

    private data class Remote(val seq: Long = -1, val video: Boolean? = null, val audio: Boolean? = null)

    private data class Shown(val video: Boolean? = null, val audio: Boolean? = null)

    private val local = AtomicReference(Local())
    private val remote = AtomicReference(Remote())
    private val shown = AtomicReference(Shown())
    private val publisher = SerialDrain(::publishRemote)
    private val transport = AtomicReference<((ByteArray) -> Boolean)?>(null)

    fun setCamera(on: Boolean) = change("user") { it.copy(camera = on) }

    fun setMic(on: Boolean) = change("user") { it.copy(mic = on) }

    fun cameraInterrupted(interrupted: Boolean, detail: String) =
        change(if (interrupted) "interrupted($detail)" else "interruption ended($detail)") { it.copy(interrupted = interrupted) }

    fun capturerEnded() = change("capturer ended") { it.copy(capturerEnded = true) }

    fun capturerRestored() = change("capturer restored") { it.copy(capturerEnded = false) }

    fun opened(send: (ByteArray) -> Boolean) {
        transport.store(send)
        val state = local.load().message()
        Logger.i(tag) { "media-state channel open, sending video=${state.video} audio=${state.audio} seq=${state.seq}" }
        transmit(state)
    }

    fun closed() = transport.store(null)

    fun resend() = transmit(local.load().message())

    fun received(bytes: ByteArray) {
        val message = runCatching { json.decodeFromString(MediaStateMessage.serializer(), bytes.decodeToString()) }
            .onFailure { Logger.w(tag) { "media-state unreadable, dropped: ${it.message}" } }
            .getOrNull() ?: return
        while (true) {
            val before = remote.load()
            if (message.seq <= before.seq) return
            if (!remote.compareAndSet(before, before.copy(seq = message.seq, video = message.video, audio = message.audio))) continue
            if (before.video != message.video || before.audio != message.audio) {
                Logger.i(tag) { "media-state received video=${message.video} audio=${message.audio} seq=${message.seq}" }
            }
            publisher.request()
            return
        }
    }

    // Runs on one thread at a time and always from the latest remote state, so a stale value can never be sent after a newer one.
    private fun publishRemote() {
        val now = remote.load()
        val before = shown.load()
        shown.store(Shown(now.video, now.audio))
        if (now.video != null && now.video != before.video) emit(MediaEvent.RemoteVideoChanged(now.video))
        if (now.audio != null && now.audio != before.audio) emit(MediaEvent.RemoteMuteChanged(!now.audio))
    }

    private fun change(reason: String, update: (Local) -> Local) {
        while (true) {
            val before = local.load()
            val updated = update(before)
            val changed = updated.video != before.video || updated.mic != before.mic
            val after = if (changed) updated.copy(seq = before.seq + 1) else updated
            if (!local.compareAndSet(before, after)) continue
            if (!changed) return
            val state = after.message()
            val open = transport.load() != null
            Logger.i(tag) { "media-state sent video=${state.video} audio=${state.audio} seq=${state.seq} reason=$reason open=$open" }
            transmit(state)
            return
        }
    }

    private fun transmit(state: MediaStateMessage) {
        val send = transport.load() ?: return
        if (!send(json.encodeToString(MediaStateMessage.serializer(), state).encodeToByteArray())) {
            Logger.w(tag) { "media-state send refused by the data channel seq=${state.seq}" }
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
