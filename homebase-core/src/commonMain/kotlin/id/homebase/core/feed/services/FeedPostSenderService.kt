package id.homebase.core.feed.services

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.drives.files.DeleteFilesByGroupIdOutboxRequest
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.files.SecurityGroupType
import id.homebase.api.client.drives.files.ThumbnailDescriptor
import id.homebase.api.client.drives.upload.SendContents
import id.homebase.api.client.drives.upload.TransitOptions
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.client.link.LinkPreview
import id.homebase.api.crypto.Md5
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.OutboxSync
import id.homebase.api.sync.database.enqueued
import id.homebase.chat.services.PayloadBundleEncryptor
import id.homebase.chat.services.builder.AttachmentInput
import id.homebase.chat.services.builder.LinkPreviewPayloadBuilder
import id.homebase.chat.services.builder.MessageAttachmentBuilder
import id.homebase.chat.services.outbox.OptimisticWriter
import id.homebase.chat.services.thumbnailDescriptorsFor
import kotlinx.coroutines.CoroutineScope
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.Uuid

/**
 * Create / edit / delete a feed post. Mirrors the post path of
 * [id.homebase.core.moments.services.MomentsPostSenderService] with feed substitutions:
 *
 *  - target drive = the channel drive (`alias = channelId`); the upload `driveId` is that alias.
 *  - `fileType = [FeedProtocol.PostFileType]` (101), `dataType = type.toDataType()`.
 *  - `uniqueId = Md5.toGuidId(slug)` so the same slug always addresses the same post.
 *  - media payload keys via [FeedProtocol.mediaPayloadKey]; link-preview key `pst_links`;
 *    content overflow → `pst_text` payload.
 *  - the caller's [AccessControlList] rides on `UploadFileMetadata.accessControlList`;
 *    a post is **unencrypted** when its security group is Anonymous/Authenticated (public),
 *    encrypted otherwise.
 *
 * [deletePost] soft-deletes the post and bulk-deletes its comments by `groupId`.
 */
