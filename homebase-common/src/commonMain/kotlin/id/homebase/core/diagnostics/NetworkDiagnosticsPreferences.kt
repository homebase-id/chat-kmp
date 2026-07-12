package id.homebase.core.diagnostics

import id.homebase.api.sync.database.DatabaseManager
import kotlin.uuid.Uuid

/**
 * Persists the last-known resolved IP for the owner server hostname, in the encrypted key/value
 * store (same [DatabaseManager] backing as [id.homebase.core.contactbook.ContactBookPreferences]).
 * The developer-menu Network Status probe writes it whenever its DNS-resolve stage succeeds, and
 * reads it back only when live DNS fails, to attempt a last-known-IP fallback.
 *
 * A single entry is stored, encoded as `hostname|ip|epochMs`. Reads are guarded on the hostname, so
 * an entry left over from a previous identity/server is simply ignored (and overwritten on the next
 * successful resolve) — there is no in-memory state and nothing to reset on logout.
 *
 * UUID namespace: `0a05xx` (claimed by network diagnostics).
 */
class NetworkDiagnosticsPreferences(private val databaseManager: DatabaseManager) {

    private val keyValue get() = databaseManager.keyValue

    /** The stored IP + when it was resolved (epoch ms), or null if none is stored for [hostname]. */
    suspend fun getLastKnownIp(hostname: String): LastKnownIp? {
        val bytes = runCatching {
            keyValue.selectByKey(LAST_KNOWN_IP_KEY) { _, data -> data }
        }.getOrNull() ?: return null
        val parts = bytes.decodeToString().split('|')
        if (parts.size != 3) return null
        val (host, ip, ts) = parts
        if (host != hostname || ip.isEmpty()) return null
        val at = ts.toLongOrNull() ?: return null
        return LastKnownIp(ip, at)
    }

    suspend fun setLastKnownIp(hostname: String, ip: String, atMs: Long) {
        keyValue.upsertValue(LAST_KNOWN_IP_KEY, "$hostname|$ip|$atMs".encodeToByteArray())
    }

    companion object {
        val LAST_KNOWN_IP_KEY: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000a0501")
    }
}

/** A previously-resolved IP for the owner hostname and when ([resolvedAtMs], epoch ms) it was seen. */
data class LastKnownIp(val ip: String, val resolvedAtMs: Long)
