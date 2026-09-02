package id.homebase.core.contactbook

import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import kotlin.uuid.Uuid

/**
 * Persisted "when did we last preflight this peer" log, keyed by lowercased odinId.
 *
 * Records the *attempt*, not the answer: a denied grant currently arrives as a 400 and is
 * indistinguishable from a real "no", so there is nothing trustworthy to cache yet (see
 * [EmergencyContactReconciler]). An attempt timestamp is safe to keep either way and is what
 * bounds the sweep.
 *
 * Lives in keyValue, so a logout wipes it — a new identity re-probes from scratch.
 */
class EmergencyReconcileAttemptLog(private val databaseManager: DatabaseManager) {

    private val keyValue get() = databaseManager.keyValue

    suspend fun load(): Map<String, Long> = runCatching {
        keyValue.selectByKey(KEY) { _, data -> data }
            ?.takeIf { it.isNotEmpty() }
            ?.let { OdinSystemSerializer.deserialize<Map<String, Long>>(it.decodeToString()) }
    }.getOrNull().orEmpty()

    suspend fun save(attempts: Map<String, Long>) {
        runCatching {
            keyValue.upsertValue(KEY, OdinSystemSerializer.serialize(attempts).encodeToByteArray())
        }
    }

    companion object {
        // 0a08xx — next free namespace after Location (0a03xx) and the 0a04–0a07 add-ons.
        val KEY: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000a0801")
    }
}
