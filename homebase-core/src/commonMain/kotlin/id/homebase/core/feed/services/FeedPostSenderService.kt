package id.homebase.core.feed.services

import co.touchlab.kermit.Logger
import id.homebase.api.HomebaseProtocol
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.drives.files.DeleteFilesByGroupIdOutboxRequest
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.files.SecurityGroupType
import id.homebase.api.client.drives.upload.SendContents
import id.homebase.api.client.drives.upload.TransitOptions
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.crypto.Md5
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.OutboxSync
import id.homebase.chat.services.builder.AttachmentInput
import id.homebase.chat.services.builder.MessageAttachmentBuilder
import id.homebase.chat.services.outbox.MutationOutcome
import id.homebase.chat.services.outbox.OptimisticWriter
import id.homebase.core.settings.UserPreferences
import id.homebase.upload.MediaUploadSpec
import id.homebase.upload.UploadOutcome
import id.homebase.upload.UploadService
import kotlinx.coroutines.CoroutineScope
import kotlin.uuid.Uuid

class FeedPostSenderService(
    private val outboxSync: OutboxSync,
    private val uploadService: UploadService,
    private val optimisticWriter: OptimisticWriter,
    private val fileOps: FileOperationsProvider,
    private val userPreferences: UserPreferences,
    private val scope: CoroutineScope,
) {

    companion object {
        private const val TAG = "FeedPostSenderService"
    }

    // A public post ships plaintext payloads (no encryptBundle); anything narrower goes through UploadService.
    suspend fun createPost(
        channelId: Uuid,
        caption: String,
        attachments: List<AttachmentInput>,
        acl: AccessControlList,
        reactAccess: ReactAccess = ReactAccess.All,
        slug: String = uniqueSlug(caption),
    ): CreatePostResult {
        val text = caption.trim()
        require(text.isNotEmpty() || attachments.isNotEmpty()) { "a post needs text or media" }

        val postUniqueId = Md5.toGuidId(slug)
        val group = acl.requiredSecurityGroup?.let { SecurityGroupType.fromString(it) } ?: SecurityGroupType.Owner
        val isPublic = group == SecurityGroupType.Anonymous || group == SecurityGroupType.Authenticated
        val isEncrypted = !isPublic
        val keyHeader = if (isEncrypted) KeyHeader.newRandom16() else KeyHeader.empty()
        val type = if (attachments.isEmpty()) PostType.Tweet else PostType.Media

        val mediaBundle = MessageAttachmentBuilder.build(
            attachments = attachments,
            fileOperationsProvider = fileOps,
            mediaQuality = userPreferences.mediaQuality,
        ) { index, _ -> FeedProtocol.mediaPayloadKey(index) }

        val (descriptor, overflowText) = buildDescriptor(
            postUniqueId = postUniqueId,
            channelId = channelId,
            type = type,
            caption = text,
            slug = slug,
            reactAccess = reactAccess,
            primaryMediaKey = mediaBundle.payloads.firstOrNull()?.key,
        )
        val overflowPayload = overflowText?.let {
            PayloadFile(
                key = FeedProtocol.FullTextPayloadKey,
                filePath = fileOps.writeBytesToTempFile(it.encodeToByteArray(), "pst_text_", ".txt"),
                contentType = "text/plain",
            )
        }
        val bundle = overflowPayload?.let { mediaBundle.copy(payloads = mediaBundle.payloads + it) } ?: mediaBundle

        val metadata = UploadFileMetadata(
            allowDistribution = isPublic || group == SecurityGroupType.Connected,
            isEncrypted = isEncrypted,
            accessControlList = acl,
            appData = UploadAppFileMetaData(
                uniqueId = postUniqueId,
                tags = listOf(postUniqueId),
                fileType = FeedProtocol.PostFileType,
                dataType = type.toDataType(),
                userDate = UnixTimeUtc.now().milliseconds,
                content = descriptor,
                previewThumbnail = mediaBundle.previewThumbs.minByOrNull { it.pixelWidth },
            ),
        )
        val transit = TransitOptions(
            recipients = emptyList(),
            sendContents = SendContents.All,
            useAppNotification = false,
        )

        if (isEncrypted) {
            val outcome = uploadService.upload(
                MediaUploadSpec(
                    driveId = channelId,
                    uniqueId = postUniqueId,
                    keyHeader = keyHeader,
                    bundle = bundle,
                    metadata = metadata,
                    transit = transit,
                ),
                scope = scope,
            )
            if (outcome !is UploadOutcome.Enqueued) error("Failed to enqueue post for upload (outbox: $outcome)")
        } else {
            enqueuePlaintextUpload(
                outboxSync = outboxSync,
                optimisticWriter = optimisticWriter,
                request = UploadFileRequest(
                    driveId = channelId,
                    keyHeader = keyHeader,
                    metadata = metadata,
                    payloads = bundle.payloads,
                    thumbnails = bundle.thumbnails,
                    transitOptions = transit,
                ),
                tag = TAG,
            )
        }

        return CreatePostResult(uniqueId = postUniqueId, slug = slug)
    }

    // Past the header budget the caption moves to the pst_text payload and the descriptor keeps an empty one.
    private fun buildDescriptor(
        postUniqueId: Uuid,
        channelId: Uuid,
        type: PostType,
        caption: String,
        slug: String,
        reactAccess: ReactAccess,
        primaryMediaKey: String?,
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
                primaryMediaKey = primaryMediaKey,
            ),
        )
        val full = serialize(caption)
        if (full.encodeToByteArray().size <= HomebaseProtocol.MaxHeaderContentBytes) return full to null
        return serialize("") to caption
    }

    suspend fun deletePost(channelId: Uuid, postUniqueId: Uuid) {
        // recipients null = local + own-host removal.
        val outcome = optimisticWriter.deleteFile(channelId, postUniqueId, recipients = null)
        if (outcome != MutationOutcome.Queued) {
            Logger.w(tag = TAG) { "deletePost: post $postUniqueId → $outcome; skipping comment cleanup" }
            return
        }

        // The post's comments are keyed by groupId (= the post uniqueId).
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
}

internal fun uniqueSlug(caption: String): String {
    val base = caption.lowercase()
        .map { if (it.isLetterOrDigit()) it else '-' }
        .joinToString("")
        .replace(Regex("-+"), "-")
        .trim('-')
        .take(60)
    return "${base.ifBlank { "post" }}-${Uuid.random().toString().take(8)}"
}

data class CreatePostResult(val uniqueId: Uuid, val slug: String)
