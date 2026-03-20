package id.homebase.core.audio

interface AudioPlayer {
    fun play(filePath: String)
    fun jump(seconds: Int)
    fun resume()
    fun pause()
    fun stop()
    fun release()

    fun setPlaybackObserver(observer: AudioPlaybackObserver)
}

interface AudioPlaybackObserver {
    fun onComplete()
    fun onProgressUpdate(progressSeconds: Int, totalSeconds: Int)
}

expect fun getAudioPlayer(): AudioPlayer


