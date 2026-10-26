package id.homebase.agent

import kotlin.math.max

const val SEGMENT_RATE = 16_000
const val SEGMENT_FRAME_MS = 20
const val SEGMENT_START_FRAMES = 3
const val SEGMENT_END_SILENCE_MS = 700
const val SEGMENT_MIN_MS = 400
const val SEGMENT_MAX_MS = 20_000
private const val CALIBRATION_FRAMES = 5
private const val CALIBRATION_CEILING = 0.03
private const val FLOOR_MAX = 0.05
private const val FLOOR_MIN = 0.001
private const val FLOOR_RATE = 0.05
private const val FLOOR_TRACK_RATIO = 2.0
private const val THRESHOLD_FACTOR = 3.0
private const val THRESHOLD_MIN = 0.01

class Utterance(val pcm16k: ShortArray, val startMs: Long, val endMs: Long) {
    val durationMs get() = endMs - startMs
}

sealed interface SegmentEvent {
    class Onset(val atMs: Long) : SegmentEvent
    class Done(val utterance: Utterance) : SegmentEvent
}

class Segmenter(private val inputRate: Int = CALL_SAMPLE_RATE, private val channels: Int = 1) {
    init {
        require(inputRate % SEGMENT_RATE == 0 && channels >= 1) { "input rate $inputRate must be a multiple of $SEGMENT_RATE" }
    }

    private val ratio = inputRate / SEGMENT_RATE
    private val frameSamples = SEGMENT_RATE / 1000 * SEGMENT_FRAME_MS
    private val endFrames = SEGMENT_END_SILENCE_MS / SEGMENT_FRAME_MS
    private val maxFrames = SEGMENT_MAX_MS / SEGMENT_FRAME_MS
    private val minFrames = SEGMENT_MIN_MS / SEGMENT_FRAME_MS

    private var carry = ShortArray(0)
    private var frameIndex = 0L
    private var floor = 0.0
    private val calibration = ArrayList<Double>()

    private var speaking = false
    private var pendingVoiced = 0
    private var silentRun = 0
    private var startFrame = 0L
    private val pending = ArrayList<ShortArray>()
    private val body = ArrayList<ShortArray>()
    private var bodyFramesToLastVoiced = 0

    fun feed(chunk: ShortArray): List<SegmentEvent> {
        val mono = downsample(chunk)
        val all = if (carry.isEmpty()) mono else carry + mono
        val events = ArrayList<SegmentEvent>()
        var offset = 0
        while (all.size - offset >= frameSamples) {
            frame(all.copyOfRange(offset, offset + frameSamples), events)
            offset += frameSamples
        }
        carry = all.copyOfRange(offset, all.size)
        return events
    }

    private fun downsample(chunk: ShortArray): ShortArray {
        val frames = chunk.size / channels
        val outLen = frames / ratio
        val out = ShortArray(outLen)
        for (i in 0 until outLen) {
            var sum = 0L
            for (j in 0 until ratio) for (c in 0 until channels) sum += chunk[(i * ratio + j) * channels + c]
            out[i] = (sum / (ratio * channels)).toShort()
        }
        return out
    }

    private fun frame(samples: ShortArray, events: MutableList<SegmentEvent>) {
        val level = rms(samples)
        val index = frameIndex++
        if (calibration.size < CALIBRATION_FRAMES) {
            calibration += level
            floor = max(FLOOR_MIN, minOf(calibration.average(), CALIBRATION_CEILING))
            if (calibration.size < CALIBRATION_FRAMES) return
        }
        val voiced = level > max(floor * THRESHOLD_FACTOR, THRESHOLD_MIN)
        if (!voiced && level < floor * FLOOR_TRACK_RATIO) floor = (floor + (level - floor) * FLOOR_RATE).coerceIn(FLOOR_MIN, FLOOR_MAX)
        if (!speaking) {
            idle(samples, voiced, index, events)
            return
        }
        body += samples
        if (voiced) {
            silentRun = 0
            bodyFramesToLastVoiced = body.size
        } else {
            silentRun++
            if (silentRun >= endFrames) {
                finish(events)
                return
            }
        }
        if (body.size >= maxFrames) {
            emit(body.size, events)
            restart(index + 1)
        }
    }

    private fun idle(samples: ShortArray, voiced: Boolean, index: Long, events: MutableList<SegmentEvent>) {
        if (!voiced) {
            pendingVoiced = 0
            pending.clear()
            return
        }
        pending += samples
        pendingVoiced++
        if (pendingVoiced < SEGMENT_START_FRAMES) return
        speaking = true
        startFrame = index + 1 - pending.size
        body.addAll(pending)
        pending.clear()
        pendingVoiced = 0
        silentRun = 0
        bodyFramesToLastVoiced = body.size
        events += SegmentEvent.Onset(startFrame * SEGMENT_FRAME_MS)
    }

    private fun restart(nextFrame: Long) {
        body.clear()
        bodyFramesToLastVoiced = 0
        silentRun = 0
        startFrame = nextFrame
    }

    private fun finish(events: MutableList<SegmentEvent>) {
        emit(bodyFramesToLastVoiced, events)
        body.clear()
        speaking = false
        silentRun = 0
        bodyFramesToLastVoiced = 0
    }

    private fun emit(frames: Int, events: MutableList<SegmentEvent>) {
        if (frames < minFrames) return
        val pcm = ShortArray(frames * frameSamples)
        for (i in 0 until frames) body[i].copyInto(pcm, i * frameSamples)
        events += SegmentEvent.Done(Utterance(pcm, startFrame * SEGMENT_FRAME_MS, (startFrame + frames) * SEGMENT_FRAME_MS))
    }
}

fun wavBytes(pcm: ShortArray, rate: Int = SEGMENT_RATE): ByteArray {
    val data = pcm.size * 2
    val out = java.nio.ByteBuffer.allocate(44 + data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    out.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
    out.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
    out.put("data".toByteArray()).putInt(data)
    pcm.forEach { out.putShort(it) }
    return out.array()
}
