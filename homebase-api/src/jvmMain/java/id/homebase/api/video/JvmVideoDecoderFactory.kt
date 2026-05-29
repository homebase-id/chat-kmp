package id.homebase.api.video

internal actual fun platformVideoDecoder(): VideoDecoder = FFmpegSubprocessVideoDecoder()

internal actual fun platformFfmpegDecoder(): VideoDecoder? = FFmpegSubprocessVideoDecoder()
