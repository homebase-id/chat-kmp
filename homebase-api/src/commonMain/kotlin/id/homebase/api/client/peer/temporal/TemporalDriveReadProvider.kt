package id.homebase.api.client.peer.temporal

import co.touchlab.kermit.Logger
import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.PayloadSizePolicy
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.QueryBatchRequest
import id.homebase.api.client.drives.QueryBatchResponse
import id.homebase.api.client.drives.ServerFile
import id.homebase.api.client.drives.files.BytesResponse
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.common.OdinId
import id.homebase.api.serialization.OdinSystemSerializer
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import kotlin.uuid.Uuid

/**
 * Client for the **temporal (time-boxed / "emergency") read** API a peer identity hosts for its
 * sensitive drives (odin-core PR #1567). The caller's access derives from
 * `DrivePermission.ConditionalTemporalRead` granted via a circle on the peer; the peer clamps every
 * read to a recent window and notifies its owner. Used by the Location App's family-emergency feature
 * to pull a relative's recent location history.
 *
 * Like the rest of the peer API, every call hits the **user's own host** (`creds.domain`) with
 * `bearerAuth` + shared-secret encryption; the own host brokers to the peer server-side and
 * re-encrypts the response under the caller's shared secret, so the decode path is identical to the
 * non-temporal providers. Mirrors [id.homebase.api.client.peer.PeerDriveQueryProvider]'s style and
 * reuses [DriveQueryProvider.mapQueryBatchResponse] / [DriveFileProvider.decryptBytes] so no decode
 * or decrypt logic is duplicated.
 *
 * Routes (all under `/peer/{odinId}/drives/{driveId}/temporal`):
 * - `POST verify`                                  → [TemporalAccessStatus]
 * - `POST query-batch`                             → [QueryBatchResponse]
 * - `GET  files/{fileId}/header`                   → [HomebaseFile] (null outside the window / missing)
 * - `GET  files/{fileId}/payload/{payloadKey}`     → payload bytes (null outside the window / missing)
 * - `GET  files/{fileId}/payload/{payloadKey}/thumb/{w}/{h}` → thumbnail bytes
 */
