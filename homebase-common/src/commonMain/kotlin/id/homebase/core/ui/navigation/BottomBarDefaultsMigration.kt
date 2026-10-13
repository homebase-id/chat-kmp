package id.homebase.core.ui.navigation

import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.core.email.EmailPreferences
import id.homebase.core.vault.VaultPreferences
import kotlin.uuid.Uuid

/**
 * Vault and Email default to hidden. An install that already synced chat (logout wipes the DB, so
 * a chat cursor means a returning session) gets an explicit `true` for any icon it never toggled,
 * so its bar does not change under it. Runs once per identity DB.
 */
object BottomBarDefaultsMigration {
    val DONE_KEY: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000a0d01")
    private val pinnedVisible = listOf(VaultPreferences.ICON_VISIBLE_KEY, EmailPreferences.ICON_VISIBLE_KEY)

    /** Returns true when it wrote an icon value, so callers know to re-read the prefs. */
    suspend fun run(databaseManager: DatabaseManager): Boolean {
        val keyValue = databaseManager.keyValue
        if (keyValue.selectByKey(DONE_KEY) != null) return false
        var wrote = false
        if (keyValue.selectByKey(SystemDriveConstants.chatDrive.alias) != null) {
            for (key in pinnedVisible) {
                if (keyValue.selectByKey(key) == null) wrote = keyValue.upsertValue(key, byteArrayOf(1)) or wrote
            }
        }
        keyValue.upsertValue(DONE_KEY, byteArrayOf(1))
        return wrote
    }
}
