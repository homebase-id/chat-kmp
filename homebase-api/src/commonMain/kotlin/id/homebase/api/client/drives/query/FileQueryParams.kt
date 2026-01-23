package id.homebase.homebasekmppoc.prototype.lib.drives.query

import id.homebase.homebasekmppoc.prototype.lib.core.time.UnixTimeUtcRange
import id.homebase.homebasekmppoc.prototype.lib.drives.FileState
import id.homebase.homebasekmppoc.prototype.lib.drives.FileSystemType
import id.homebase.homebasekmppoc.prototype.lib.drives.TargetDrive
import id.homebase.homebasekmppoc.prototype.lib.serialization.UuidSerializer
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * File query parameters for filtering drive files
 *
 * Ported from C# Odin.Services.Drives.DriveCore.Query.FileQueryParams
 */
@Serializable
data class FileQueryParams(
    val fileType: List<Int>? = null,
    val dataType: List<Int>? = null,
    val fileState: List<id.homebase.homebasekmppoc.prototype.lib.drives.FileState>? = null,
    val archivalStatus: List<Int>? = null,
    val sender: List<String>? = null, // Todo: senderId
    val groupId: List<@Serializable(with = UuidSerializer::class) Uuid>? = null,
    val userDate: UnixTimeUtcRange? = null,
    val userDateStart: Long? = null,
    val userDateEnd: Long? = null,
    val clientUniqueIdAtLeastOne: List<@Serializable(with = UuidSerializer::class) Uuid>? = null,
    val tagsMatchAtLeastOne: List<@Serializable(with = UuidSerializer::class) Uuid>? = null,
    val tagsMatchAll: List<@Serializable(with = UuidSerializer::class) Uuid>? = null,
    val localTagsMatchAtLeastOne: List<@Serializable(with = UuidSerializer::class) Uuid>? = null,
    val localTagsMatchAll: List<@Serializable(with = UuidSerializer::class) Uuid>? = null,
    val globalTransitId: List<@Serializable(with = UuidSerializer::class) Uuid>? = null,
    val fileSystemType: id.homebase.homebasekmppoc.prototype.lib.drives.FileSystemType? = null,
) {

}