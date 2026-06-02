package id.homebase.core.moments.services

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.files.ThumbnailDescriptor
import id.homebase.api.client.drives.files.ThumbnailFile
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
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.OdinId
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.image.ImageFormat
import id.homebase.api.image.ImageUtils
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.OutboxSync
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.PayloadBundleEncryptor
import id.homebase.chat.services.builder.AttachmentInput
import id.homebase.chat.services.builder.MessageAttachmentBuilder
import id.homebase.chat.services.outbox.OptimisticWriter
import id.homebase.core.config.momentsLabeledDrive
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    private val eventBus: EventBus,
    private val scope: CoroutineScope,
) {

    /**
     * Coil model for the first renderable attachment of an in-flight moment:
     * the local file URI (from the user's picker) for a photo, or the extracted
     * poster-frame JPEG bytes for a video. Populated by [postMomentAsync] right
     * after the placeholder DB write, cleared once the real optimistic write
     * lands (real `previewThumbnail` bytes are then available on `MomentFeedItem`).
     *
     * Videos must hand over poster bytes rather than the raw video path — an
     * image loader can't decode a video file, so a video path would render the
     * tile black for the whole "Preparing…" window.
     *
     * The feed VM mirrors this into `MomentsFeedUiState.pendingLocalPreviews`
     * so the tile can show the user's source media during the brief
     * "Preparing…" window before thumbnails are generated.
     */
    private val _pendingLocalPreviews = MutableStateFlow<PersistentMap<Uuid, Any>>(persistentMapOf())
    val pendingLocalPreviews: StateFlow<PersistentMap<Uuid, Any>> = _pendingLocalPreviews.asStateFlow()
    companion object {
        private const val TAG = "MomentsPostSenderService"

        /**
         * Embedded video-poster thumbnail sizing for the optimistic row. The raw
         * poster is a full-resolution frame; resize it to this cover size so the
         * inline tile has a crisp-enough still to paint over the player during
         * warm-up without bloating the local descriptor.
         */
        private const val PosterThumbnailMaxDimension = 640
        private const val PosterThumbnailJpegQuality = 75
    }

    private val drive = momentsLabeledDrive.drive.alias

    /**
     * Two-phase post that returns as soon as the local placeholder row exists
     * in the DB. Sequence:
     *
     *   1. SYNCHRONOUS (suspend, fast): generate the moment's uniqueId + key
     *      header, write a placeholder DriveMainIndex row carrying the
     *      description / recipients / source (and no payloads), stash the first
     *      attachment's local URI in `pendingLocalPreviews`, and emit
     *      `PayloadBundlingEvent.Preparing`. The feed picks this up via the
     *      `BatchReceived` the optimistic writer already emits — the tile
     *      appears immediately with a "Preparing…" overlay.
     *
     *   2. BACKGROUND (on the service's singleton scope): build thumbnails,
     *      encrypt the payload bundle, enqueue the upload, and `writeUpdate`
     *      the placeholder row with the real payload descriptors and preview
     *      thumbnail. The pending-preview cache entry is cleared once the
     *      real row lands so the tile transitions to the proper thumbnail
     *      without flicker.
     *
     *  Returns immediately after phase 1 — the dialog can dismiss now.
     */
    suspend fun postMomentAsync(
        attachments: List<AttachmentInput>,
        description: String,
        recipients: List<OdinId>,
        momentUniqueId: Uuid = Uuid.random(),
        userDate: UnixTimeUtc? = null,
        source: MomentSource? = null,
        mediaInfoByAttachment: List<MediaInfo?>? = null,
        commentsEnabled: Boolean = true,
        // Aligned-by-index with [attachments]: the extracted poster-frame JPEG
        // for each video attachment (null for non-video / extraction failures).
        // Used both for the immediate local preview and as the optimistic row's
        // embedded video thumbnail, so the tile never renders black.
        posterByAttachment: List<ByteArray?>? = null,
    ): PostMomentResult {
        Logger.d(tag = TAG) {
            "postMomentAsync: enqueueing moment=$momentUniqueId attachments=${attachments.size} recipients=${recipients.size} source=$source"
        }

        val keyHeader = KeyHeader.newRandom16()
        val effectiveUserDate = userDate ?: UnixTimeUtc.now()
        val isLocalOnly = recipients.isEmpty()

        // Placeholder content carries only what we know without doing any
        // build/encrypt work: description, audience, comments policy. The real
        // optimistic write later replaces this with the same content plus the
        // mediaInfo keys generated during encryption.
        val placeholderContent = OdinSystemSerializer.serialize(
            MomentPostContent(
                version = MomentsProtocol.MomentPostVersionNumberOne,
                description = description,
                recipients = recipients,
                source = source,
                mediaInfo = null,
                commentsEnabled = commentsEnabled,
            )
        )

        val tags: List<Uuid>? = when (source) {
            is MomentSource.Conversation -> listOf(source.conversationId)
            is MomentSource.Audience -> source.groupIds.ifEmpty { null }
            null -> null
        }

        val placeholderMetadata = UploadFileMetadata(
            allowDistribution = !isLocalOnly,
            isEncrypted = true,
            appData = UploadAppFileMetaData(
                uniqueId = momentUniqueId,
                tags = tags,
                fileType = MomentsProtocol.MomentPostFileType,
                userDate = effectiveUserDate.milliseconds,
                content = placeholderContent,
                previewThumbnail = null,
            ),
        )

        // Cache the first renderable attachment's preview model BEFORE writing
        // the placeholder. The optimistic writer immediately emits
        // BatchReceived, so the feed VM resolves the tile in the same dispatch
        // loop — by the time it asks `pendingLocalPreviews[id]`, the entry must
        // already be there. For a video we hand over the poster JPEG bytes
        // (an image loader can't decode a raw video path); for a photo, the
        // local file path.
        val firstRenderableIndex = attachments.indexOfFirst { input ->
            input.contentType.startsWith("image/") || input.contentType.startsWith("video/")
        }
        if (firstRenderableIndex >= 0) {
            val poster = posterByAttachment?.getOrNull(firstRenderableIndex)
            val previewModel: Any = poster ?: attachments[firstRenderableIndex].filePath
            _pendingLocalPreviews.update { it.put(momentUniqueId, previewModel) }
        }

        try {
            optimisticWriter.writeNewFile(
                driveId = drive,
                keyHeader = keyHeader,
                unecryptedMetadata = placeholderMetadata,
                originalRecipientCount = recipients.size,
                fileSystemType = FileSystemType.Standard,
                payloadDescriptors = null,
            )
        } catch (e: Exception) {
            // Without a placeholder row the feed has nothing to attach the
            // overlay to. Fail loudly so the dialog can surface the error
            // instead of pretending the post succeeded.
            _pendingLocalPreviews.update { it.remove(momentUniqueId) }
            Logger.e(throwable = e, tag = TAG) {
                "postMomentAsync: placeholder write failed moment=$momentUniqueId"
            }
            throw e
        }

        eventBus.emit(BackendEvent.PayloadBundlingEvent.Preparing(uniqueId = momentUniqueId))

        // Heavy work — build/encrypt/enqueue/finalize — runs on the service's
        // singleton scope so it survives the dialog's ViewModel being cleared.
        scope.launch {
            finalizeMomentSend(
                momentUniqueId = momentUniqueId,
                attachments = attachments,
                description = description,
                recipients = recipients,
                userDate = effectiveUserDate,
                source = source,
                mediaInfoByAttachment = mediaInfoByAttachment,
                commentsEnabled = commentsEnabled,
                keyHeader = keyHeader,
                tags = tags,
                isLocalOnly = isLocalOnly,
                posterByAttachment = posterByAttachment,
            )
        }

        return PostMomentResult(uniqueId = momentUniqueId)
    }

    /**
     * Background half of [postMomentAsync]: thumbnail generation + encryption +
     * outbox enqueue + final optimistic update. Failures here leave the
     * placeholder row in the feed marked with `isPendingSendTag`; the user
     * sees a Failed overlay via the outbox events / dropped fallback.
     */
    private suspend fun finalizeMomentSend(
        momentUniqueId: Uuid,
        attachments: List<AttachmentInput>,
        description: String,
        recipients: List<OdinId>,
        userDate: UnixTimeUtc,
        source: MomentSource?,
        mediaInfoByAttachment: List<MediaInfo?>?,
        commentsEnabled: Boolean,
        keyHeader: KeyHeader,
        tags: List<Uuid>?,
        isLocalOnly: Boolean,
        posterByAttachment: List<ByteArray?>? = null,
    ) {
        // Once the outbox has accepted the request its event stream drives the
        // tile's status (Sending → Uploading → Completed/Failed). Before that,
        // any failure here means the moment never went anywhere — roll the
        // placeholder back so the user doesn't see a tile stuck in Preparing.
        var enqueued = false
        try {
            // Server constraint: payload keys must match ^[a-z0-9_]{8,10}$.
            val keyForIndex: (Int) -> String = { i -> "mmt_${i.toString().padStart(4, '0')}" }

            // Downscaled video posters keyed by the payload key the builder
            // will use, so we can graft them onto the video payload descriptors
            // below. The attachment builder produces no thumbnail for videos
            // (see MessageAttachmentBuilder's `else` branch), so without this
            // the optimistic video row has no embedded preview and the inline
            // tile renders the bare player surface — black — during decoder
            // warm-up. The raw poster is full-resolution (MediaMetadataRetriever
            // returns the whole frame), so resize it to an embedded-thumbnail
            // size before base64-ing it into the local descriptor — otherwise a
            // multi-hundred-KB string would ride on every video row (and never
            // get reclaimed for local-only "Save privately" moments, which have
            // no server resync to replace the optimistic row).
            @OptIn(ExperimentalEncodingApi::class)
            val posterThumbByKey: Map<String, ThumbnailDescriptor> = buildMap {
                posterByAttachment?.forEachIndexed { i, poster ->
                    if (poster == null) return@forEachIndexed
                    val thumb = runCatching {
                        val resized = ImageUtils.resizePreserveAspect(
                            srcBytes = poster,
                            maxWidth = PosterThumbnailMaxDimension,
                            maxHeight = PosterThumbnailMaxDimension,
                            outputFormat = ImageFormat.JPEG,
                            quality = PosterThumbnailJpegQuality,
                        )
                        ThumbnailDescriptor(
                            pixelWidth = resized.size.pixelWidth,
                            pixelHeight = resized.size.pixelHeight,
                            contentType = "image/jpeg",
                            content = Base64.encode(resized.bytes),
                        )
                    }.getOrNull()
                    if (thumb != null) put(keyForIndex(i), thumb)
                }
            }
            val bundle = MessageAttachmentBuilder.build(
                attachments = attachments,
                fileOperationsProvider = fileOps,
            ) { index, _ -> keyForIndex(index) }

            val keyedMediaInfo: Map<String, MediaInfo>? = mediaInfoByAttachment
                ?.mapIndexedNotNull { i, mi -> mi?.let { keyForIndex(i) to it } }
                ?.toMap()
                ?.takeIf { it.isNotEmpty() }

            val encrypted = payloadBundleEncryptor.encryptBundle(
                uniqueId = momentUniqueId,
                bundle = bundle,
                aesKey = keyHeader.aesKey,
                scope = scope,
            )

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

            val unencryptedMetadata = UploadFileMetadata(
                allowDistribution = !isLocalOnly,
                isEncrypted = true,
                appData = UploadAppFileMetaData(
                    uniqueId = momentUniqueId,
                    tags = tags,
                    fileType = MomentsProtocol.MomentPostFileType,
                    userDate = userDate.milliseconds,
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
                        appId = ChatProtocol.ChatAppId.toString(), // MomentsProtocol.MomentsAppId.toString(),
                        typeId = momentUniqueId.toString(),
                        tagId = momentUniqueId.toString(),
                        silent = false,
                        unEncryptedMessage = "New moment posted",
                    ),
                ),
                payloads = encrypted.payloads,
                thumbnails = encrypted.thumbnails,
            )

            enqueued = outboxSync.tryEnqueue(
                request,
                priority = 1,
                dependencyUniqueId = null,
            )

            if (!enqueued) {
                error("Failed to enqueue moment for upload")
            }

            Logger.d(tag = TAG) { "finalizeMomentSend: outbox enqueued moment=$momentUniqueId" }

            @OptIn(ExperimentalEncodingApi::class)
            val payloadDescriptors = encrypted.payloads.map { payload ->
                // Prefer the builder's own preview (photos/audio); fall back to
                // the extracted video poster so the optimistic video tile has a
                // still to show over the player surface during warm-up. This
                // descriptor feeds the local `writeUpdate` only — it isn't part
                // of the uploaded payload bundle, so there's no header-budget
                // concern with the full-frame poster bytes.
                val previewThumb = payload.previewThumbnail?.let {
                    ThumbnailDescriptor(
                        pixelWidth = it.pixelWidth,
                        pixelHeight = it.pixelHeight,
                        contentType = it.contentType,
                        content = it.content,
                    )
                } ?: posterThumbByKey[payload.key]
                PayloadDescriptor(
                    key = payload.key,
                    contentType = payload.contentType.ifEmpty { null },
                    iv = payload.iv?.let { Base64.encode(it) },
                    descriptorContent = payload.descriptorContent,
                    previewThumbnail = previewThumb,
                )
            }.ifEmpty { null }

            try {
                optimisticWriter.writeUpdate(
                    driveId = drive,
                    keyHeader = keyHeader,
                    unecryptedMetadata = unencryptedMetadata,
                    payloadDescriptors = payloadDescriptors,
                )
                Logger.d(tag = TAG) { "finalizeMomentSend: optimistic update complete moment=$momentUniqueId" }
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = TAG) {
                    "finalizeMomentSend: optimistic update failed (non-fatal) moment=$momentUniqueId"
                }
            }
        } catch (e: Throwable) {
            if (!enqueued) {
                Logger.e(throwable = e, tag = TAG) {
                    "finalizeMomentSend: failed before enqueue moment=$momentUniqueId — rolling back placeholder"
                }
                runCatching {
                    optimisticWriter.removeOptimisticFile(drive, momentUniqueId)
                }.onFailure { rb ->
                    Logger.e(throwable = rb, tag = TAG) {
                        "finalizeMomentSend: rollback also failed moment=$momentUniqueId"
                    }
                }
            } else {
                // Outbox already owns the lifecycle (retry → OutboxItemDropped
                // → OptimisticRollback flow handles it). Just log so we know
                // the post-enqueue finalize update never landed.
                Logger.e(throwable = e, tag = TAG) {
                    "finalizeMomentSend: post-enqueue work failed moment=$momentUniqueId — outbox owns retry"
                }
            }
        } finally {
            // Real thumbnail (if any) is now on the row, so the transient
            // local-file preview is no longer needed. Always clear, even on
            // error paths — keeping the entry would pin a file URI the user
            // may have just deleted from their picker.
            _pendingLocalPreviews.update { it.remove(momentUniqueId) }
        }
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
     * Widen an already-posted moment's audience — "I forgot to include Bob and
     * Suzy." The media and description are untouched; we only add recipients.
     *
     * An empty-manifest update ships the file HEADER only, so a brand-new
     * recipient who never received this moment would land a header that
     * references payloads they can't fetch. We therefore re-attach the moment's
     * existing encrypted payloads (downloaded from our own drive, keyed by their
     * original IV so they decrypt with the unchanged file key) into the
     * manifest — the same heal-redistribute trick
     * `ConversationService.reuseExistingPayloadsForResend` uses for chat group
     * images.
     *
     * Distribution targets ONLY the genuinely-new recipients: existing
     * recipients already hold the media, so they are intentionally neither
     * re-shipped to nor re-notified. The persisted [MomentPostContent.recipients]
     * is widened to the full merged audience so the author's own copy reflects
     * everyone; existing recipients keep their prior copy (their stored audience
     * goes stale, which is acceptable while the "Shared with" recipient UI is
     * not rendered).
     *
     * No app notification is sent: the update-side notification plumbing
     * ([AppNotificationOptions] on [FileUpdateInstructionSet]) is unexercised
     * everywhere else in the app, so we don't send into that untested path. The
     * moment still lands in the new recipients' feeds via the normal sync
     * (`BatchReceived`) path — just without an OS push banner.
     *
     * Mirrors [updateMoment]: AES key reused (fresh IV), `replaceEnqueue`.
     */
    suspend fun addRecipientsToMoment(
        momentUniqueId: Uuid,
        versionTag: Uuid,
        newRecipients: List<OdinId>,
    ): UpdateMomentResult {
        Logger.d(tag = TAG) {
            "addRecipientsToMoment: starting moment=$momentUniqueId newRecipients=${newRecipients.size}"
        }

        val existing = driveFileProvider.getFileHeaderByUid(drive, momentUniqueId)
            ?: throw IllegalArgumentException("moment not found: $momentUniqueId")

        if (existing.fileMetadata.versionTag != versionTag) {
            error("VersionTag mismatch")
        }

        val self = credentialsManager.requireActiveCredentials().domain

        val existingContent = existing.fileMetadata.appData.content?.let { raw ->
            runCatching {
                OdinSystemSerializer.deserialize<MomentPostContent>(raw)
            }.getOrNull()
        } ?: throw IllegalStateException("moment $momentUniqueId content unreadable")

        val existingRecipients = existingContent.recipients
        // Only identities not already on the moment (and never ourselves) need
        // a copy. Anyone already in the audience is left untouched.
        val genuinelyNew = newRecipients
            .filterNot { it == self || existingRecipients.contains(it) }
            .distinct()

        if (genuinelyNew.isEmpty()) {
            Logger.d(tag = TAG) {
                "addRecipientsToMoment: no new recipients moment=$momentUniqueId — nothing to do"
            }
            return UpdateMomentResult(uniqueId = momentUniqueId)
        }

        val mergedRecipients = (existingRecipients + genuinelyNew).distinct()

        val keyHeader = KeyHeader(
            iv = ByteArrayUtil.getRndByteArray(16),
            aesKey = existing.keyHeader.aesKey,
        )

        // Re-attach the moment's existing media so the brand-new recipients
        // actually receive it (an empty manifest would ship header only).
        val (reusedPayloads, reusedThumbs) = reuseExistingPayloadsForResend(existing)

        val manifest = UpdateManifest.build(
            payloads = reusedPayloads.takeIf { it.isNotEmpty() },
            toDeletePayloads = null,
            thumbnails = reusedThumbs.takeIf { it.isNotEmpty() },
            generatePayloadIv = false,
        )

        val content = OdinSystemSerializer.serialize(
            MomentPostContent(
                version = MomentsProtocol.MomentPostVersionNumberOne,
                description = existingContent.description,
                recipients = mergedRecipients,
                source = existingContent.source,
                mediaInfo = existingContent.mediaInfo,
                commentsEnabled = existingContent.commentsEnabled,
            )
        )

        val unencryptedMetadata = UploadFileMetadata(
            allowDistribution = true,
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

        val request = UpdateFileByUniqueIdRequest(
            driveId = drive,
            uniqueId = momentUniqueId,
            keyHeader = keyHeader,
            instructions = FileUpdateInstructionSet(
                transferIv = ByteArrayUtil.getRndByteArray(16),
                locale = UpdateLocale.Local,
                recipients = genuinelyNew,
                manifest = manifest,
                useAppNotification = false,
                appNotificationOptions = null,
            ),
            metadata = unencryptedMetadata.encryptContent(keyHeader),
            payloads = reusedPayloads,
            thumbnails = reusedThumbs,
        )

        val enqueued = outboxSync.replaceEnqueue(
            request,
            priority = 1,
            dependencyUniqueId = null,
        )

        if (!enqueued) {
            error("Failed to enqueue moment recipient update")
        }

        Logger.d(tag = TAG) {
            "addRecipientsToMoment: outbox enqueued moment=$momentUniqueId added=${genuinelyNew.size}"
        }

        // Best-effort optimistic write so the author's detail screen reflects
        // the widened audience immediately. Media payloads are preserved.
        try {
            optimisticWriter.writeUpdate(
                driveId = drive,
                keyHeader = keyHeader,
                unecryptedMetadata = unencryptedMetadata,
            )
            Logger.d(tag = TAG) {
                "addRecipientsToMoment: optimistic write complete moment=$momentUniqueId"
            }
        } catch (e: Exception) {
            Logger.e(throwable = e, tag = TAG) {
                "addRecipientsToMoment: optimistic write failed (non-fatal) moment=$momentUniqueId"
            }
        }

        return UpdateMomentResult(uniqueId = momentUniqueId)
    }

    /**
     * Download the moment's existing encrypted payload (and thumbnail) bytes
     * from our own drive and re-package them as pre-encrypted [PayloadFile] /
     * [ThumbnailFile] entries keyed by their original IV, so an update can ship
     * them to brand-new recipients who decrypt with the unchanged file key.
     *
     * Mirrors `ConversationService.reuseExistingPayloadsForResend`. Returns
     * empty lists when the moment has no payloads or a fetch fails — the caller
     * falls back to a header-only update for those keys.
     */
    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun reuseExistingPayloadsForResend(
        file: HomebaseFile,
    ): Pair<List<PayloadFile>, List<ThumbnailFile>> {
        val existing = file.fileMetadata.payloads.orEmpty()
        if (existing.isEmpty()) {
            Logger.d(tag = TAG) {
                "reuseExistingPayloadsForResend: fileId=${file.fileId} has no payloads — nothing to re-attach"
            }
            return emptyList<PayloadFile>() to emptyList()
        }

        val payloads = mutableListOf<PayloadFile>()
        val thumbnails = mutableListOf<ThumbnailFile>()
        for (descriptor in existing) {
            try {
                val bytes = driveFileProvider.getPayloadBytesEncrypted(drive, file.fileId, descriptor.key)
                if (bytes == null || bytes.isEmpty()) {
                    Logger.w(tag = TAG) {
                        "reuseExistingPayloadsForResend: skipping payload key=${descriptor.key} — bytes ${if (bytes == null) "null" else "empty"} (fileId=${file.fileId})"
                    }
                    continue
                }
                val ivBytes = descriptor.iv?.let { Base64.decode(it) }
                if (ivBytes == null) {
                    Logger.w(tag = TAG) {
                        "reuseExistingPayloadsForResend: skipping payload key=${descriptor.key} — descriptor has no iv (fileId=${file.fileId})"
                    }
                    continue
                }
                val tempPath = fileOps.writeBytesToTempFile(
                    bytes = bytes,
                    prefix = "mmt_resend_${descriptor.key}_",
                    suffix = ".enc",
                )
                payloads += PayloadFile(
                    key = descriptor.key,
                    filePath = tempPath,
                    previewThumbnail = descriptor.previewThumbnail?.toEmbeddedThumb(),
                    contentType = descriptor.contentType ?: "",
                    isPreEncrypted = true,
                    iv = ivBytes,
                    descriptorContent = descriptor.descriptorContent,
                )

                // Re-attach the payload's thumbnails: the AppendOrOverwrite
                // REPLACES the payload AND its thumbnail set on each peer, so
                // omitting them would wipe the server-side thumbnails. They're
                // encrypted with the same payload key + iv, so ship as-is.
                for (thumb in descriptor.thumbnails.orEmpty()) {
                    val w = thumb.pixelWidth
                    val h = thumb.pixelHeight
                    if (w == null || h == null) {
                        Logger.w(tag = TAG) {
                            "reuseExistingPayloadsForResend: skipping thumb for key=${descriptor.key} — missing dims (fileId=${file.fileId})"
                        }
                        continue
                    }
                    val thumbBytes = driveFileProvider.getThumbBytesEncrypted(
                        driveId = drive,
                        fileId = file.fileId,
                        payloadKey = descriptor.key,
                        width = w,
                        height = h,
                        lastModified = descriptor.lastModified,
                    )
                    if (thumbBytes == null || thumbBytes.isEmpty()) {
                        Logger.w(tag = TAG) {
                            "reuseExistingPayloadsForResend: skipping thumb key=${descriptor.key} ${w}x$h — bytes ${if (thumbBytes == null) "null" else "empty"} (fileId=${file.fileId})"
                        }
                        continue
                    }
                    thumbnails += ThumbnailFile(
                        pixelWidth = w,
                        pixelHeight = h,
                        thumbnailBytes = thumbBytes,
                        key = descriptor.key,
                        contentType = thumb.contentType ?: "image/webp",
                    )
                }
            } catch (e: Exception) {
                Logger.w(throwable = e, tag = TAG) {
                    "reuseExistingPayloadsForResend: failed to re-attach payload key=${descriptor.key} fileId=${file.fileId}"
                }
            }
        }
        return payloads to thumbnails
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
                    appId = ChatProtocol.ChatAppId.toString(), // MomentsProtocol.MomentsAppId.toString(),
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
