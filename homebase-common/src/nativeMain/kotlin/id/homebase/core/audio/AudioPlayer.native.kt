package id.homebase.core.audio

actual fun getAudioPlayer(): AudioPlayer {
    return IOSAudioPlayer()
}