class TemporalDriveReadProvider(
    httpClient: HttpClient,
    credentialsManager: CredentialsManager,
    private val driveQueryProvider: DriveQueryProvider,
    private val driveFileProvider: DriveFileProvider,
) : OdinApiProviderBase(httpClient, credentialsManager) {

    /**
     * Preflight: does the caller currently hold temporal read access to [driveId] on [peer], and what
     * window? Reads no data and fires no access notification on the peer. Returns
     * `hasAccess = false` when no grant exists (does not throw).
     */
    suspend fun verifyTemporalAccess(peer: OdinId, driveId: Uuid): TemporalAccessStatus {
        val creds = requireCreds()
        val url = apiUrl(creds.domain, "/peer/$peer/drives/$driveId/temporal/verify")
        Logger.i(tag = TAG) { "verifyTemporalAccess: POST peer=${peer.domainName} drive=$driveId" }

        // No request body on the server; send an encrypted empty JSON object so the shared-secret
        // perimeter is satisfied (the endpoint is OwnerOrApp, not [NoSharedSecret]).
        val response = encryptedPostJson(
            url = url,
            token = creds.accessToken,
            jsonBody = "{}",
            secret = creds.secret,
        )
        try {
            throwForFailure(response)
        } catch (e: Exception) {
            Logger.w(throwable = e, tag = TAG) {
                "verifyTemporalAccess: FAIL peer=${peer.domainName} drive=$driveId status=${response.status}"
            }
            throw e
        }
        val body: TemporalAccessStatus = deserialize(response.body)
        Logger.i(tag = TAG) {
            "verifyTemporalAccess: OK peer=${peer.domainName} drive=$driveId → hasAccess=${body.hasAccess} windowSeconds=${body.windowSeconds} newestFileModified=${body.newestFileModified.milliseconds}"
        }
        return body
    }

    /**
     * Time-clamped query-batch against the peer's drive. The peer derives the [TargetDrive] from the
     * route and clamps results to the resolved window; an out-of-window file simply isn't returned.
     * Reuses [QueryBatchRequest] (its `queryParams` needs no drive field — the route carries it).
     */
    suspend fun temporalQueryBatch(
        peer: OdinId,
        driveId: Uuid,
        request: QueryBatchRequest,
    ): QueryBatchResponse {
        val creds = requireCreds()
        val url = apiUrl(creds.domain, "/peer/$peer/drives/$driveId/temporal/query-batch")
        Logger.i(tag = TAG) { "temporalQueryBatch: POST peer=${peer.domainName} drive=$driveId" }

        val response = encryptedPostJson(
            url = url,
            token = creds.accessToken,
            jsonBody = OdinSystemSerializer.serialize(request),
            secret = creds.secret,
        )
        try {
            throwForFailure(response)
        } catch (e: Exception) {
            Logger.w(throwable = e, tag = TAG) {
                "temporalQueryBatch: FAIL peer=${peer.domainName} drive=$driveId status=${response.status}"
            }
            throw e
        }
        val batch = driveQueryProvider.mapQueryBatchResponse(response.body, creds.secret)
        Logger.i(tag = TAG) {
            "temporalQueryBatch: OK peer=${peer.domainName} drive=$driveId → ${batch.searchResults.size} files (hasMore=${batch.hasMoreRows})"
        }
        return batch
    }

    /**
     * Fetch one file's header through the temporal API. Returns null when the file is outside the
     * window or doesn't exist (the peer answers 404 either way — fail-closed).
     */
    suspend fun temporalGetFileHeader(peer: OdinId, driveId: Uuid, fileId: Uuid): HomebaseFile? {
        val creds = requireCreds()
        val url = apiUrl(creds.domain, "/peer/$peer/drives/$driveId/temporal/files/$fileId/header")
        Logger.i(tag = TAG) { "temporalGetFileHeader: GET peer=${peer.domainName} drive=$driveId file=$fileId" }

        val response = encryptedGet(url = url, token = creds.accessToken, secret = creds.secret)
        if (response.status == 404) {
            Logger.i(tag = TAG) { "temporalGetFileHeader: 404 (outside window / missing) file=$fileId" }
            return null
        }
        try {
            throwForFailure(response)
        } catch (e: Exception) {
            Logger.w(throwable = e, tag = TAG) {
                "temporalGetFileHeader: FAIL peer=${peer.domainName} drive=$driveId file=$fileId status=${response.status}"
            }
            throw e
        }
        return deserialize<ServerFile>(response.body).asHomebaseFile(creds.secret)
    }

    /**
     * Fetch (and decrypt) a payload through the temporal API. The payload endpoint carries no
     * shared-secret on the wire — it returns the still-encrypted bytes plus the
     * `sharedsecretencryptedheader64` header, which [DriveFileProvider.decryptBytes] uses to recover
     * the key header and decrypt. Returns null when the file is outside the window or missing.
     *
     * [chunkStart]/[chunkLength] request a byterange (both or neither).
     */
    suspend fun temporalGetPayload(
        peer: OdinId,
        driveId: Uuid,
        fileId: Uuid,
        payloadKey: String,
        chunkStart: Long? = null,
        chunkLength: Long? = null,
    ): BytesResponse? {
        require(payloadKey.isNotBlank()) { "payloadKey must be defined" }
        val creds = requireCreds()
        val base = "/peer/$peer/drives/$driveId/temporal/files/$fileId/payload/$payloadKey"
        val path = if (chunkStart != null) "$base/$chunkStart/${chunkLength ?: ""}" else base
        val url = apiUrl(creds.domain, path)
        Logger.i(tag = TAG) { "temporalGetPayload: GET peer=${peer.domainName} drive=$driveId file=$fileId key=$payloadKey" }

        // Full reads are size-guarded (#845) — location payloads are tiny, so this is
        // pure defense; range reads stay uncapped (bounded by their chunkLength).
        val response = requestBytes(
            maxBytes = if (chunkStart == null) PayloadSizePolicy.RENDER_LIMIT_BYTES else null
        ) {
            httpClient.get(url) { bearerAuth(creds.accessToken) }
        }
        if (response.status == 404) {
            Logger.i(tag = TAG) { "temporalGetPayload: 404 (outside window / missing) file=$fileId key=$payloadKey" }
            return null
        }
        throwForFailure(response)

        val decrypted = driveFileProvider.decryptBytes(response.headers, response.bytes)
        Logger.i(tag = TAG) {
            "temporalGetPayload: OK file=$fileId key=$payloadKey → ${decrypted.size} bytes (${response.contentType})"
        }
        return BytesResponse(bytes = decrypted, contentType = response.contentType)
    }

    /**
     * Fetch (and decrypt) a payload thumbnail through the temporal API. Same wire shape as
     * [temporalGetPayload]. Returns null when the file/thumbnail is outside the window or missing.
     */
    suspend fun temporalGetThumbnail(
        peer: OdinId,
        driveId: Uuid,
        fileId: Uuid,
        payloadKey: String,
        width: Int,
        height: Int,
    ): BytesResponse? {
        require(payloadKey.isNotBlank()) { "payloadKey must be defined" }
        require(width > 0 && height > 0) { "width/height must be positive" }
        val creds = requireCreds()
        val url = apiUrl(
            creds.domain,
            "/peer/$peer/drives/$driveId/temporal/files/$fileId/payload/$payloadKey/thumb/$width/$height",
        )
        Logger.i(tag = TAG) {
            "temporalGetThumbnail: GET peer=${peer.domainName} drive=$driveId file=$fileId key=$payloadKey ${width}x$height"
        }

        val response = requestBytes {
            httpClient.get(url) { bearerAuth(creds.accessToken) }
        }
        if (response.status == 404) {
            Logger.i(tag = TAG) { "temporalGetThumbnail: 404 (outside window / missing) file=$fileId key=$payloadKey" }
            return null
        }
        throwForFailure(response)

        val decrypted = driveFileProvider.decryptBytes(response.headers, response.bytes)
        Logger.i(tag = TAG) {
            "temporalGetThumbnail: OK file=$fileId key=$payloadKey → ${decrypted.size} bytes (${response.contentType})"
        }
        return BytesResponse(bytes = decrypted, contentType = response.contentType)
    }

    companion object { private const val TAG = "TemporalRead" }
}
