package id.homebase.chat.services.image

/**
 * Desktop (JVM): background removal is deferred for v1 (no OS segmenter; bundling
 * an ONNX model would bloat the distributable). Returns null so common code
 * compiles and the editor hides the tool on Desktop.
 */
actual suspend fun removeBackground(srcBytes: ByteArray): ByteArray? = null

actual fun isBackgroundRemovalSupported(): Boolean = false

/** No-op: Desktop has no segmenter, so there is no model to pre-download. */
actual fun warmUpBackgroundRemoval() {
    // Nothing to warm up on Desktop.
}
