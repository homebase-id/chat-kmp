package id.homebase.upload

import id.homebase.api.client.drives.files.PayloadHashAlgorithm

/**
 * Payload hashes (odin-core `docs/payload-hashes.md`) for the payloads an encrypted upload encrypts as they are, taken
 * in the same read. A payload whose key is in [expected] must hash to that digest, or [UploadService.prepare] throws
 * [ContentChangedException]: for a caller that hashed its source earlier and must send those bytes and no others.
 * The digest is of the bytes sent, so a payload whose metadata is stripped won't match its source's.
 */
class ContentHashes(
    val algorithm: PayloadHashAlgorithm,
    val expected: Map<String, ByteArray> = emptyMap(),
    /** Sees each hashed payload's plaintext as it's read, for a caller that needs something else from it. */
    val onPlaintext: (payloadKey: String, chunk: ByteArray, offset: Int, length: Int) -> Unit = { _, _, _, _ -> },
)

class ContentChangedException(val payloadKey: String) : Exception("payload $payloadKey no longer matches its hash")
