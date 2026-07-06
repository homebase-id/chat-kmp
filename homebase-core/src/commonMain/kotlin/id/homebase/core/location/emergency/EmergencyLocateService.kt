package id.homebase.core.location.emergency

import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.QueryBatchRequest
import id.homebase.api.client.drives.QueryBatchResultOptionsRequest
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.query.FileQueryParams
import id.homebase.api.client.peer.temporal.TemporalDriveReadProvider
import id.homebase.api.common.OdinId
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.common.time.UnixTimeUtcRange
import id.homebase.core.config.locationLabeledDrive
import id.homebase.core.ui.screens.location.model.HOUR_MS
import id.homebase.core.ui.screens.location.model.LOCATION_POINTS_PAYLOAD_KEY
import id.homebase.core.ui.screens.location.model.LOCATION_TRACK_FILE_TYPE
import id.homebase.core.ui.screens.location.model.LocationTrackCodec
import id.homebase.core.ui.screens.location.model.LocationTrackHour
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Pulls a peer's location hour-files over the temporal (emergency) read API into the
 * memory-only [EmergencyLocateStore]. Flow per fetch: verify access → query-batch the
 * window (fileType 5610, header content inline) → decode header traces → best-effort
 * full-resolution payload pass for thinned hours → store.
 *
 * The peer's server auto-notifies its owner on these reads (odin-core
 * TemporalDriveAccessedNotification, 1h-throttled) — this service does not add its own
 * signalling; the justified request notice is a chat status message sent by the caller
 * (ConversationService.sendEmergencyLocateRequest) BEFORE the fetch.
 */
class EmergencyLocateService(
    private val temporalDriveReadProvider: TemporalDriveReadProvider,
    private val store: EmergencyLocateStore,
) {
    private val logger = Logger.withTag(TAG)

    sealed interface FetchResult {
        /** Data retrieved and stored (possibly zero hours — the peer had no data in the window). */
        data class Success(val hourCount: Int, val pointCount: Int) : FetchResult
        /** verify said no access (revoked between the list refresh and the tap). */
        data object NoAccess : FetchResult
        data class Failed(val error: Exception) : FetchResult
    }

    /**
     * Fetch [windowMs] of [peer]'s history ending now. Never throws — returns [FetchResult].
     * The server additionally clamps to ITS resolved window, so the result may cover less
     * than requested; whatever came back is stored.
     */
    suspend fun fetch(peer: OdinId, displayName: String, windowMs: Long): FetchResult {
        val driveId = locationLabeledDrive.drive.alias
        val nowMs = UnixTimeUtc.now().milliseconds
        return try {
            // Gate on hasAccess alone — windowSeconds is not a reliable discriminator (#875).
            val access = temporalDriveReadProvider.verifyTemporalAccess(peer, driveId)
            if (!access.hasAccess) {
                logger.w { "fetch: no temporal access to ${peer.domainName}" }
                return FetchResult.NoAccess
            }

            val hours = queryHours(peer, driveId, startMs = nowMs - windowMs, endMs = nowMs)
            val upgraded = upgradeThinnedHours(peer, driveId, hours)

            store.put(
                EmergencyLocateResult(
                    peer = peer,
                    displayName = displayName,
                    windowMs = windowMs,
                    fetchedAtMs = nowMs,
                    hours = upgraded.values.toList(),
                )
            )
            val points = upgraded.values.sumOf { it.points.size }
            logger.i { "fetch: OK peer=${peer.domainName} hours=${upgraded.size} points=$points windowMs=$windowMs" }
            FetchResult.Success(hourCount = upgraded.size, pointCount = points)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(throwable = e) { "fetch: FAILED peer=${peer.domainName}" }
            FetchResult.Failed(e)
        }
    }

    /**
     * Page through the peer's hour files for `[startMs − 1h, endMs]` (1h left-widen for
     * boundary-straddling hour files, mirroring LocationDeviceDirectory.loadDayTraces) and
     * decode each inline header trace. Keyed by fileId so the payload pass can re-fetch.
     */
    private suspend fun queryHours(
        peer: OdinId,
        driveId: kotlin.uuid.Uuid,
        startMs: Long,
        endMs: Long,
    ): Map<kotlin.uuid.Uuid, LocationTrackHour> {
        val hours = LinkedHashMap<kotlin.uuid.Uuid, LocationTrackHour>()
        var cursorState: String? = null
        var fetched = 0
        while (true) {
            val batch = temporalDriveReadProvider.temporalQueryBatch(
                peer, driveId,
                QueryBatchRequest(
                    queryParams = FileQueryParams(
                        fileType = listOf(LOCATION_TRACK_FILE_TYPE),
                        userDate = UnixTimeUtcRange(
                            start = UnixTimeUtc(startMs - HOUR_MS),
                            end = UnixTimeUtc(endMs),
                        ),
                    ),
                    resultOptionsRequest = QueryBatchResultOptionsRequest(
                        cursorState = cursorState,
                        maxRecords = PAGE_SIZE,
                        includeMetadataHeader = true,
                        ordering = QueryBatchSortOrder.NewestFirst,
                    ),
                ),
            )
            for (file in batch.searchResults) {
                val hour = file.fileMetadata.appData.content
                    ?.let { LocationTrackCodec.decodeHeader(it) } ?: continue
                hours[file.fileId] = hour
            }
            fetched += batch.searchResults.size
            if (!batch.hasMoreRows || batch.cursorState == null || fetched >= MAX_FILES) {
                if (fetched >= MAX_FILES) {
                    logger.w { "queryHours: hit MAX_FILES=$MAX_FILES cap for ${peer.domainName} — truncating" }
                }
                return hours
            }
            cursorState = batch.cursorState
        }
    }

    /**
     * Best-effort fidelity pass: hours whose header trace was thinned carry a full-resolution
     * `loc_points` payload — fetch and swap it in (bounded concurrency). A failed payload
     * fetch keeps the header-resolution trace; never fails the overall retrieval.
     */
    private suspend fun upgradeThinnedHours(
        peer: OdinId,
        driveId: kotlin.uuid.Uuid,
        hours: Map<kotlin.uuid.Uuid, LocationTrackHour>,
    ): Map<kotlin.uuid.Uuid, LocationTrackHour> {
        val semaphore = Semaphore(PAYLOAD_CONCURRENCY)
        val fullHours: List<Pair<kotlin.uuid.Uuid, LocationTrackHour>> = coroutineScope {
            hours.filterValues { it.hasOverflowPayload }.map { (fileId, _) ->
                async {
                    semaphore.withPermit {
                        runCatching {
                            val payload = temporalDriveReadProvider.temporalGetPayload(
                                peer, driveId, fileId, LOCATION_POINTS_PAYLOAD_KEY,
                            ) ?: return@withPermit null
                            LocationTrackCodec.decodePayload(payload.bytes.decodeToString())
                                ?.let { full -> fileId to full }
                        }.onFailure { e ->
                            logger.w(throwable = e) { "payload upgrade failed file=$fileId — keeping header trace" }
                        }.getOrNull()
                    }
                }
            }.awaitAll().filterNotNull()
        }
        return hours + fullHours
    }

    private companion object {
        const val TAG = "EmergencyLocate"
        const val PAGE_SIZE = 200

        /** Hard cap: 4 days × 24 hour-files × up to ~15 devices, rounded up. */
        const val MAX_FILES = 1500
        const val PAYLOAD_CONCURRENCY = 4
    }
}
