package id.homebase.upload

import id.homebase.api.client.drives.upload.UploadFileRequest

/**
 * A new file ready to send: [request] and the files its payloads point at, which this owns until an outbox row takes
 * them. Made by [UploadService.prepare]; [UploadService.queue] hands it to the outbox once, and anything else that has
 * one calls [discard] once it's done with it. One owner at a time.
 */
class PreparedUpload internal constructor(
    internal val spec: MediaUploadSpec,
    val request: UploadFileRequest,
    /** The payloads and thumbnails as sent, for seeding the cache and the optimistic row's descriptors. */
    internal val sent: PayloadBundle,
    private val encryptor: PayloadBundleEncryptor,
) {
    private var state = State.Ready

    /**
     * Deletes the files, a video's HLS folder included, unless [UploadService.queue] already has them. Runs to the end
     * even when the caller is cancelled.
     */
    suspend fun discard() {
        if (state == State.Ready) release()
    }

    internal fun claim() {
        check(state == State.Ready) { "a PreparedUpload is queued once, and never after discard()" }
        state = State.Claimed
    }

    internal suspend fun release() {
        state = State.Discarded
        encryptor.discard(sent)
    }

    private enum class State { Ready, Claimed, Discarded }
}
