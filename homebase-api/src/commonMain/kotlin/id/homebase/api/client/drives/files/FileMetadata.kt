@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.api.client.drives.files

import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.client.drives.GlobalTransitIdFileIdentifier
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.serialization.UuidSerializer
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.serialization.Serializable
import id.homebase.api.common.OdinId
import id.homebase.api.common.OdinIdSerializerNullable

@Serializable
data class FileMetadata(
    @Serializable(with = UuidSerializer::class) val globalTransitId: Uuid? = null,
    val created: UnixTimeUtc = UnixTimeUtc.ZeroTime,
    val updated: UnixTimeUtc = UnixTimeUtc.ZeroTime,
    val transitCreated: UnixTimeUtc = UnixTimeUtc.ZeroTime,
    val transitUpdated: UnixTimeUtc = UnixTimeUtc.ZeroTime,
    val isEncrypted: Boolean = false,
    @Serializable(with = OdinIdSerializerNullable::class) val senderOdinId: OdinId? = null,
    @Serializable(with = OdinIdSerializerNullable::class) val originalAuthor: OdinId? = null,
    val appData: AppFileMetaData = AppFileMetaData(),
    val localAppData: LocalAppMetadata? = null,
    val referencedFile: GlobalTransitIdFileIdentifier? = null,
    val reactionPreview: ReactionSummary? = null,
    @Serializable(with = UuidSerializer::class) val versionTag: Uuid? = null,
    val payloads: List<PayloadDescriptor>? = null,
    val dataSource: DataSource? = null
) {
    fun getPayloadDescriptor(key: String): PayloadDescriptor? {
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
    val previewThumbnail: EmbeddedThumb? = null,
    val archivalStatus: ArchivalStatus? = null
)

@Serializable
data class LocalAppMetadata(
    val tags: List<@Serializable(with = UuidSerializer::class) Uuid>? = null,
    val versionTag: Uuid? = null,
    val iv: String? = null,
    val content: String? = null,
    val readTime: UnixTimeUtc? = null
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
    val reactions: List<ReactionEntry> = emptyList()
)

/**
 * Reaction summary (also known as ReactionPreview in TypeScript) Contains comments, reactions, and
 * total comment count
 */
@Serializable
data class ReactionSummary(
    val comments: List<CommentPreview> = emptyList(),
    val reactions: Map<String, ReactionEntry> = emptyMap(),
    val totalCommentCount: Int = 0
)

@Serializable
data class DataSource(
    val identity: String,
    @Serializable(with = UuidSerializer::class) val driveId: Uuid,
    val payloadsAreRemote: Boolean = false
)
