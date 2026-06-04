package id.homebase.api.image

// Web: HEIC isn't decodable in the browser, so callers fall back to the original bytes.
//
// Everything else (the ImageUtils object + toImageBitmap) is the shared Skia/skiko
// implementation in the `skiaMain` source set — the browser bundles skiko via Compose,
// so the same synchronous Skia code that Desktop/iOS use runs on the web too.
actual fun convertHeicToJpeg(heicBytes: ByteArray): ByteArray? = null
