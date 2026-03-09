package id.homebase.core.audio

import java.io.File
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

class JvmAudioRecorder : AudioRecorder {
    private var line: TargetDataLine? = null
    private var audioFileName: String? = null

    override fun startRecording(fileName: String) {
        audioFileName = fileName
        val format = AudioFormat(44100.0f, 16, 2, true, false)
        val info = DataLine.Info(TargetDataLine::class.java, format)
        line = AudioSystem.getLine(info) as TargetDataLine
        line?.open(format)
        line?.start()

        // You must run the file-writing logic in a background thread
        Thread {
            AudioSystem.write(AudioInputStream(line), AudioFileFormat.Type.WAVE, File(fileName))
        }.start()
    }

    override fun stopRecording(): String? {
        line?.stop()
        line?.close()
        return audioFileName
    }
}