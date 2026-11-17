package id.homebase.upload

import id.homebase.api.common.SecureByteArray
import kotlinx.coroutines.CoroutineScope
import kotlin.uuid.Uuid

interface PayloadBundleEncryptor {
    suspend fun encryptBundle(
        uniqueId: Uuid,
        bundle: PayloadBundle?,
        aesKey: SecureByteArray,
        scope: CoroutineScope
    ): PayloadBundle

    /**
     * [bundle] as an unencrypted upload sends it (a public post): images stripped of metadata when their payload asks,
     * into staged copies the outbox row owns. Throws [SourceUnavailableException] for a missing source, before anything
     * is staged, and [IllegalArgumentException] for a video that would need processing: that exists only encrypted.
     */
    suspend fun stageBundle(uniqueId: Uuid, bundle: PayloadBundle?): PayloadBundle =
        bundle ?: PayloadBundle(payloads = emptyList(), thumbnails = emptyList(), previewThumbs = emptyList())
}
