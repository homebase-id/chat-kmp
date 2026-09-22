package id.homebase.api.file

import okio.Path.Companion.toPath

/**
 * Reads a file back out of the in-memory web [systemFileSystem], or null if it isn't there.
 *
 * Exists so callers outside this module can reach the wasm temp filesystem without taking an
 * okio dependency of their own — okio is `implementation`-scoped here.
 */
fun readWebFileBytes(path: String): ByteArray? =
    runCatching { systemFileSystem.read(path.toPath()) { readByteArray() } }.getOrNull()
