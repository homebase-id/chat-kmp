package id.homebase.api

object HomebaseProtocol {

    const val MaxPayloadDescriptorBytes = 1024
    const val MaxHeaderContentBytes = 7000

    // Server's hard rejection threshold on EmbeddedThumb.content after
    // base64 decode — exceed it and the upload comes back 400
    // "Thumbnail size of N exceeds 1024" with errorCode collapsed into
    // UnhandledScenario. The number is raw bytes (the server reports N
    // = decoded length, not base64 length). ThumbnailGenerator's
    // tinyThumbSize budget (768 raw bytes) is sized to stay comfortably
    // under this with a margin.
    const val MaxEmbeddedThumbBytes = 1024
}
