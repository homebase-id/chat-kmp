package id.homebase.core.ui.navigation

import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.KeyValueWrapper
import id.homebase.core.calling.CallsPreferences
import id.homebase.core.contactbook.ContactBookPreferences
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
    val CONTACTS_DONE_KEY: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000a0d02")
    private val RETIRED_CALLS_ICON_KEY: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000a0c01")
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

    /** What a pref seeds its icon from; also freezes the decision before a first sync can change it. */
    internal fun resolveIconVisible(databaseManager: DatabaseManager, key: Uuid, scope: CoroutineScope?): Boolean {
        val visible = runCatching { iconVisible(databaseManager.keyValue, key) }.getOrDefault(false)
        persistIfPending(databaseManager, scope)
        return visible
    }

    /**
     * Calls moved into Contacts: a user whose bar had the Calls icon but hid Contacts would lose
     * both, so Contacts comes back once. The done key is written whatever the outcome, so a later
     * hide is final.
     */
    internal fun resolveContactBookIconVisible(databaseManager: DatabaseManager, scope: CoroutineScope?): Boolean {
        val keyValue = databaseManager.keyValue
        val state = runCatching {
            val stored = readFlag(keyValue, ContactBookPreferences.ICON_VISIBLE_KEY)
            val done = keyValue.selectByKeyBootstrapSync(CONTACTS_DONE_KEY) != null
            val callsWasVisible = readFlag(keyValue, CallsPreferences.ENABLED_KEY) == true &&
                readFlag(keyValue, RETIRED_CALLS_ICON_KEY) != false
            ContactsResolution(visible = stored ?: true, restore = !done && stored == false && callsWasVisible, done = done)
        }.getOrDefault(ContactsResolution(visible = true, restore = false, done = true))
        if (!state.done && scope != null) {
            scope.launch {
                try {
                    if (state.restore) keyValue.upsertValue(ContactBookPreferences.ICON_VISIBLE_KEY, byteArrayOf(1))
                    keyValue.upsertValue(CONTACTS_DONE_KEY, byteArrayOf(1))
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Logger.w(t) { "Contacts bottom bar migration failed; the next pref read retries" }
                }
            }
        }
        return state.visible || state.restore
    }

    private class ContactsResolution(val visible: Boolean, val restore: Boolean, val done: Boolean)

    private fun readFlag(keyValue: KeyValueWrapper, key: Uuid): Boolean? =
        keyValue.selectByKeyBootstrapSync(key) { _, data -> data }?.takeIf { it.isNotEmpty() }?.let { it[0].toInt() != 0 }

    private fun persistIfPending(databaseManager: DatabaseManager, scope: CoroutineScope?) {
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
