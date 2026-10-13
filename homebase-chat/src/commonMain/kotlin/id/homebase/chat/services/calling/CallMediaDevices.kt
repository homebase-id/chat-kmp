package id.homebase.chat.services.calling

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class MediaDeviceKind { Mic, Speaker, Camera }

data class MediaDevice(val id: String, val name: String, val kind: MediaDeviceKind, val isDefault: Boolean)

sealed interface MediaDeviceNotice {
    data class Disconnected(val kind: MediaDeviceKind, val name: String, val fallbackName: String) : MediaDeviceNotice

    /** The user's remembered device was plugged back in while the call runs on a fallback; switching back is their call. */
    data class Returned(val kind: MediaDeviceKind, val id: String, val name: String) : MediaDeviceNotice
}

/** Live input/output choice for a call; [select] may block on native audio or capture, so call it off the main thread. */
interface CallMediaDevices {
    val devices: StateFlow<List<MediaDevice>>
    val selected: StateFlow<Map<MediaDeviceKind, String?>>
    val micLevel: StateFlow<Float>
    val events: SharedFlow<MediaDeviceNotice>
    fun select(kind: MediaDeviceKind, id: String)
}

/** The last descriptor the user picked per kind, so the next call opens on it. */
interface CallDevicePreferences {
    fun get(kind: MediaDeviceKind): String?
    fun set(kind: MediaDeviceKind, id: String)
}

class InMemoryCallDevicePreferences : CallDevicePreferences {
    private val values = mutableMapOf<MediaDeviceKind, String>()
    override fun get(kind: MediaDeviceKind): String? = values[kind]
    override fun set(kind: MediaDeviceKind, id: String) { values[kind] = id }
}

/** 0..32767 signal level to a 0..1 meter; the square root lifts speech out of the bottom of the range. */
fun normalizeMicLevel(raw: Int): Float {
    val linear = (raw / MAX_SIGNAL_LEVEL).coerceIn(0f, 1f)
    return kotlin.math.sqrt(linear)
}

private const val MAX_SIGNAL_LEVEL = 32767f

/**
 * Device list, selection, hot-plug fallback and remembered choice, over a platform's [enumerate] and [apply].
 * [serialize] runs every state-changing step under the platform's own lock so [enumerate] and [apply] never overlap.
 */
class ManagedCallMediaDevices(
    private val enumerate: () -> List<MediaDevice>,
    private val apply: (MediaDevice) -> Unit,
    private val preferences: CallDevicePreferences,
    private val serialize: (() -> Unit) -> Unit = { it() },
    private val onApplyFailed: (MediaDevice, Throwable) -> Unit = { _, _ -> },
) : CallMediaDevices {
    private val _devices = MutableStateFlow<List<MediaDevice>>(emptyList())
    private val _selected = MutableStateFlow<Map<MediaDeviceKind, String?>>(emptyMap())
    private val _micLevel = MutableStateFlow(0f)
    private val _events = MutableSharedFlow<MediaDeviceNotice>(extraBufferCapacity = 8)

    override val devices: StateFlow<List<MediaDevice>> = _devices.asStateFlow()
    override val selected: StateFlow<Map<MediaDeviceKind, String?>> = _selected.asStateFlow()
    override val micLevel: StateFlow<Float> = _micLevel.asStateFlow()
    override val events: SharedFlow<MediaDeviceNotice> = _events.asSharedFlow()

    fun setMicLevel(level: Float) { _micLevel.value = level.coerceIn(0f, 1f) }

    /** Re-reads the hardware: follows a hot-plug, and moves a selection whose device vanished to the fallback. */
    fun refresh() = serialize {
        val previous = _devices.value
        val current = enumerate()
        _devices.value = current
        for (kind in MediaDeviceKind.entries) {
            val ofKind = current.filter { it.kind == kind }
            val chosenId = _selected.value[kind]
            if (chosenId != null && ofKind.any { it.id == chosenId }) {
                val wanted = preferences.get(kind)
                val back = ofKind.firstOrNull { it.id == wanted && it.id != chosenId }
                if (back != null && previous.isNotEmpty() && previous.none { it.kind == kind && it.id == back.id }) {
                    _events.tryEmit(MediaDeviceNotice.Returned(kind, back.id, back.name))
                }
                continue
            }
            val fallback = fallbackFor(ofKind)
            _selected.update { it + (kind to fallback?.id) }
            if (chosenId == null || fallback == null) continue
            val gone = previous.firstOrNull { it.kind == kind && it.id == chosenId }
            tryApply(fallback)
            _events.tryEmit(MediaDeviceNotice.Disconnected(kind, gone?.name ?: chosenId, fallback.name))
        }
    }

    /** Opens the call on each remembered device that is present and not already in use. */
    fun applyPreferences() = serialize {
        for (kind in MediaDeviceKind.entries) {
            val wanted = preferences.get(kind) ?: continue
            if (_selected.value[kind] == wanted) continue
            val device = _devices.value.firstOrNull { it.kind == kind && it.id == wanted } ?: continue
            if (tryApply(device)) _selected.update { it + (kind to device.id) }
        }
    }

    /** Records a device the platform switched to on its own, without remembering it as the user's choice. */
    fun markApplied(kind: MediaDeviceKind, id: String) { _selected.update { it + (kind to id) } }

    override fun select(kind: MediaDeviceKind, id: String) = serialize {
        val device = _devices.value.firstOrNull { it.kind == kind && it.id == id } ?: return@serialize
        if (_selected.value[kind] == id) return@serialize
        if (!tryApply(device)) return@serialize
        _selected.update { it + (kind to id) }
        preferences.set(kind, id)
    }

    private fun tryApply(device: MediaDevice): Boolean = try {
        apply(device)
        true
    } catch (e: Exception) {
        onApplyFailed(device, e)
        false
    }

    private fun fallbackFor(ofKind: List<MediaDevice>): MediaDevice? = ofKind.firstOrNull { it.isDefault } ?: ofKind.firstOrNull()
}

internal val NoCallMediaDevices: StateFlow<CallMediaDevices?> = MutableStateFlow(null)
