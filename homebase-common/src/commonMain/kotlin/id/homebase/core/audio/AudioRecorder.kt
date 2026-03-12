package id.homebase.core.audio

interface AudioRecorder {

    fun getAudioFileExtension(): String
    fun startRecording(fileName: String)
    fun stopRecording(): String?
}