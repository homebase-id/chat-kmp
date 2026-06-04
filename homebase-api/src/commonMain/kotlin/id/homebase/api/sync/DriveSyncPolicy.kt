package id.homebase.api.sync

import id.homebase.api.client.drives.query.FileQueryParams
import kotlin.time.Duration

/**
 * Per-drive overrides for the FRESH-sync path (first login, or a discarded stale
 * cursor — i.e. `cursor == null` when [DriveSync.performSync] starts). Both fields
 * are inert on incremental syncs, where the saved cursor is resumed unchanged.
 *
 * @param fullSyncWindow How far back to crawl on a fresh sync. `null` (default) =
 *   sync everything, the historical behavior. Applied by seeding the QueryBatch
 *   paging cursor to `now - fullSyncWindow` before the main crawl loop; with the
 *   crawl's `OldestFirst` / `AnyChangeDate` ordering the server then returns only
 *   rows changed after that floor.
 * @param initialQueries Custom queries run once, fully paginated, at the START of a
 *   fresh sync — BEFORE the windowed crawl. Their results are upserted with
 *   `cursor = null` so they NEVER advance the drive's persisted CursorStorage
 *   position. The chat drive uses one entry (`fileType = [ConversationFileType]`)
 *   to pull the complete conversation-file list even though messages are windowed
 *   to [fullSyncWindow]. Empty (default) = no initial query.
 */
data class DriveSyncPolicy(
    val fullSyncWindow: Duration? = null,
    val initialQueries: List<FileQueryParams> = emptyList(),
)
