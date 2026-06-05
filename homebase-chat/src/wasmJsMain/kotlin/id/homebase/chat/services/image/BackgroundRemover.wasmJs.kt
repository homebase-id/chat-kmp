package id.homebase.chat.services.image

/**
 * Web (wasmJs): background removal is deferred for v1 (the web target is partial /
 * disabled). Returns null so common code compiles and the editor hides the tool.
 */
actual suspend fun removeBackground(srcBytes: ByteArray): ByteArray? = null

actual fun isBackgroundRemovalSupported(): Boolean = false
