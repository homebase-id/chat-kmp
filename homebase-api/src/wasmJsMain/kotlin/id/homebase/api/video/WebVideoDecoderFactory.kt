package id.homebase.api.video

internal actual fun platformVideoDecoder(): VideoDecoder =
    TieredVideoDecoder(
        primary = BrowserVideoDecoder(),
        fallback = FFmpegWasmVideoDecoder(),
    )

internal actual fun platformFfmpegDecoder(): VideoDecoder? = FFmpegWasmVideoDecoder()
