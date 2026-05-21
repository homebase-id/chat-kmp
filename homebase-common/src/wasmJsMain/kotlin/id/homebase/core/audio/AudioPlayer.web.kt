package id.homebase.core.audio

// Pre-flight stub. Browser audio playback will use HTMLAudioElement when wired
// up properly; for now this no-ops so chat UI compiles without an audio
// dependency on the web.
private object NoopAudioPlayer : AudioPlayer {
    override fun play(filePath: String) {}
    override fun jump(seconds: Int) {}
    override fun resume() {}
    override fun pause() {}
    override fun stop() {}
    override fun release() {}
    override fun setPlaybackObserver(observer: AudioPlaybackObserver) {}
}

actual fun getAudioPlayer(): AudioPlayer = NoopAudioPlayer