@OptIn(ExperimentalEncodingApi::class)
class FeedPostSenderService(
    private val outboxSync: OutboxSync,
    private val payloadBundleEncryptor: PayloadBundleEncryptor,
    private val fileOps: FileOperationsProvider,
    private val driveFileProvider: DriveFileProvider,
    private val optimisticWriter: OptimisticWriter,
    private val scope: CoroutineScope,
) {

    companion object {
        private const val TAG = "FeedPostSenderService"
    }

    /**
     * Publish a new post to [channelId]. Returns the created post's uniqueId (derived from the
     * slug) once the upload is enqueued. The optimistic local write makes it appear immediately in
     * the author's feed via the channel drive [FeedTimelineService] aggregates.
     */
    suspend fun createPost(
        channelId: Uuid,
        type: PostType,
        caption: String,
        attachments: List<AttachmentInput>,
        linkPreview: LinkPreview?,
        acl: AccessControlList,
        reactAccess: ReactAccess = ReactAccess.All,
        embeddedPost: EmbeddedPost? = null,
        slug: String = defaultSlug(caption),
    ): CreatePostResult {
        val postUniqueId = Md5.toGuidId(slug)
        val isPublic = isPublicSecurityGroup(acl)
        val isEncrypted = !isPublic
        val keyHeader = if (isEncrypted) KeyHeader.newRandom16() else KeyHeader.Companion.empty()

        Logger.d(tag = TAG) {
            "createPost: channel=$channelId post=$postUniqueId type=$type encrypted=$isEncrypted " +
                "attachments=${attachments.size}"
        }

        // 1. Build media payloads (keyed pst_mdi_NN) + optional link preview (re-keyed to pst_links).
        val mediaBundle = MessageAttachmentBuilder.build(
            attachments = attachments,
            fileOperationsProvider = fileOps,
        ) { index, _ -> FeedProtocol.mediaPayloadKey(index) }

        val linkPayload = if (attachments.isEmpty() && linkPreview != null) {
            // LinkPreviewPayloadBuilder keys the payload with chat's PAYLOAD_KEY_LINKS; re-key it to
            // the feed link key so it matches the descriptor's expectation (pst_links).
            LinkPreviewPayloadBuilder.build(linkPreview, fileOps).payloads
                .map { it.copy(key = FeedProtocol.LinksPayloadKey) }
        } else {
            emptyList()
        }

        val primaryMediaKey = mediaBundle.payloads.firstOrNull()?.key

        // 2. Build the descriptor. If it overflows the header budget, spill the caption to pst_text.
        val (descriptor, overflowText) = buildDescriptor(
            postUniqueId = postUniqueId,
            channelId = channelId,
            type = type,
            caption = caption,
            slug = slug,
            reactAccess = reactAccess,
            primaryMediaKey = primaryMediaKey,
            embeddedPost = embeddedPost,
        )

        val overflowPayload = overflowText?.let {
            val path = fileOps.writeBytesToTempFile(
                bytes = it.encodeToByteArray(),
                prefix = "pst_text_",
                suffix = ".txt",
            )
            PayloadFile(
                key = FeedProtocol.FullTextPayloadKey,
                filePath = path,
                contentType = "text/plain",
            )
        }

        val plainPayloads = mediaBundle.payloads + linkPayload + listOfNotNull(overflowPayload)

        // 3. Encrypt the payload bundle when the post is encrypted; public posts ship plaintext.
        val (payloads, thumbnails, payloadDescriptors) = if (isEncrypted) {
            val encrypted = payloadBundleEncryptor.encryptBundle(
                uniqueId = postUniqueId,
                bundle = mediaBundle.copy(payloads = plainPayloads),
                aesKey = keyHeader.aesKey,
                scope = scope,
            )
            Triple(
                encrypted.payloads,
                encrypted.thumbnails,
                encrypted.payloads.map { payload ->
                    PayloadDescriptor(
                        key = payload.key,
                        contentType = payload.contentType.ifEmpty { null },
                        thumbnails = encrypted.thumbnailDescriptorsFor(payload.key),
                        iv = payload.iv?.let { Base64.encode(it) },
                        descriptorContent = payload.descriptorContent,
                        previewThumbnail = payload.previewThumbnail?.let {
                            ThumbnailDescriptor(
                                pixelWidth = it.pixelWidth,
                                pixelHeight = it.pixelHeight,
                                contentType = it.contentType,
                                content = it.content,
                            )
                        },
                    )
                }.ifEmpty { null },
            )
        } else {
            val descriptors = plainPayloads.map { payload ->
                PayloadDescriptor(
                    key = payload.key,
                    contentType = payload.contentType.ifEmpty { null },
                    descriptorContent = payload.descriptorContent,
                    previewThumbnail = payload.previewThumbnail?.let {
                        ThumbnailDescriptor(
                            pixelWidth = it.pixelWidth,
                            pixelHeight = it.pixelHeight,
                            contentType = it.contentType,
                            content = it.content,
                        )
                    },
                )
            }.ifEmpty { null }
            Triple(plainPayloads, mediaBundle.thumbnails, descriptors)
        }

        val metadata = buildMetadata(
            postUniqueId = postUniqueId,
            type = type,
            content = descriptor,
            isEncrypted = isEncrypted,
            isPublic = isPublic,
            acl = acl,
            previewThumbnail = mediaBundle.previewThumbs.minByOrNull { it.pixelWidth },
            versionTag = null,
        )

        val request = UploadFileRequest(
            driveId = channelId,
            keyHeader = keyHeader,
            metadata = if (isEncrypted) metadata.encryptContent(keyHeader) else metadata,
            payloads = payloads,
            thumbnails = thumbnails,
            transitOptions = TransitOptions(
                recipients = emptyList(),
                sendContents = SendContents.All,
                useAppNotification = false,
            ),
        )

        val enqueued = outboxSync.tryEnqueue(request)
        if (!enqueued.enqueued) {
            error("Failed to enqueue post for upload (outbox: $enqueued)")
        }
        Logger.d(tag = TAG) { "createPost: outbox enqueued post=$postUniqueId" }

        try {
            optimisticWriter.writeNewFile(
                driveId = channelId,
                keyHeader = keyHeader,
                unecryptedMetadata = metadata,
                originalRecipientCount = 0,
                fileSystemType = FileSystemType.Standard,
                payloadDescriptors = payloadDescriptors,
            )
        } catch (e: Exception) {
            Logger.e(throwable = e, tag = TAG) {
                "createPost: optimistic write failed (non-fatal) post=$postUniqueId"
            }
        }

        return CreatePostResult(uniqueId = postUniqueId, slug = slug)
    }

    /**
     * Edit an existing post's caption. The AES key from the original is reused (fresh IV) so the
     * media payloads stay decryptable; media is preserved (empty manifest semantics — we re-upload
     * header + descriptor only). Mirrors `MomentsPostSenderService.updateMoment`.
     */
    suspend fun updatePost(
        channelId: Uuid,
        postUniqueId: Uuid,
        versionTag: Uuid,
        caption: String,
        reactAccess: ReactAccess? = null,
    ): UpdatePostResult {
        val existing = driveFileProvider.getFileHeaderByUid(channelId, postUniqueId)
            ?: throw IllegalArgumentException("post not found: $postUniqueId")
        if (existing.fileMetadata.versionTag != versionTag) error("VersionTag mismatch")

        val existingContent = existing.fileMetadata.appData.content?.let { raw ->
            runCatching { OdinSystemSerializer.deserialize<PostContent>(raw) }.getOrNull()
        }
        val isEncrypted = existing.fileMetadata.isEncrypted
        val keyHeader = if (isEncrypted) {
            KeyHeader(iv = randomIv(), aesKey = existing.keyHeader.aesKey)
        } else {
            KeyHeader.Companion.empty()
        }

        val updated = (existingContent ?: PostContent(
            version = FeedProtocol.PostVersion,
            id = postUniqueId.toString(),
            channelId = channelId.toString(),
            type = PostType.Tweet,
            caption = caption,
            slug = postUniqueId.toString(),
        )).copy(
            caption = caption,
            reactAccess = reactAccess ?: existingContent?.reactAccess ?: ReactAccess.All,
        )

        val metadata = UploadFileMetadata(
            allowDistribution = existing.serverMetadata.allowDistribution,
            isEncrypted = isEncrypted,
            accessControlList = existing.serverMetadata.accessControlList,
            versionTag = versionTag,
            appData = UploadAppFileMetaData(
                uniqueId = postUniqueId,
                tags = existing.fileMetadata.appData.tags,
                fileType = FeedProtocol.PostFileType,
                dataType = updated.type.toDataType(),
                userDate = existing.fileMetadata.appData.userDate,
                content = OdinSystemSerializer.serialize(updated),
                previewThumbnail = existing.fileMetadata.appData.previewThumbnail,
            ),
        )

        val request = UploadFileRequest(
            driveId = channelId,
            keyHeader = keyHeader,
            metadata = if (isEncrypted) metadata.encryptContent(keyHeader) else metadata,
            transitOptions = TransitOptions(
                recipients = emptyList(),
                sendContents = SendContents.All,
                useAppNotification = false,
            ),
        )

        val enqueued = outboxSync.replaceEnqueue(request)
        if (!enqueued.enqueued) error("Failed to enqueue post update (outbox: $enqueued)")

        try {
            optimisticWriter.writeUpdate(
                driveId = channelId,
                keyHeader = keyHeader,
                unecryptedMetadata = metadata,
            )
        } catch (e: Exception) {
            Logger.e(throwable = e, tag = TAG) {
                "updatePost: optimistic write failed (non-fatal) post=$postUniqueId"
            }
        }

        return UpdatePostResult(uniqueId = postUniqueId)
    }

    /**
     * Soft-delete a post and bulk-delete all of its comments. The optimistic writer removes the
     * post from the local feed immediately; the outbox carries the post delete and a
     * [DeleteFilesByGroupIdOutboxRequest] that cleans up the comment files keyed by `groupId`.
     */
    suspend fun deletePost(channelId: Uuid, postUniqueId: Uuid) {
        val original = optimisticWriter.writeDelete(channelId, postUniqueId)
        if (original == null) {
            Logger.w(tag = TAG) { "deletePost: post $postUniqueId not found locally" }
            return
        }

        // 1. Delete the post file itself (recipients null = local + own-host removal).
        val postDelete = outboxSync.tryEnqueue(
            request = id.homebase.api.client.drives.files.DeleteLocalFilesByFileIdRequest(
                driveId = channelId,
                fileIds = listOf(original.fileId),
                recipients = null,
                hardDelete = false,
            ),
        )
        if (!postDelete.enqueued) {
            Logger.w(tag = TAG) { "deletePost: post delete enqueue -> $postDelete; rolling back" }
            optimisticWriter.rollbackWrite(channelId, original)
            return
        }

        // 2. Clean up the post's comments by groupId (= the post uniqueId).
        try {
            outboxSync.tryEnqueue(
                request = DeleteFilesByGroupIdOutboxRequest(
                    driveId = channelId,
                    groupIds = listOf(postUniqueId),
                ),
            )
        } catch (t: Throwable) {
            Logger.e(throwable = t, tag = TAG) {
                "deletePost: comment cleanup enqueue failed post=$postUniqueId (post already deleted)"
            }
        }
    }

    // -------------------- HELPERS --------------------

    private fun isPublicSecurityGroup(acl: AccessControlList): Boolean {
        val group = acl.requiredSecurityGroup?.let { SecurityGroupType.fromString(it) }
            ?: SecurityGroupType.Owner
        return group == SecurityGroupType.Anonymous || group == SecurityGroupType.Authenticated
    }

    /**
     * Build the [PostContent] JSON. When it would exceed [HomebaseProtocol.MaxHeaderContentBytes],
     * move the caption out to the returned overflow string (uploaded as the `pst_text` payload) and
     * blank the descriptor's caption. Returns (descriptorJson, overflowTextOrNull).
     */
    private fun buildDescriptor(
        postUniqueId: Uuid,
        channelId: Uuid,
        type: PostType,
        caption: String,
        slug: String,
        reactAccess: ReactAccess,
        primaryMediaKey: String?,
        embeddedPost: EmbeddedPost?,
    ): Pair<String, String?> {
        fun serialize(cap: String) = OdinSystemSerializer.serialize(
            PostContent(
                version = FeedProtocol.PostVersion,
                id = postUniqueId.toString(),
                channelId = channelId.toString(),
                type = type,
                caption = cap,
                slug = slug,
                reactAccess = reactAccess,
                embeddedPost = embeddedPost,
                primaryMediaKey = primaryMediaKey,
            )
        )

        val full = serialize(caption)
        if (full.encodeToByteArray().size <= id.homebase.api.HomebaseProtocol.MaxHeaderContentBytes) {
            return full to null
        }
        // Spill the caption to the pst_text payload; keep the descriptor lean.
        return serialize("") to caption
    }

    private fun buildMetadata(
        postUniqueId: Uuid,
        type: PostType,
        content: String,
        isEncrypted: Boolean,
        isPublic: Boolean,
        acl: AccessControlList,
        previewThumbnail: id.homebase.api.client.drives.upload.EmbeddedThumb?,
        versionTag: Uuid?,
    ): UploadFileMetadata = UploadFileMetadata(
        // Public and connected posts are distributed; a private/owner-only post is local.
        allowDistribution = isPublic || acl.requiredSecurityGroup
            ?.let { SecurityGroupType.fromString(it) } == SecurityGroupType.Connected,
        isEncrypted = isEncrypted,
        accessControlList = acl,
        versionTag = versionTag,
        appData = UploadAppFileMetaData(
            uniqueId = postUniqueId,
            // tags carry the content id (= the post uniqueId), matching dotyoucore-js.
            tags = listOf(postUniqueId),
            fileType = FeedProtocol.PostFileType,
            dataType = type.toDataType(),
            content = content,
            previewThumbnail = previewThumbnail,
        ),
    )

    private fun randomIv(): ByteArray = id.homebase.api.crypto.ByteArrayUtil.getRndByteArray(16)
}

/** Default URL-safe slug from a caption: lowercase, non-alphanumerics → '-', trimmed. */
internal fun defaultSlug(caption: String): String {
    val base = caption.lowercase()
        .map { if (it.isLetterOrDigit()) it else '-' }
        .joinToString("")
        .trim('-')
        .replace(Regex("-+"), "-")
        .take(80)
    return base.ifBlank { "post-${Uuid.random()}" }
}

data class CreatePostResult(val uniqueId: Uuid, val slug: String)
data class UpdatePostResult(val uniqueId: Uuid)
