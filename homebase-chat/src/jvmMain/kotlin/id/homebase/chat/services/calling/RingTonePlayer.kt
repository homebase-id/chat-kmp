package id.homebase.chat.services.calling

import java.io.File
import javax.sound.sampled.AudioSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

/** The operating system's own ringtone; nothing is bundled, so an OS without one stays on the system beep. */
fun osRingSource(osName: String, exists: (String) -> Boolean, hasCommand: (String) -> Boolean): RingSource? {
    val os = osName.lowercase()
    return when {
        "mac" in os -> MAC_RINGTONES.firstOrNull(exists)?.let { RingSource.Command(listOf("afplay", it)) }
        "win" in os -> WINDOWS_RINGTONES.firstOrNull(exists)?.let { RingSource.Wav(File(it)) }
        else -> LINUX_RINGTONES.firstOrNull(exists)?.let { file ->
            val player = listOf("paplay", "pw-play", "ogg123", "mpv").firstOrNull(hasCommand) ?: return null
            RingSource.Command(listOf(player, file))
        }
    }
}

private const val MAC_TONES = "/System/Library/PrivateFrameworks/ToneLibrary.framework/Versions/A/Resources/Ringtones"
private val MAC_RINGTONES = listOf("$MAC_TONES/Reflection.m4r", "$MAC_TONES/Opening.m4r", "/System/Library/Sounds/Glass.aiff")
private val WINDOWS_RINGTONES = listOf("C:\\Windows\\Media\\Ring01.wav", "C:\\Windows\\Media\\Ring02.wav")
private val LINUX_RINGTONES = listOf(
    "/usr/share/sounds/freedesktop/stereo/phone-incoming-call.oga",
    "/usr/share/sounds/freedesktop/stereo/phone-incoming-call.ogg",
)

class OsRingTonePlayer(
    private val scope: CoroutineScope,
    private val gapMs: Long = 1_000,
    private val source: () -> RingSource? = {
        osRingSource(System.getProperty("os.name").orEmpty(), { File(it).isFile }, ::commandOnPath)
    },
) : RingTonePlayer {
    private var job: Job? = null

    @Synchronized
    override fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            val resolved = source()
            while (isActive) {
                if (resolved == null) java.awt.Toolkit.getDefaultToolkit().beep() else playOnce(resolved)
                delay(gapMs)
            }
        }
    }

    @Synchronized
    override fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun playOnce(source: RingSource) = withContext(Dispatchers.IO) {
        when (source) {
            is RingSource.Command -> {
                val process = ProcessBuilder(source.argv).redirectErrorStream(true).start()
                try {
                    runInterruptible { process.inputStream.readBytes(); process.waitFor() }
                } finally {
                    process.destroy()
                }
            }
            is RingSource.Wav -> {
                val clip = AudioSystem.getClip()
                try {
                    AudioSystem.getAudioInputStream(source.file).use { clip.open(it) }
                    clip.start()
                    delay(clip.microsecondLength / 1_000)
                } finally {
                    clip.stop()
                    clip.close()
                }
            }
        }
    }
}

private fun commandOnPath(name: String): Boolean =
    System.getenv("PATH").orEmpty().split(File.pathSeparator).any { File(it, name).canExecute() }
