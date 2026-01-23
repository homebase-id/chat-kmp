@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.homebasekmppoc.prototype.lib.drives.files

import id.homebase.homebasekmppoc.prototype.lib.core.time.UnixTimeUtc
import id.homebase.homebasekmppoc.prototype.lib.drives.GlobalTransitIdFileIdentifier
import id.homebase.homebasekmppoc.prototype.lib.serialization.UuidSerializer
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.serialization.Serializable

/** Client file metadata Ported from C# Odin.Services.Apps.ClientFileMetadata */
@Serializable
data class FileMetadata(
    @Serializable(with = UuidSerializer::class) val globalTransitId: Uuid? = null,
    val created: UnixTimeUtc = UnixTimeUtc.ZeroTime,
    val updated: UnixTimeUtc = UnixTimeUtc.ZeroTime,
    val transitCreated: UnixTimeUtc = UnixTimeUtc.ZeroTime,
    val transitUpdated: UnixTimeUtc = UnixTimeUtc.ZeroTime,
    val isEncrypted: Boolean = false,
    val senderOdinId: String? = null,
    val originalAuthor: String? = null,
    val appData: id.homebase.homebasekmppoc.prototype.lib.drives.files.AppFileMetaData = _root_ide_package_.id.homebase.homebasekmppoc.prototype.lib.drives.files.AppFileMetaData(),
    val localAppData: id.homebase.homebasekmppoc.prototype.lib.drives.files.LocalAppMetadata? = null,
    val referencedFile: id.homebase.homebasekmppoc.prototype.lib.drives.GlobalTransitIdFileIdentifier? = null,
    val reactionPreview: id.homebase.homebasekmppoc.prototype.lib.drives.files.ReactionSummary? = null,
    @Serializable(with = UuidSerializer::class) val versionTag: Uuid? = null,
    val payloads: List<id.homebase.homebasekmppoc.prototype.lib.drives.files.PayloadDescriptor>? = null,
    val dataSource: id.homebase.homebasekmppoc.prototype.lib.drives.files.DataSource? = null
) {
        fun getPayloadDescriptor(key: String): id.homebase.homebasekmppoc.prototype.lib.drives.files.PayloadDescriptor? {
                return payloads?.firstOrNull { it.keyEquals(key) }
        }
}

@Serializable
data class AppFileMetaData(
    @Serializable(with = UuidSerializer::class) val uniqueId: Uuid? = null,
    val tags: List<@Serializable(with = UuidSerializer::class) Uuid>? = null,
    val fileType: Int? = null,
    val dataType: Int? = null,
    @Serializable(with = UuidSerializer::class) val groupId: Uuid? = null,
    val userDate: Long? = null,
    val content: String? = null,
    val previewThumbnail: id.homebase.homebasekmppoc.prototype.lib.drives.files.ThumbnailDescriptor? = null,
    val archivalStatus: id.homebase.homebasekmppoc.prototype.lib.drives.files.ArchivalStatus? = null
)

@Serializable
data class LocalAppMetadata(
        val tags: List<@Serializable(with = UuidSerializer::class) Uuid>? = null,
        val versionTag: Uuid? = null,
        val iv: String? = null,
        val content: String? = null
)

/** Reaction entry for both comments and summary reactions */
@Serializable
data class ReactionEntry(val key: String, val count: Int, val reactionContent: String)

/** Comment preview within a reaction summary */
@Serializable
data class CommentPreview(
        val created: Long,
        val updated: Long,
        val fileId: String,
        val isEncrypted: Boolean,
        val odinId: String,
        val content: String,
        val reactions: List<id.homebase.homebasekmppoc.prototype.lib.drives.files.ReactionEntry> = emptyList()
)

/**
 * Reaction summary (also known as ReactionPreview in TypeScript) Contains comments, reactions, and
 * total comment count
 */
@Serializable
data class ReactionSummary(
    val comments: List<id.homebase.homebasekmppoc.prototype.lib.drives.files.CommentPreview> = emptyList(),
    val reactions: Map<String, id.homebase.homebasekmppoc.prototype.lib.drives.files.ReactionEntry> = emptyMap(),
    val totalCommentCount: Int = 0
)

@Serializable
data class DataSource(
        val identity: String,
        @Serializable(with = UuidSerializer::class) val driveId: Uuid,
        val payloadsAreRemote: Boolean = false
)
