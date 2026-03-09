package id.homebase.core.audio

interface AudioRecorder {
    fun startRecording(fileName: String)
    fun stopRecording(): String?
}