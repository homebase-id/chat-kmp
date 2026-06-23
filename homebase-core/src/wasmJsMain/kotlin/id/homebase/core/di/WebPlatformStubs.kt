package id.homebase.core.di

import id.homebase.chat.dice.ShakeDetector
import id.homebase.chat.dice.ShakeEvent
import id.homebase.core.audio.AudioFileInfo
import id.homebase.core.audio.AudioRecorder
import id.homebase.core.audio.AudioWaveFormGenerator
import id.homebase.core.gallery.GalleryImage
import id.homebase.core.gallery.PlatformGalleryManager
import id.homebase.core.updater.UpdateAppManager
import id.homebase.core.updater.UpdateAppModel
import id.homebase.core.updater.UpdateResult
import id.homebase.core.util.PlatformInfo
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Web stubs for platform capabilities that don't have a browser implementation yet (audio
 * recording, gallery access, native updates, shake detection, on-disk DB size). They exist so the
 * Koin graph the view models depend on resolves on wasmJs; the features are simply inert on web.
 */

internal class WebUpdateAppManager : UpdateAppManager {
    override suspend fun checkForUpdate(): UpdateAppModel = UpdateAppModel(updateAvailable = false)
    override suspend fun downloadUpdate(): UpdateResult = UpdateResult.Unsupported
}

internal class WebGalleryManager : PlatformGalleryManager {
    // The browser has no system photo gallery to enumerate; attachments come via file pickers.
    override suspend fun fetchGalleryImages(limit: Int): List<GalleryImage> = emptyList()
}

internal class WebPlatformInfo : PlatformInfo {
    override val versionName: String = "web"
    override val versionCode: Int = 1

    // The browser tab has no OS background-wake — it only runs while open.
    override val supportsBackgroundWake: Boolean = false
}

internal class WebAudioRecorder : AudioRecorder {
    override fun getAudioFileExtension(): String = "webm"
    override fun startRecording(fileName: String) {}
    override fun stopRecording(): String? = null
}

internal class WebAudioWaveFormGenerator : AudioWaveFormGenerator {
    override fun generateWaveForm(file: PlatformFile): AudioFileInfo =
        AudioFileInfo(durationUs = 0L, waveFormBytes = ByteArray(0))

    override fun saveWaveformToPng(amplitudes: FloatArray, width: Int, height: Int): ByteArray =
        ByteArray(0)
}

internal class WebShakeDetector : ShakeDetector {
    override val isAvailable: Boolean = false
    override fun events(): Flow<ShakeEvent> = emptyFlow()
}
