package id.homebase.photobase.backup

enum class BackupMediaType(val code: Long) {
    Image(0),
    Video(1),
    LivePhoto(2);

    companion object {
        fun ofCode(code: Long): BackupMediaType = entries.first { it.code == code }
    }
}

data class DeviceAsset(
    val id: String,
    val mediaType: BackupMediaType,
    val createdAtMs: Long,
    val modifiedAtMs: Long,
    val sizeBytes: Long,
    val albumIds: List<String> = emptyList(),
)

data class DeviceChanges(
    val assets: List<DeviceAsset>,
    val deletedIds: List<String>,
    val newCheckpoint: Map<String, String>,
    /** Every asset id on the device in scope; null when this pass didn't list them. The ledger diffs against it. */
    val currentIds: Set<String>?,
)

data class DeviceAlbum(val id: String, val name: String)

enum class ExportedPartRole { Primary, PairedVideo }

/**
 * [isTemporary] parts live in upload-temp and are deleted by whoever asked for the export; the
 * others are references to the original, read in place.
 */
data class ExportedPart(
    val role: ExportedPartRole,
    val path: String,
    val contentType: String,
    val isTemporary: Boolean,
)

data class ExportedAsset(val parts: List<ExportedPart>, val fileName: String)

/** The asset can't be backed up and retrying won't change that. Becomes a SKIPPED row with [reason]. */
class AssetPermanentlyUnavailableException(val reason: String) : Exception(reason)

/**
 * What changed in the device's photo library.
 *
 * Contract for every implementation:
 * - [changes] given an empty or invalid [checkpoint] (expired token, new MediaStore version) lists
 *   everything in scope. It never throws for a stale checkpoint.
 * - An asset edited on the device comes back in `assets`, with a new `modifiedAtMs`.
 * - When [wantCurrentIds] is true, `currentIds` is the complete id set in scope, so deletions can
 *   be found even where the OS's own delta doesn't report them. It can be expensive; short budgets
 *   don't ask for it.
 * - `newCheckpoint` covers everything returned, and is safe to store only after the result has
 *   been committed to the ledger. Interrupted callers redo the work rather than skip it.
 * - [albumIds] empty means the default scope, the camera roll. Partial library access returns only
 *   what's granted.
 * - [export] returns the original bytes, downloading from cloud storage if needed, with no
 *   location or metadata removed. An asset that no longer exists throws
 *   [AssetPermanentlyUnavailableException]. A Live Photo returns the still first, then the video.
 */
interface DeviceChangeSource {
    suspend fun changes(checkpoint: Map<String, String>, albumIds: Set<String>, wantCurrentIds: Boolean): DeviceChanges

    suspend fun export(assetId: String, mediaType: BackupMediaType): ExportedAsset

    suspend fun albums(): List<DeviceAlbum>
}
