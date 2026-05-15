package id.homebase.api.file

import co.touchlab.kermit.Logger
import okio.FileSystem

/**
 * Subdirectory of the app cache where the chat "Share to other app" flow
 * writes its plaintext-decrypted Homebase payloads. Sequestered so the
 * security-relevant temp files (Category 2: cleartext copies of
 * end-to-end-encrypted chat content) never mingle with general cache
 * scratch and can be reaped as a single unit.
 *
 * The flow itself: `MediaDownloadHandler.handleShareMedia` decrypts a
 * payload, writes the bytes here under a `share_<random>.<ext>` name, and
 * hands the path to `Intent.ACTION_SEND` via Android `FileProvider`. The
 * receiving app reads the file via the URI grant; nothing in the standard
 * Android flow deletes it afterwards. [sweepShareOutbound] is what does.
 */
const val SHARE_OUTBOUND_DIR_NAME: String = "share_outbound"

/**
 * Recursively delete the share-outbound subdirectory under [cacheDirPath].
 * Best-effort: a missing dir is a quiet no-op, a delete failure is logged.
 *
 * Called from:
 *  - cold startup, alongside [CacheAudit] / [CacheSweeper], so a cleartext
 *    file that survived a process death gets reaped on next launch;
 *  - app foreground (Android `ProcessLifecycleOwner.ON_START`), so the
 *    moment the user returns to the chat app after a share, the cleartext
 *    is gone — bounding the on-disk lifetime to "user's time in another
 *    app";
 *  - logout (full sweep), so the cleartext doesn't outlive the session.
 */
fun sweepShareOutbound(
    cacheDirPath: String,
    fileSystem: FileSystem = systemFileSystem,
): Boolean {
    val deleted = safeDeleteRecursively(cacheDirPath, SHARE_OUTBOUND_DIR_NAME, fileSystem)
    if (deleted) {
        Logger.i(tag = "ShareOutbound") {
            "swept $cacheDirPath/$SHARE_OUTBOUND_DIR_NAME (cleartext share temps)"
        }
    }
    return deleted
}
