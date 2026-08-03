package id.homebase.core.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build

class AndroidAudioRecorder(
    private val context: Context,
) : AudioRecorder {
    private var recorder: MediaRecorder? = null
    private var audioFileName: String? = null

    override fun getAudioFileExtension(): String {
        return "m4a"
    }

    override fun startRecording(fileName: String) {
        audioFileName = fileName
        recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            // Unset, MediaRecorder picked 8 kHz / 12 kbps on real hardware (#1202)
            setAudioSamplingRate(44_100)
            setAudioEncodingBitRate(96_000)
            setAudioChannels(1)
            setOutputFile(fileName)
            prepare()
            start()
        }
    }

    override fun stopRecording(): String? {
        recorder?.stop()
        recorder?.release()
        return audioFileName
    }
}