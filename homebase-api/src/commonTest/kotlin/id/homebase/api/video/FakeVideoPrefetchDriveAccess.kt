package id.homebase.api.video

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.BytesResponse
import kotlinx.coroutines.delay
import kotlin.uuid.Uuid

/** Records calls routed through [VideoPrefetchDriveAccess]. Tests assert on [calls] to verify the
 *  pre-cache contract.
 *
 *  [getPayloadResponses] maps a payload [key] to the JSON bytes the fake should hand back from
 *  [getPayloadBytesDecrypted] — simulating a cached metadata payload fetch without any real
 *  encryption. */
class FakeVideoPrefetchDriveAccess(
    private val getPayloadResponses: Map<String, String> = emptyMap(),
    private val prefetchDelayMs: Long = 0L,
    private val prefetchError: Throwable? = null,
) : VideoPrefetchDriveAccess {

    sealed interface Call {
        data class PrefetchPayload(val driveId: Uuid, val fileId: Uuid, val key: String) : Call
        data class PrefetchPayloadChunk(
            val driveId: Uuid,
            val fileId: Uuid,
            val key: String,
            val chunkStart: Long,
            val chunkLength: Long,
        ) : Call

        data class GetPayloadBytesDecrypted(
            val driveId: Uuid,
            val fileId: Uuid,
            val key: String,
            val chunkStart: Long?,
            val chunkLength: Long?,
        ) : Call
    }

    private val _calls = mutableListOf<Call>()
    val calls: List<Call> get() = _calls.toList()

    override suspend fun prefetchPayload(
        driveId: Uuid,
        fileId: Uuid,
        key: String,
        onDownloadProgress: ((Float) -> Unit)?,
    ) {
        if (prefetchDelayMs > 0) delay(prefetchDelayMs)
        _calls.add(Call.PrefetchPayload(driveId, fileId, key))
        prefetchError?.let { onDownloadProgress?.invoke(0.8f); throw it }
    }

    override suspend fun prefetchPayloadChunk(
        driveId: Uuid,
        fileId: Uuid,
        key: String,
        chunkStart: Long,
        chunkLength: Long,
        onDownloadProgress: ((Float) -> Unit)?,
    ) {
        if (prefetchDelayMs > 0) delay(prefetchDelayMs)
        _calls.add(Call.PrefetchPayloadChunk(driveId, fileId, key, chunkStart, chunkLength))
    }

    override suspend fun getPayloadBytesDecrypted(
        driveId: Uuid,
        fileId: Uuid,
        key: String,
        keyHeader: KeyHeader,
        chunkStart: Long?,
        chunkLength: Long?,
        onDownloadProgress: ((Float) -> Unit)?,
    ): BytesResponse? {
        _calls.add(Call.GetPayloadBytesDecrypted(driveId, fileId, key, chunkStart, chunkLength))
        val json = getPayloadResponses[key] ?: return null
        return BytesResponse(bytes = json.encodeToByteArray(), contentType = "application/json")
    }
}
