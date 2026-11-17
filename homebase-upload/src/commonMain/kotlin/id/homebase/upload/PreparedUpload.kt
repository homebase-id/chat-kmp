package id.homebase.upload

import id.homebase.api.client.drives.upload.UploadFileRequest

/**
 * A new file ready to send: [request] and the files its payloads point at, which this owns until an outbox row takes
 * them. Made by [UploadService.prepare]; [UploadService.queue] hands it to the outbox, and anything else that has one
 * calls [discard] once it's done with it.
 */
class PreparedUpload internal constructor(
    internal val spec: MediaUploadSpec,
    val request: UploadFileRequest,
    /** The payloads and thumbnails as sent, for seeding the cache and the optimistic row's descriptors. */
    internal val sent: PayloadBundle,
    private val encryptor: PayloadBundleEncryptor,
) {
    /** Deletes the files, a video's HLS folder included. Runs to the end even when the caller is cancelled. */
    suspend fun discard() = encryptor.discard(sent)
}
