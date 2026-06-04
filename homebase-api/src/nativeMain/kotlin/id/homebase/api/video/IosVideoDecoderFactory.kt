package id.homebase.api.video

internal actual fun platformVideoDecoder(): VideoDecoder =
    TieredVideoDecoder(
        primary = AvFoundationVideoDecoder(),
        fallback = FFmpegKitVideoDecoder(),
    )

internal actual fun platformFfmpegDecoder(): VideoDecoder? = FFmpegKitVideoDecoder()
