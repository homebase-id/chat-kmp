package id.homebase.chat.services.convo.contact

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.connections.ConnectionRequestOrigin
import id.homebase.api.client.connections.ConnectionStatus
import id.homebase.api.client.connections.RedactedIdentityConnectionRegistration
import id.homebase.api.common.OdinId
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.chat.data.IncomingConnectionRequestUiModel
import id.homebase.chat.data.OutgoingConnectionRequestUiModel
import kotlin.time.Clock

/**
 * Thin persistence layer around the ConnectionCache table. Scoped to the currently-authenticated
 * identity via CredentialsManager. Converts the stored (odinId, status) rows into the richer
 * in-memory shapes the services expose (RedactedIdentityConnectionRegistration for the
 * connection map, and UiModels for pending request lists) so downstream consumers don't need
 * to distinguish cached rows from live-fetched rows.
 */
class ConnectionCacheRepository(
    private val dbm: DatabaseManager,
    private val credentialsManager: CredentialsManager,
) {
    companion object {
        const val STATUS_CONNECTED = "connected"
        const val STATUS_BLOCKED = "blocked"
        const val STATUS_INCOMING_PENDING = "incomingPending"
        const val STATUS_OUTGOING_PENDING = "outgoingPending"
    }

    data class HydratedConnections(
        val connectionMap: Map<OdinId, RedactedIdentityConnectionRegistration>,
    )

    suspend fun hydrateConnections(): HydratedConnections? = runReading { identityId ->
        val rows = dbm.connectionCache.selectByIdentity(identityId)
            .filter { it.status == STATUS_CONNECTED || it.status == STATUS_BLOCKED }
        if (rows.isEmpty()) return@runReading null
        val map = rows.associate { row ->
            val odinId = OdinId(row.odinId)
            odinId to synthesizeRegistration(
                odinId = odinId,
                status = if (row.status == STATUS_CONNECTED) ConnectionStatus.Connected
                        else ConnectionStatus.Blocked,
                lastRefreshMs = row.lastRefresh,
            )
        }
        HydratedConnections(connectionMap = map)
    }

    suspend fun hydrateIncomingRequests(): List<IncomingConnectionRequestUiModel>? =
        runReading { identityId ->
            val rows = dbm.connectionCache.selectByIdentityAndStatus(
                identityId,
                STATUS_INCOMING_PENDING,
            )
            if (rows.isEmpty()) null
            else rows.map { row ->
                val sender = OdinId(row.odinId)
                IncomingConnectionRequestUiModel(
                    senderName = "TODO $sender",
                    senderOdinId = sender,
                    receivedTimestampMilliseconds = UnixTimeUtc(row.lastRefresh),
                )
            }
        }

    suspend fun hydrateOutgoingRequests(): List<OutgoingConnectionRequestUiModel>? =
        runReading { identityId ->
            val rows = dbm.connectionCache.selectByIdentityAndStatus(
                identityId,
                STATUS_OUTGOING_PENDING,
            )
            if (rows.isEmpty()) null
            else rows.map { row ->
                val recipient = OdinId(row.odinId)
                OutgoingConnectionRequestUiModel(
                    recipientName = "TODO ${recipient.domainName}",
                    recipientOdinId = recipient,
                    message = null,
                    introducerOdinId = null,
                    receivedTimestampMilliseconds = UnixTimeUtc(row.lastRefresh),
                )
            }
        }

    suspend fun persistConnections(
        connected: Collection<OdinId>,
        blocked: Collection<OdinId>,
    ) {
        val identityId = credentialsManager.getActiveCredentials()?.getIdentityId() ?: return
        val now = nowMs()
        dbm.connectionCache.replaceStatus(
            identityId,
            STATUS_CONNECTED,
            connected.map { it.domainName },
            now,
        )
        dbm.connectionCache.replaceStatus(
            identityId,
            STATUS_BLOCKED,
            blocked.map { it.domainName },
            now,
        )
    }

    suspend fun persistIncomingRequests(senders: Collection<OdinId>) {
        val identityId = credentialsManager.getActiveCredentials()?.getIdentityId() ?: return
        dbm.connectionCache.replaceStatus(
            identityId,
            STATUS_INCOMING_PENDING,
            senders.map { it.domainName },
            nowMs(),
        )
    }

    suspend fun persistOutgoingRequests(recipients: Collection<OdinId>) {
        val identityId = credentialsManager.getActiveCredentials()?.getIdentityId() ?: return
        dbm.connectionCache.replaceStatus(
            identityId,
            STATUS_OUTGOING_PENDING,
            recipients.map { it.domainName },
            nowMs(),
        )
    }

    suspend fun upsert(odinId: OdinId, status: String) {
        val identityId = credentialsManager.getActiveCredentials()?.getIdentityId() ?: return
        dbm.connectionCache.upsert(identityId, odinId.domainName, status, nowMs())
    }

    suspend fun remove(odinId: OdinId) {
        val identityId = credentialsManager.getActiveCredentials()?.getIdentityId() ?: return
        dbm.connectionCache.deleteByIdentityAndOdinId(identityId, odinId.domainName)
    }

    // The block runs the suspend `dbm.connectionCache.select*` reads, which
    // route through `databaseManager.readValue(...)` for SlowDbRead
    // instrumentation and read-lane queue-wait tracking.
    private suspend inline fun <T> runReading(
        crossinline block: suspend (kotlin.uuid.Uuid) -> T?,
    ): T? {
        val identityId = try {
            credentialsManager.getActiveCredentials()?.getIdentityId()
        } catch (e: Exception) {
            Logger.w(e) { "ConnectionCacheRepository: unable to resolve active identity" }
            null
        } ?: return null
        return block(identityId)
    }

    private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

    private fun synthesizeRegistration(
        odinId: OdinId,
        status: ConnectionStatus,
        lastRefreshMs: Long,
    ): RedactedIdentityConnectionRegistration {
        // Only `odinId` and `status` are consumed by ConversationEnricher; the remaining
        // fields are filled with safe defaults so cached rows can share the same type as
        // network-fetched rows.
        return RedactedIdentityConnectionRegistration(
            odinId = odinId,
            status = status,
            accessGrant = null,
            created = lastRefreshMs,
            lastUpdated = lastRefreshMs,
            originalContactData = null,
            introducerOdinId = null,
            connectionRequestOrigin = ConnectionRequestOrigin.None,
            hasVerificationHash = false,
            rku = false,
        )
    }
}
