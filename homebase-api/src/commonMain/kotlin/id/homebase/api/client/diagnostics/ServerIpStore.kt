package id.homebase.api.client.diagnostics

import id.homebase.api.sync.database.DatabaseManager
import kotlin.uuid.Uuid

/**
 * Persists the last-known-good IP for the owner server hostname, in the encrypted key/value store.
 *
 * Written from the **production** networking path by [ServerIpCapture] whenever the real app
 * completes a validated TLS connection to the owner server (SNI=hostname + cert validation, so the
 * IP provably reached the real server — fail-closed). Read by the developer-menu Network Status
 * probe as the last resort of its resolution ladder when system DNS and DoH both fail.
 *
 * A single entry is stored, encoded as `hostname|ip|epochMs`. Reads are guarded on the hostname, so
 * an entry left over from a previous identity/server is ignored (and overwritten on the next
 * validated connect). No in-memory state; nothing to reset on logout.
 *
 * UUID namespace: `0a05xx` (claimed by network diagnostics).
 */
class ServerIpStore(private val databaseManager: DatabaseManager) {

    private val keyValue get() = databaseManager.keyValue

    /** The stored IP + when it was captured (epoch ms), or null if none is stored for [hostname]. */
    suspend fun getLastKnownIp(hostname: String): LastKnownServerIp? {
        val bytes = runCatching {
            keyValue.selectByKey(LAST_KNOWN_IP_KEY) { _, data -> data }
        }.getOrNull() ?: return null
        val parts = bytes.decodeToString().split('|')
        if (parts.size != 3) return null
        val (host, ip, ts) = parts
        if (host != hostname || ip.isEmpty()) return null
        val at = ts.toLongOrNull() ?: return null
        return LastKnownServerIp(ip, at)
    }

    suspend fun setLastKnownIp(hostname: String, ip: String, atMs: Long) {
        keyValue.upsertValue(LAST_KNOWN_IP_KEY, "$hostname|$ip|$atMs".encodeToByteArray())
    }

    companion object {
        val LAST_KNOWN_IP_KEY: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000a0501")
    }
}

/** A previously-captured IP for the owner hostname and when ([resolvedAtMs], epoch ms) it was seen. */
data class LastKnownServerIp(val ip: String, val resolvedAtMs: Long)
