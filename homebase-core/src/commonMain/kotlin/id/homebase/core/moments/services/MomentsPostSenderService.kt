package id.homebase.core.moments.services

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.ThumbnailDescriptor
import id.homebase.api.client.drives.upload.FileUpdateInstructionSet
import id.homebase.api.client.drives.upload.PushNotificationOptions
import id.homebase.api.client.drives.upload.SendContents
import id.homebase.api.client.drives.upload.TransitOptions
import id.homebase.api.client.drives.upload.UpdateFileByUniqueIdRequest
import id.homebase.api.client.drives.upload.UpdateLocale
import id.homebase.api.client.drives.upload.UpdateManifest
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.common.OdinId
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.OutboxSync
import id.homebase.chat.services.PayloadBundleEncryptor
import id.homebase.chat.services.builder.AttachmentInput
import id.homebase.chat.services.builder.MessageAttachmentBuilder
import id.homebase.chat.services.outbox.OptimisticWriter
import id.homebase.core.config.momentsLabeledDrive
import kotlinx.coroutines.CoroutineScope
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.Uuid

class MomentsPostSenderService(
    private val outboxSync: OutboxSync,
    private val payloadBundleEncryptor: PayloadBundleEncryptor,
    private val fileOps: FileOperationsProvider,
    private val driveFileProvider: DriveFileProvider,
    private val optimisticWriter: OptimisticWriter,
    private val credentialsManager: CredentialsManager,
    private val dbm: DatabaseManager,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "MomentsPostSenderService"
    }

    private val drive = momentsLabeledDrive.drive.alias

    suspend fun postMoment(
        attachments: List<AttachmentInput>,
        description: String,
        recipients: List<OdinId>,
        momentUniqueId: Uuid = Uuid.random(),
        userDate: UnixTimeUtc? = null,
        source: MomentSource? = null,
        /**
         * Per-attachment image metadata aligned with [attachments] (same length
         * and order). Null entries are dropped — the resulting MomentPostContent
         * only carries entries for images the user opted in for.
         */
        mediaInfoByAttachment: List<MediaInfo?>? = null,
        commentsEnabled: Boolean = true,
    ): PostMomentResult {
        Logger.d(tag = TAG) {
            "postMoment: starting moment=$momentUniqueId attachments=${attachments.size} recipients=${recipients.size} source=$source"
        }

        // Server constraint: payload keys must match ^[a-z0-9_]{8,10}$. The
        // padStart keeps the key fixed at 8 chars across single- and multi-digit
        // indices (mmt_0000 .. mmt_9999). Capturing as a local so the same scheme
        // names both the bundle payloads and the MediaInfo map keys.
        val keyForIndex: (Int) -> String = { i -> "mmt_${i.toString().padStart(4, '0')}" }
        val bundle = MessageAttachmentBuilder.build(
            attachments = attachments,
            fileOperationsProvider = fileOps,
        ) { index, _ -> keyForIndex(index) }

        val keyedMediaInfo: Map<String, MediaInfo>? = mediaInfoByAttachment
            ?.mapIndexedNotNull { i, mi -> mi?.let { keyForIndex(i) to it } }
            ?.toMap()
            ?.takeIf { it.isNotEmpty() }

        val keyHeader = KeyHeader.newRandom16()
        val encrypted = payloadBundleEncryptor.encryptBundle(
            uniqueId = momentUniqueId,
            bundle = bundle,
            aesKey = keyHeader.aesKey,
            scope = scope,
        )

        val isLocalOnly = recipients.isEmpty()
        val effectiveUserDate = userDate ?: UnixTimeUtc.now()

        val content = OdinSystemSerializer.serialize(
            MomentPostContent(
                version = MomentsProtocol.MomentPostVersionNumberOne,
                description = description,
                recipients = recipients,
                source = source,
                mediaInfo = keyedMediaInfo,
                commentsEnabled = commentsEnabled,
            )
        )

        // Unencrypted, queryable: lets callers find moments tied to a
        // source (conversation id or any of the audience's groups) via
        // `tagsMatchAtLeastOne = listOf(...)` without decrypting
        // `MomentPostContent`.
        val tags: List<Uuid>? = when (source) {
            is MomentSource.Conversation -> listOf(source.conversationId)
            is MomentSource.Audience -> source.groupIds.ifEmpty { null }
            null -> null
        }

        val unencryptedMetadata = UploadFileMetadata(
            allowDistribution = !isLocalOnly,
            isEncrypted = true,
            appData = UploadAppFileMetaData(
                uniqueId = momentUniqueId,
                tags = tags,
                fileType = MomentsProtocol.MomentPostFileType,
                userDate = effectiveUserDate.milliseconds,
                content = content,
                previewThumbnail = encrypted.previewThumbs.minByOrNull { it.pixelWidth },
            ),
        )

        val request = UploadFileRequest(
            driveId = drive,
            keyHeader = keyHeader,
            metadata = unencryptedMetadata.encryptContent(keyHeader),
            transitOptions = TransitOptions(
                recipients = recipients,
                sendContents = SendContents.All,
                useAppNotification = !isLocalOnly,
                appNotificationOptions = if (isLocalOnly) null else PushNotificationOptions(
                    appId = MomentsProtocol.MomentsAppId.toString(),
                    typeId = momentUniqueId.toString(),
                    tagId = momentUniqueId.toString(),
                    silent = false,
                    unEncryptedMessage = "New moment posted",
                ),
            ),
            payloads = encrypted.payloads,
            thumbnails = encrypted.thumbnails,
        )

        val enqueued = outboxSync.tryEnqueue(
            request,
            priority = 1,
            dependencyUniqueId = null,
        )

        if (!enqueued) {
            error("Failed to enqueue moment for upload")
        }

        Logger.d(tag = TAG) { "postMoment: outbox enqueued moment=$momentUniqueId" }

        // Best-effort optimistic write — surfaces the moment in the feed
        // immediately so the user sees their own post without waiting for
        // outbox drain → server → sync replay. If this fails the outbox
        // delivery + sync will still bring it back.
        @OptIn(ExperimentalEncodingApi::class)
        val payloadDescriptors = encrypted.payloads.map { payload ->
            PayloadDescriptor(
                key = payload.key,
                contentType = payload.contentType.ifEmpty { null },
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
        }.ifEmpty { null }
        try {
            optimisticWriter.writeNewFile(
                driveId = drive,
                keyHeader = keyHeader,
                unecryptedMetadata = unencryptedMetadata,
                originalRecipientCount = recipients.size,
                fileSystemType = FileSystemType.Standard,
                payloadDescriptors = payloadDescriptors,
            )
            Logger.d(tag = TAG) { "postMoment: optimistic write complete moment=$momentUniqueId" }
        } catch (e: Exception) {
            Logger.e(throwable = e, tag = TAG) {
                "postMoment: optimistic write failed (non-fatal) moment=$momentUniqueId"
            }
        }

        return PostMomentResult(uniqueId = momentUniqueId)
    }

    /**
     * Edit the description of an existing moment. Mirrors
     * [id.homebase.chat.services.ChatMessageSenderService.updateMessage]: the
     * AES key from the original moment is reused (only the IV is regenerated)
     * so the existing media payloads remain decryptable, the manifest carries
     * no payload changes (media is preserved), and the request goes through
     * `replaceEnqueue` so a still-pending earlier post/edit for the same
     * moment is superseded rather than racing.
     *
     * Reads the current file header from the drive to recover the AES key and
     * to seed `userDate` / `previewThumbnail` so the edit doesn't blank them.
     */
    suspend fun updateMoment(
        momentUniqueId: Uuid,
        versionTag: Uuid,
        description: String,
        recipients: List<OdinId>,
    ): UpdateMomentResult {
        Logger.d(tag = TAG) {
            "updateMoment: starting moment=$momentUniqueId recipients=${recipients.size}"
        }

        val existing = driveFileProvider.getFileHeaderByUid(drive, momentUniqueId)
            ?: throw IllegalArgumentException("moment not found: $momentUniqueId")

        if (existing.fileMetadata.versionTag != versionTag) {
            error("VersionTag mismatch")
        }

        val keyHeader = KeyHeader(
            iv = ByteArrayUtil.getRndByteArray(16),
            aesKey = existing.keyHeader.aesKey,
        )

        val isLocalOnly = recipients.isEmpty()

        // Preserve audience/source from the existing file so an edit doesn't
        // blank fields the caller never sees. Drive query layer hands back
        // plaintext content (same path MomentsFeedService.toFeedItem uses), so
        // no explicit decryption is needed here.
        val existingContent = existing.fileMetadata.appData.content?.let { raw ->
            runCatching {
                OdinSystemSerializer.deserialize<MomentPostContent>(raw)
            }.getOrNull()
        }

        val content = OdinSystemSerializer.serialize(
            MomentPostContent(
                version = MomentsProtocol.MomentPostVersionNumberOne,
                description = description,
                recipients = existingContent?.recipients ?: emptyList(),
                source = existingContent?.source,
                // Preserve author's original comments choice; default-true
                // matches legacy posts that pre-date this field.
                commentsEnabled = existingContent?.commentsEnabled ?: true,
            )
        )

        val unencryptedMetadata = UploadFileMetadata(
            allowDistribution = !isLocalOnly,
            isEncrypted = true,
            versionTag = versionTag,
            appData = UploadAppFileMetaData(
                uniqueId = momentUniqueId,
                tags = existing.fileMetadata.appData.tags,
                fileType = MomentsProtocol.MomentPostFileType,
                userDate = existing.fileMetadata.appData.userDate,
                content = content,
                previewThumbnail = existing.fileMetadata.appData.previewThumbnail,
            ),
        )

        // Empty manifest: no payload appends, no payload deletes — the
        // existing media payloads on the file are left intact.
        val manifest = UpdateManifest.build(
            payloads = null,
            toDeletePayloads = null,
            thumbnails = null,
            generatePayloadIv = false,
        )

        val request = UpdateFileByUniqueIdRequest(
            driveId = drive,
            uniqueId = momentUniqueId,
            keyHeader = keyHeader,
            instructions = FileUpdateInstructionSet(
                transferIv = ByteArrayUtil.getRndByteArray(16),
                locale = UpdateLocale.Local,
                recipients = recipients,
                manifest = manifest,
                useAppNotification = false,
                appNotificationOptions = null,
            ),
            metadata = unencryptedMetadata.encryptContent(keyHeader),
            payloads = emptyList(),
            thumbnails = emptyList(),
        )

        val enqueued = outboxSync.replaceEnqueue(
            request,
            priority = 1,
            dependencyUniqueId = null,
        )

        if (!enqueued) {
            error("Failed to enqueue moment update")
        }

        Logger.d(tag = TAG) { "updateMoment: outbox enqueued moment=$momentUniqueId" }

        // Best-effort optimistic write — applies the description edit to the
        // local copy so the detail screen reflects it immediately. Media
        // payloads on the existing file are preserved (we don't touch them).
        try {
            optimisticWriter.writeUpdate(
                driveId = drive,
                keyHeader = keyHeader,
                unecryptedMetadata = unencryptedMetadata,
            )
            Logger.d(tag = TAG) { "updateMoment: optimistic write complete moment=$momentUniqueId" }
        } catch (e: Exception) {
            Logger.e(throwable = e, tag = TAG) {
                "updateMoment: optimistic write failed (non-fatal) moment=$momentUniqueId"
            }
        }

        return UpdateMomentResult(uniqueId = momentUniqueId)
    }

    /**
     * Post a comment against an existing moment. Recipients are derived
     * from the moment itself — caller only needs `momentId` and the comment
     * payload.
     *
     * Audience math: a moment's audience is `senderOdinId ∪ content.recipients`.
     * The commenter sends to `audience - self`, so:
     *   - author commenting → goes to original recipients
     *   - recipient commenting → goes to author + other recipients
     *
     * `groupId = momentId` ties comments to their moment so feed queries
     * (`groupIdAnyOf = listOf(momentId)`) return them as a batch.
     */
    suspend fun postComment(
        momentId: Uuid,
        attachments: List<AttachmentInput>,
        body: String,
        commentUniqueId: Uuid = Uuid.random(),
        userDate: UnixTimeUtc? = null,
    ): PostMomentCommentResult {
        Logger.d(tag = TAG) {
            "postComment: starting moment=$momentId comment=$commentUniqueId attachments=${attachments.size}"
        }

        val recipients = resolveCommentRecipients(momentId)

        val bundle = MessageAttachmentBuilder.build(
            attachments = attachments,
            fileOperationsProvider = fileOps,
        ) { index, _ -> "mmc_${index.toString().padStart(4, '0')}" }

        val keyHeader = KeyHeader.newRandom16()
        val encrypted = payloadBundleEncryptor.encryptBundle(
            uniqueId = commentUniqueId,
            bundle = bundle,
            aesKey = keyHeader.aesKey,
            scope = scope,
        )

        val isLocalOnly = recipients.isEmpty()
        val effectiveUserDate = userDate ?: UnixTimeUtc.now()

        val content = OdinSystemSerializer.serialize(
            MomentCommentContent(
                version = MomentsProtocol.MomentCommentVersionNumberOne,
                body = body,
            )
        )

        val unencryptedMetadata = UploadFileMetadata(
            allowDistribution = !isLocalOnly,
            isEncrypted = true,
            appData = UploadAppFileMetaData(
                uniqueId = commentUniqueId,
                groupId = momentId,
                fileType = MomentsProtocol.MomentCommentFileType,
                userDate = effectiveUserDate.milliseconds,
                content = content,
                previewThumbnail = encrypted.previewThumbs.minByOrNull { it.pixelWidth },
            ),
        )

        val request = UploadFileRequest(
            driveId = drive,
            keyHeader = keyHeader,
            metadata = unencryptedMetadata.encryptContent(keyHeader),
            transitOptions = TransitOptions(
                recipients = recipients,
                sendContents = SendContents.All,
                useAppNotification = !isLocalOnly,
                appNotificationOptions = if (isLocalOnly) null else PushNotificationOptions(
                    appId = MomentsProtocol.MomentsAppId.toString(),
                    typeId = commentUniqueId.toString(),
                    // tagId = momentId so the OS coalesces a noisy comment
                    // thread under one notification group per moment.
                    tagId = momentId.toString(),
                    silent = false,
                    unEncryptedMessage = "New comment",
                ),
            ),
            payloads = encrypted.payloads,
            thumbnails = encrypted.thumbnails,
        )

        val enqueued = outboxSync.tryEnqueue(
            request,
            priority = 1,
            dependencyUniqueId = null,
        )

        if (!enqueued) {
            error("Failed to enqueue comment for upload")
        }

        Logger.d(tag = TAG) { "postComment: outbox enqueued comment=$commentUniqueId" }

        @OptIn(ExperimentalEncodingApi::class)
        val payloadDescriptors = encrypted.payloads.map { payload ->
            PayloadDescriptor(
                key = payload.key,
                contentType = payload.contentType.ifEmpty { null },
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
        }.ifEmpty { null }
        try {
            optimisticWriter.writeNewFile(
                driveId = drive,
                keyHeader = keyHeader,
                unecryptedMetadata = unencryptedMetadata,
                originalRecipientCount = recipients.size,
                fileSystemType = FileSystemType.Standard,
                payloadDescriptors = payloadDescriptors,
            )
            Logger.d(tag = TAG) { "postComment: optimistic write complete comment=$commentUniqueId" }
        } catch (e: Exception) {
            Logger.e(throwable = e, tag = TAG) {
                "postComment: optimistic write failed (non-fatal) comment=$commentUniqueId"
            }
        }

        return PostMomentCommentResult(uniqueId = commentUniqueId)
    }

    /**
     * Edit the body of an existing comment. Recipients are re-resolved from
     * the parent moment (via the comment's `groupId`) so the audience stays
     * consistent with the moment even if it has changed since the comment
     * was posted. Mirrors [updateMoment]: AES key reused, empty manifest,
     * `replaceEnqueue`.
     */
    suspend fun updateComment(
        commentUniqueId: Uuid,
        versionTag: Uuid,
        body: String,
    ): UpdateMomentCommentResult {
        Logger.d(tag = TAG) { "updateComment: starting comment=$commentUniqueId" }

        // Read the existing comment from the local DriveMainIndex rather than
        // via `driveFileProvider.getFileHeaderByUid`. Same blip-resilience
        // argument as `resolveCommentRecipients`: the comment was authored on
        // this device so its row is already local, and reading from the server
        // turns a transient DNS hiccup into a lost edit (the throw beats the
        // outbox enqueue and the optimistic write).
        val credentials = credentialsManager.requireActiveCredentials()
        val existing = dbm.driveMainIndex.selectHomebaseFileByUnique(
            credentials.getIdentityId(), drive, commentUniqueId,
        ) ?: throw IllegalArgumentException("comment not found locally: $commentUniqueId")

        if (existing.fileMetadata.versionTag != versionTag) {
            error("VersionTag mismatch")
        }

        val momentId = existing.fileMetadata.appData.groupId
            ?: throw IllegalStateException("comment $commentUniqueId missing groupId")

        val recipients = resolveCommentRecipients(momentId)

        val keyHeader = KeyHeader(
            iv = ByteArrayUtil.getRndByteArray(16),
            aesKey = existing.keyHeader.aesKey,
        )

        val isLocalOnly = recipients.isEmpty()

        val content = OdinSystemSerializer.serialize(
            MomentCommentContent(
                version = MomentsProtocol.MomentCommentVersionNumberOne,
                body = body,
            )
        )

        val unencryptedMetadata = UploadFileMetadata(
            allowDistribution = !isLocalOnly,
            isEncrypted = true,
            versionTag = versionTag,
            appData = UploadAppFileMetaData(
                uniqueId = commentUniqueId,
                groupId = momentId,
                fileType = MomentsProtocol.MomentCommentFileType,
                userDate = existing.fileMetadata.appData.userDate,
                content = content,
                previewThumbnail = existing.fileMetadata.appData.previewThumbnail,
            ),
        )

        val manifest = UpdateManifest.build(
            payloads = null,
            toDeletePayloads = null,
            thumbnails = null,
            generatePayloadIv = false,
        )

        val request = UpdateFileByUniqueIdRequest(
            driveId = drive,
            uniqueId = commentUniqueId,
            keyHeader = keyHeader,
            instructions = FileUpdateInstructionSet(
                transferIv = ByteArrayUtil.getRndByteArray(16),
                locale = UpdateLocale.Local,
                recipients = recipients,
                manifest = manifest,
                useAppNotification = false,
                appNotificationOptions = null,
            ),
            metadata = unencryptedMetadata.encryptContent(keyHeader),
            payloads = emptyList(),
            thumbnails = emptyList(),
        )

        val enqueued = outboxSync.replaceEnqueue(
            request,
            priority = 1,
            dependencyUniqueId = null,
        )

        if (!enqueued) {
            error("Failed to enqueue comment update")
        }

        Logger.d(tag = TAG) { "updateComment: outbox enqueued comment=$commentUniqueId" }

        try {
            optimisticWriter.writeUpdate(
                driveId = drive,
                keyHeader = keyHeader,
                unecryptedMetadata = unencryptedMetadata,
            )
            Logger.d(tag = TAG) { "updateComment: optimistic write complete comment=$commentUniqueId" }
        } catch (e: Exception) {
            Logger.e(throwable = e, tag = TAG) {
                "updateComment: optimistic write failed (non-fatal) comment=$commentUniqueId"
            }
        }

        return UpdateMomentCommentResult(uniqueId = commentUniqueId)
    }

    /**
     * Resolve who a comment on this moment should be sent to: the moment's
     * full audience (`senderOdinId ∪ content.recipients`) minus the current
     * user. Returns empty when the moment was local-only — comment stays
     * local-only too.
     *
     * Reads the moment header from the local DriveMainIndex rather than via
     * `driveFileProvider.getFileHeaderByUid` (a server GET). The moment is
     * already in the local DB by the time the user can reach the comment
     * composer, and routing this through HTTP turned a transient network
     * blip (e.g. `UnresolvedAddressException` during DNS hiccup) into a
     * lost comment — the throw aborted the function before the outbox
     * enqueue or optimistic write ran, so the user typed text just vanished.
     *
     * Mirrors the same fix `MomentActionService.resolveAudienceLocal`
     * already made for reaction toggles.
     */
    private suspend fun resolveCommentRecipients(momentId: Uuid): List<OdinId> {
        val credentials = credentialsManager.requireActiveCredentials()
        val moment = dbm.driveMainIndex.selectHomebaseFileByUnique(
            credentials.getIdentityId(), drive, momentId,
        ) ?: throw IllegalArgumentException("moment not found locally: $momentId")

        val momentContent = moment.fileMetadata.appData.content?.let { raw ->
            runCatching {
                OdinSystemSerializer.deserialize<MomentPostContent>(raw)
            }.getOrNull()
        } ?: throw IllegalStateException("moment $momentId content unreadable")

        val self = credentials.domain
        val audience = buildSet {
            moment.fileMetadata.senderOdinId?.let { add(it) }
            addAll(momentContent.recipients)
        }
        return (audience - self).toList()
    }
}

data class PostMomentResult(val uniqueId: Uuid)
data class UpdateMomentResult(val uniqueId: Uuid)
data class PostMomentCommentResult(val uniqueId: Uuid)
data class UpdateMomentCommentResult(val uniqueId: Uuid)
