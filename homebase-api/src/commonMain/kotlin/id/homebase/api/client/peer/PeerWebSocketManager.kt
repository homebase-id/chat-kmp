package id.homebase.api.client.peer

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.TargetDrive
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.OdinId
import id.homebase.api.sync.database.DatabaseManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns one [PeerWebSocketClient] per community-owner host. A member can belong to communities owned
 * by different identities, so live updates require a separate websocket per owner host (the existing
 * single-host [id.homebase.api.client.websockets.OdinWebSocketClient] is bound to `creds.domain`).
 *
 * Lifecycle is driven by the drive mount path (mount on subscribe, unmount on revoke) and must be
 * [reset] on logout so a second user never inherits the first user's peer connections.
 */
class PeerWebSocketManager(
    private val credentialsManager: CredentialsManager,
    private val peerNotificationProvider: PeerNotificationProvider,
    private val eventBus: EventBus,
    private val databaseManager: DatabaseManager,
    // Own SupervisorJob scope (like DriveRegistry) so a peer-connection crash is isolated and the
    // manager doesn't have to thread the AuthConnectionCoordinator scope through DI.
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val mutex = Mutex()

    // One connection per owner host, plus the set of community drives subscribed on it. Adding a
    // drive for an owner that already has a connection rebuilds the client with the union so a
    // single establishConnectionRequest covers all that owner's communities.
    private data class OwnerConnection(
        val client: PeerWebSocketClient,
        val drives: Set<TargetDrive>,
    )

    private val connections = mutableMapOf<OdinId, OwnerConnection>()

    /** Subscribe to [drive] on [ownerOdinId]'s host, starting (or extending) that owner's websocket. */
    suspend fun mount(ownerOdinId: OdinId, drive: TargetDrive) = mutex.withLock {
        val existing = connections[ownerOdinId]
        val drives = (existing?.drives ?: emptySet()) + drive
        if (existing != null && drive in existing.drives) {
            Logger.d(tag = TAG) { "mount: $ownerOdinId already subscribed to ${drive.alias}" }
            return@withLock
        }
        existing?.client?.close()
        val client = newClient(ownerOdinId, drives.toList())
        connections[ownerOdinId] = OwnerConnection(client, drives)
        Logger.i(tag = TAG) { "mount: $ownerOdinId now watching ${drives.size} drive(s)" }
        client.start()
    }

    /** Stop watching [drive] on [ownerOdinId]; closes the owner's websocket when its last drive goes. */
    suspend fun unmount(ownerOdinId: OdinId, drive: TargetDrive) = mutex.withLock {
        val existing = connections[ownerOdinId] ?: return@withLock
        existing.client.close()
        val remaining = existing.drives - drive
        if (remaining.isEmpty()) {
            connections.remove(ownerOdinId)
            Logger.i(tag = TAG) { "unmount: $ownerOdinId — no drives left, connection closed" }
        } else {
            val client = newClient(ownerOdinId, remaining.toList())
            connections[ownerOdinId] = OwnerConnection(client, remaining)
            client.start()
            Logger.i(tag = TAG) { "unmount: $ownerOdinId — ${remaining.size} drive(s) remain" }
        }
    }

    /** Close every peer connection. Call on logout (user-scoped singleton hygiene). */
    suspend fun reset() = mutex.withLock {
        connections.values.forEach { it.client.close() }
        connections.clear()
        Logger.i(tag = TAG) { "reset: all peer connections closed" }
    }

    private fun newClient(ownerOdinId: OdinId, drives: List<TargetDrive>) =
        PeerWebSocketClient(
            ownerOdinId = ownerOdinId,
            drives = drives,
            credentialsManager = credentialsManager,
            peerNotificationProvider = peerNotificationProvider,
            scope = scope,
            eventBus = eventBus,
            databaseManager = databaseManager,
        )

    companion object {
        private const val TAG = "PeerWebSocket"
    }
}
