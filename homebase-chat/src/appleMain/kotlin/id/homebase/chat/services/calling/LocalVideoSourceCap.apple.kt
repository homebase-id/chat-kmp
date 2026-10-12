package id.homebase.chat.services.calling

import com.shepeliev.webrtckmp.VideoProcessorFactory
import com.shepeliev.webrtckmp.WebRtc
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
private val configured = AtomicBoolean(false)

// webrtc-kmp 0.125.11 reads each camera format's size through a freed struct (copyContents), so it opens the iPhone
// camera at 4K and the adapter lets the encoder climb past 720p. The source itself stays the capturer's delegate, so
// no Kotlin runs per frame.
@OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)
internal actual fun capLocalVideoSource() {
    if (!configured.compareAndSet(false, true)) return
    WebRtc.configure(
        VideoProcessorFactory { source ->
            source.adaptOutputFormatToWidth(SendVideoCap.WIDTH, height = SendVideoCap.HEIGHT, fps = SendVideoCap.FPS)
            source
        },
    )
}
