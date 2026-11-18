package id.homebase.upload

import id.homebase.api.common.SecureByteArray
import kotlinx.coroutines.CoroutineScope
import kotlin.uuid.Uuid

interface PayloadBundleEncryptor {
    suspend fun encryptBundle(
        uniqueId: Uuid,
        bundle: PayloadBundle?,
        aesKey: SecureByteArray,
        scope: CoroutineScope,
        /** Null for none; see [ContentHashes]. */
        hashes: ContentHashes? = null,
    ): PayloadBundle

    /**
     * [bundle] as an unencrypted upload sends it (a public post): every payload copied into outbox staging, which the
     * row then owns (images stripped of metadata on the way, when their payload asks). Throws
     * [SourceUnavailableException] for a missing source, before anything is staged, and [IllegalArgumentException] for
     * a video that would need processing: that exists only encrypted.
     */
    suspend fun stageBundle(bundle: PayloadBundle?): PayloadBundle

    /**
     * Deletes the files [bundle]'s payloads point at, a video's HLS folder included, when no outbox row took them. Runs
     * to the end even when the caller is cancelled.
     */
    suspend fun discard(bundle: PayloadBundle)
}

val EMPTY_BUNDLE = PayloadBundle(payloads = emptyList(), thumbnails = emptyList(), previewThumbs = emptyList())
