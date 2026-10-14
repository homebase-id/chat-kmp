package id.homebase.chat.services.calling

import java.io.File
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

interface RingTonePlayer {
    fun start()
    fun stop()
}

sealed interface RingSource {
    data class Command(val argv: List<String>) : RingSource
    data class Wav(val file: File) : RingSource
}

/** What the OS exposes for its ringtone; each lookup is a plain file read or a public CLI, never a private API. */
class RingProbe(
    val exists: (String) -> Boolean,
    val hasCommand: (String) -> Boolean,
    val windowsRingPath: () -> String? = { null },
    val macAlertSound: () -> String? = { null },
)

/** The operating system's own ringtone; nothing is bundled, so an OS without one stays on the system beep. */
fun osRingSource(osName: String, probe: RingProbe): RingSource? {
    val os = osName.lowercase()
    return when {
        "mac" in os -> (MAC_RINGTONES + listOfNotNull(probe.macAlertSound()) + MAC_FALLBACK)
            .firstOrNull(probe.exists)?.let { RingSource.Command(listOf("afplay", it)) }
        "win" in os -> (listOfNotNull(probe.windowsRingPath()) + WINDOWS_RINGTONES)
            .firstOrNull(probe.exists)?.let { RingSource.Wav(File(it)) }
        else -> when {
            probe.hasCommand("canberra-gtk-play") -> RingSource.Command(listOf("canberra-gtk-play", "-i", LINUX_RING_EVENT))
            else -> LINUX_RINGTONES.firstOrNull(probe.exists)?.let { file ->
                val player = listOf("paplay", "pw-play", "ogg123", "mpv").firstOrNull(probe.hasCommand) ?: return null
                RingSource.Command(listOf(player, file))
            }
        }
    }
}

private const val MAC_TONES = "/System/Library/PrivateFrameworks/ToneLibrary.framework/Versions/A/Resources/Ringtones"
private val MAC_RINGTONES = listOf("$MAC_TONES/Reflection.m4r", "$MAC_TONES/Opening.m4r")
private const val MAC_FALLBACK = "/System/Library/Sounds/Glass.aiff"
private val WINDOWS_RINGTONES = listOf("C:\\Windows\\Media\\Ring01.wav", "C:\\Windows\\Media\\Ring02.wav")
private const val LINUX_RING_EVENT = "phone-incoming-call"
private val LINUX_RINGTONES = listOf(
    "/usr/share/sounds/freedesktop/stereo/phone-incoming-call.oga",
    "/usr/share/sounds/freedesktop/stereo/phone-incoming-call.ogg",
)

private fun systemProbe() = RingProbe(
    exists = { File(it).isFile },
    hasCommand = ::commandOnPath,
    windowsRingPath = ::windowsIncomingCallSound,
    macAlertSound = ::macAlertSoundPath,
)

private fun capture(vararg argv: String): String? = runCatching {
    val process = ProcessBuilder(*argv).redirectErrorStream(true).start()
    val out = process.inputStream.bufferedReader().readText()
    if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly()
    out.takeIf { process.exitValue() == 0 }
}.getOrNull()

/** The sound the user assigned to Windows' incoming-call event (Settings > Sounds), if any. */
private fun windowsIncomingCallSound(): String? =
    capture("reg", "query", "HKCU\\AppEvents\\Schemes\\Apps\\.Default\\Notification.Looping.Call\\.Current", "/ve")
        ?.let(::parseRegQueryValue)

internal fun parseRegQueryValue(output: String, env: (String) -> String? = System::getenv): String? {
    val line = output.lineSequence().map { it.trim() }.firstOrNull { "REG_SZ" in it || "REG_EXPAND_SZ" in it } ?: return null
    val raw = line.substringAfter("REG_EXPAND_SZ", line.substringAfter("REG_SZ")).trim().ifBlank { return null }
    return Regex("%([^%]+)%").replace(raw) { env(it.groupValues[1]) ?: it.value }
}

private fun macAlertSoundPath(): String? =
    capture("defaults", "read", "com.apple.systemsound", "com.apple.sound.beep.sound")?.trim()?.ifBlank { null }

class OsRingTonePlayer(
    private val scope: CoroutineScope,
    private val gapMs: Long = 1_000,
    private val source: () -> RingSource? = { osRingSource(System.getProperty("os.name").orEmpty(), systemProbe()) },
) : RingTonePlayer {
    private var job: Job? = null

    @Synchronized
    override fun start() {
        if (job?.isActive == true) return
        val previous = job
        job = scope.launch(Dispatchers.IO) {
            previous?.cancelAndJoin()
            val resolved = source()
            val clip = (resolved as? RingSource.Wav)?.let(::openClip)
            try {
                while (isActive) {
                    when (resolved) {
                        null -> java.awt.Toolkit.getDefaultToolkit().beep()
                        is RingSource.Command -> runCommand(resolved)
                        is RingSource.Wav -> play(clip!!)
                    }
                    delay(gapMs)
                }
            } finally {
                clip?.close()
            }
        }
    }

    @Synchronized
    override fun stop() {
        job?.cancel()
    }

    private suspend fun runCommand(source: RingSource.Command) = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(source.argv)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
        val reaper = Thread { process.destroyForcibly() }
        runCatching { Runtime.getRuntime().addShutdownHook(reaper) }
        try {
            runInterruptible { process.waitFor() }
        } finally {
            process.destroyForcibly()
            runCatching { Runtime.getRuntime().removeShutdownHook(reaper) }
        }
    }

    private fun openClip(source: RingSource.Wav): Clip =
        AudioSystem.getClip().apply { AudioSystem.getAudioInputStream(source.file).use { open(it) } }

    private suspend fun play(clip: Clip) {
        clip.framePosition = 0
        clip.start()
        try {
            delay(clip.microsecondLength / 1_000)
        } finally {
            clip.stop()
        }
    }
}

private fun commandOnPath(name: String): Boolean =
    System.getenv("PATH").orEmpty().split(File.pathSeparator).any { File(it, name).canExecute() }
