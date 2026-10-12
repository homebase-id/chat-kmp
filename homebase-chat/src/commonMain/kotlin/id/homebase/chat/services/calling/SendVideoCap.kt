package id.homebase.chat.services.calling

/**
 * The largest picture a phone sends. The negotiated H.264 is profile-level-id 42e01f (level 3.1, at most 3600 macroblocks
 * a frame); VideoToolbox rejects a bigger frame with -12902 and, with no fallback codec, the stream stops for good.
 */
internal object SendVideoCap {
    const val WIDTH = 1280
    const val HEIGHT = 720
    const val FPS = 30
}

internal fun h264MacroblocksPerFrame(width: Int, height: Int): Int = ((width + 15) / 16) * ((height + 15) / 16)
