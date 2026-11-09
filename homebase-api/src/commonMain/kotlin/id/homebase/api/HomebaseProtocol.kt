package id.homebase.api

object HomebaseProtocol {

    const val MaxPayloadDescriptorBytes = 1024

    const val PayloadDescriptorKeyPrefix = "pld_desc"
    const val MaxHeaderContentBytes = 7000

    /** [plain] as `appData.content` once encrypted: base64 of AES-CBC with PKCS7 padding, which is what [MaxHeaderContentBytes] caps. */
    fun encryptedHeaderContentBytes(plain: String): Int {
        val cipher = (plain.encodeToByteArray().size / 16 + 1) * 16
        return (cipher + 2) / 3 * 4
    }

    /** The one rule for whether header content fits; every inline-or-payload decision asks this. */
    fun fitsHeaderContent(plain: String): Boolean = encryptedHeaderContentBytes(plain) <= MaxHeaderContentBytes

    // Mirrors odin-core FileMetadata.MaxPayloadsCount — the server rejects a file with more.
    const val MaxPayloadsPerFile = 25

    // odin-core can't take a payload over 4 GiB yet; AES-CBC adds up to one 16-byte block.
    const val MaxPlaintextPayloadBytes = 4_294_967_295L - 16

    // Server's hard rejection threshold on EmbeddedThumb.content after
    // base64 decode — exceed it and the upload comes back 400
    // "Thumbnail size of N exceeds 1024" with errorCode collapsed into
    // UnhandledScenario. The number is raw bytes (the server reports N
    // = decoded length, not base64 length). ThumbnailGenerator's
    // tinyThumbSize budget (768 raw bytes) is sized to stay comfortably
    // under this with a margin.
    const val MaxEmbeddedThumbBytes = 1024
}
