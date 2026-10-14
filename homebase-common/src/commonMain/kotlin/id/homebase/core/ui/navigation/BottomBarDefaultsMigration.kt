package id.homebase.core.ui.navigation

import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.KeyValueWrapper
import id.homebase.core.email.EmailPreferences
import id.homebase.core.vault.VaultPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

/**
 * Vault and Email default to hidden. An install that already synced chat (logout wipes the DB, so
 * a chat cursor means a returning session) keeps them visible: the migration writes an explicit
 * `true` for any icon it never toggled, then marks itself done. Resolved by the prefs themselves
 * on every read, so it cannot depend on a bootstrap hook having run.
 */
object BottomBarDefaultsMigration {
    val DONE_KEY: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000a0d01")
    private val pinnedVisible = listOf(VaultPreferences.ICON_VISIBLE_KEY, EmailPreferences.ICON_VISIBLE_KEY)
    private val writeLock = Mutex()

    /** Synchronous: the stored value, else what the migration decides (or will decide) for this DB. */
    fun iconVisible(keyValue: KeyValueWrapper, key: Uuid): Boolean {
        keyValue.selectByKeyBootstrapSync(key) { _, data -> data }?.takeIf { it.isNotEmpty() }?.let { return it[0].toInt() != 0 }
        if (isDone(keyValue)) return false
        return isExistingInstall(keyValue)
    }

    fun isDone(keyValue: KeyValueWrapper): Boolean = keyValue.selectByKeyBootstrapSync(DONE_KEY) != null

    private fun isExistingInstall(keyValue: KeyValueWrapper): Boolean =
        keyValue.selectByKeyBootstrapSync(SystemDriveConstants.chatDrive.alias) != null

    /** Persists the decision [iconVisible] already reports; a no-op once done. */
    suspend fun run(databaseManager: DatabaseManager) = writeLock.withLock {
        val keyValue = databaseManager.keyValue
        if (keyValue.selectByKey(DONE_KEY) != null) return@withLock
        if (keyValue.selectByKey(SystemDriveConstants.chatDrive.alias) != null) {
            for (key in pinnedVisible) {
                if (keyValue.selectByKey(key) == null) keyValue.upsertValue(key, byteArrayOf(1))
            }
        }
        keyValue.upsertValue(DONE_KEY, byteArrayOf(1))
    }

    /** Called by each pref after it resolves its icon; freezes the decision before a first sync can change it. */
    internal fun persistIfPending(databaseManager: DatabaseManager, scope: CoroutineScope?) {
        if (scope == null || runCatching { isDone(databaseManager.keyValue) }.getOrDefault(true)) return
        scope.launch {
            try {
                run(databaseManager)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Logger.w(t) { "Bottom bar defaults migration failed; the next pref read retries" }
            }
        }
    }
}
