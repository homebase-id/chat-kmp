@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.api.client.profile

import id.homebase.api.client.drives.QueryBatchRequest
import id.homebase.api.client.drives.QueryBatchResultOptionsRequest
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.drives.query.FileQueryParams
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Read + write source of truth for the owner's standard-profile attributes.
 *
 * READ goes straight to the ProfileDrive via [DriveQueryProvider.queryBatch] (`fileType = 77`) — a
 * one-shot, on-demand query (the ProfileDrive is intentionally NOT in `mandatorySyncDrives`, so
 * there's no local index to read). The query response already decrypts each file's content, so
 * parsing is plain JSON. WRITE goes through [ProfileProvider]; on a 409 (stale versionTag) this
 * re-reads the attribute and resends, bounded by `maxAttempts`.
 *
 * Mirrors the [id.homebase.api.client.contacts.ContactRepository] / `ContactsProvider` split.
 */
class ProfileRepository(
    private val driveQueryProvider: DriveQueryProvider,
    private val profileProvider: ProfileProvider,
) {
    private val profileDriveId: Uuid = SystemDriveConstants.profileDrive.alias

    /**
     * Reads every standard-profile attribute the app can see, newest file per attribute. Files that
     * fail to parse (corrupt content, missing id/versionTag) are skipped, not fatal.
     */
    suspend fun loadAttributes(): List<ProfileAttribute> {
        val response = driveQueryProvider.queryBatch(
            driveId = profileDriveId,
            request = QueryBatchRequest(
                queryParams = FileQueryParams(
                    fileType = listOf(ProfileProvider.PROFILE_ATTRIBUTE_FILE_TYPE),
                ),
                resultOptionsRequest = QueryBatchResultOptionsRequest(
                    maxRecords = 1000,
                    includeMetadataHeader = true,
                ),
            ),
        )

        return response.searchResults
            .mapNotNull { it.toProfileAttribute() }
            .distinctBy { it.id }
    }

    /**
     * Creates or edits the attribute of [type] holding [data].
     *
     * Pass [knownId] + [knownVersionTag] for an attribute the caller already read (goes straight to
     * EDIT); omit them to CREATE. On a 409 the stored versionTag was stale, so re-read the current
     * attribute (by id when known, else by type) and resend the same [data]. Bounded by [maxAttempts];
     * throws [IllegalStateException] if exhausted.
     */
    suspend fun save(
        type: String,
        data: JsonObject,
        visibility: ProfileVisibility,
        knownId: Uuid? = null,
        knownVersionTag: Uuid? = null,
        maxAttempts: Int = 3,
    ): ProfileWriteResponse {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }

        var id = knownId
        var versionTag = knownVersionTag

        repeat(maxAttempts) {
            val result = profileProvider.saveAttribute(
                type = type,
                id = id,
                expectedVersionTag = versionTag,
                visibility = visibility,
                data = data,
            )
            when (result) {
                is ProfileWriteResult.Ok -> return result.body
                ProfileWriteResult.Conflict -> {
                    // Stale tag — the server did not merge. Re-read the authoritative id/versionTag
                    // and resend the same data on the next iteration.
                    val fresh = reReadAttribute(type, id)
                        ?: error("profile attribute $type returned 409 but is no longer present")
                    id = fresh.id
                    versionTag = fresh.versionTag
                }
            }
        }

        error("profile attribute write contention exceeded $maxAttempts attempts for type $type")
    }

    /** DELETE the attribute; `false` if it no longer exists. */
    suspend fun delete(id: Uuid, versionTag: Uuid): Boolean =
        profileProvider.deleteAttribute(id, versionTag)

    /** Finds the current attribute by id when known, else the first of [type]. */
    private suspend fun reReadAttribute(type: String, id: Uuid?): ProfileAttribute? {
        val all = loadAttributes()
        return if (id != null) all.firstOrNull { it.id == id }
        else all.firstOrNull { it.type == type }
    }
}
