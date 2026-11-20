package id.homebase.api.video

internal actual fun platformVideoDecoder(): VideoDecoder = AvFoundationVideoDecoder()

// A video AVFoundation can't open is sent as a file (VideoProber.canRead), so it never needs a poster.
internal actual fun platformFfmpegDecoder(): VideoDecoder? = null
