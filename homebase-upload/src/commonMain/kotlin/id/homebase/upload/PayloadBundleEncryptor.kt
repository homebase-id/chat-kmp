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
     * [SourceUnavailableException] for a missing source other than a video, before anything is staged; a video's source
     * fails when it is read, and what was staged before it is deleted. A video is compressed and
     * segmented as an encrypted one is, and staged in the clear, its progress reported under [uniqueId] on [scope].
     */
    suspend fun stageBundle(uniqueId: Uuid, bundle: PayloadBundle?, scope: CoroutineScope): PayloadBundle

    /**
     * Deletes the files [bundle]'s payloads point at, a video's HLS folder included, when no outbox row took them. Runs
     * to the end even when the caller is cancelled.
     */
    suspend fun discard(bundle: PayloadBundle)
}

val EMPTY_BUNDLE = PayloadBundle(payloads = emptyList(), thumbnails = emptyList(), previewThumbs = emptyList())
