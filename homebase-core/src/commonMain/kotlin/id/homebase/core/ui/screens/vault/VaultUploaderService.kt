@file:OptIn(ExperimentalUuidApi::class, ExperimentalEncodingApi::class)

package id.homebase.core.ui.screens.vault

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.files.ThumbnailDescriptor
import id.homebase.api.client.drives.files.ThumbnailFile
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.client.drives.upload.UpdateManifest
import id.homebase.api.client.drives.upload.PayloadDeleteKey
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.withResolvedFiles
import id.homebase.api.image.convertHeicToJpeg
import id.homebase.api.sync.database.OutboxSync
import id.homebase.chat.services.LocalAttachmentContextStore
import id.homebase.chat.services.PayloadBundle
import id.homebase.chat.services.PayloadBundleEncryptionService
import id.homebase.chat.services.builder.MessageThumbnailGenerator
import id.homebase.chat.services.outbox.OptimisticWriter
import id.homebase.core.config.vaultLabeledDrive
import id.homebase.core.ui.screens.vault.model.VaultEntry
import id.homebase.core.ui.screens.vault.model.VaultFileContent
import id.homebase.core.ui.screens.vault.model.VAULT_FILE_TYPE
import id.homebase.core.util.CONTENT_TYPE_MARKDOWN
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.image.ImageUtils
import id.homebase.api.image.createImageThumbnail
import id.homebase.api.image.tinyThumbSize
import id.homebase.core.pdf.generatePdfThumbnailFromFile
import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val TAG = "VaultUploaderService"
private fun vaultPayloadKey(index: Int): String = "vlt_pg_${index.toString().padStart(2, '0')}"

class VaultUploaderService(
    private val outboxSync: OutboxSync,
    private val optimisticWriter: OptimisticWriter,
    private val payloadEncryptionService: PayloadBundleEncryptionService,
    private val fileOperationsProvider: FileOperationsProvider,
    private val driveFileProvider: DriveFileProvider,
    private val localAttachmentStore: LocalAttachmentContextStore,
    private val vaultService: VaultService,
) {
    private val driveId = vaultLabeledDrive.drive.alias

    suspend fun uploadFile(
        entryName: String,
        files: List<Pair<String, String>>,
        scope: CoroutineScope,
        groupId: Uuid? = null,
        notes: String? = null,
        notePreview: String? = null,
        uniqueId: Uuid = Uuid.random(),
    ): Uuid? =
    // Resolve the picked files (Android copies content:// picks into cacheDir
    // as resolved_*) and reap those copies once the upload is enqueued — the
    // shared resolve-then-delete scope, so this path can't strand them. See
        // FileOperationsProvider.withResolvedFiles.
        fileOperationsProvider.withResolvedFiles(files.map { it.first }) { resolvedPaths ->
            uploadResolvedFiles(
                entryName = entryName,
                files = resolvedPaths.mapIndexed { i, resolved -> resolved to files[i].second },
                scope = scope,
                groupId = groupId,
                notes = notes,
                notePreview = notePreview,
                uniqueId = uniqueId,
            )
        }

    private suspend fun uploadResolvedFiles(
        entryName: String,
        files: List<Pair<String, String>>,
        scope: CoroutineScope,
        groupId: Uuid?,
        notes: String?,
        notePreview: String?,
        uniqueId: Uuid,
    ): Uuid? {
        // heic_converted_*.jpg temps created by convertHeicIfNeeded outlive that
        // call but are fully consumed once encryptBundle has read them into the
        // encrypted payloads. Reap them in finally so they don't linger in
        // cacheDir — same per-send discipline as the resolved copies.
        val heicTemps = mutableListOf<String>()
        return try {
            val keyHeader = KeyHeader.newRandom16()

            val resolvedFiles = files.map { (path, contentType) ->
                val converted = convertHeicIfNeeded(path, contentType)
                if (converted.first != path) heicTemps += converted.first
                converted
            }

            var pdfPageCount: Int? = null
            val bundle = buildMultiPayloadBundle(resolvedFiles, { pdfPageCount = it }, notePreview)

            val encryptedBundle = payloadEncryptionService.encryptBundle(
                uniqueId, bundle, keyHeader.aesKey, scope
            )

            val content = OdinSystemSerializer.serialize(
                VaultFileContent(name = entryName, notes = notes, pdfPageCount = pdfPageCount)
            )
            val unencryptedMetadata = UploadFileMetadata(
                allowDistribution = false,
                isEncrypted = true,
                appData = UploadAppFileMetaData(
                    uniqueId = uniqueId,
                    content = content,
                    fileType = VAULT_FILE_TYPE,
                    groupId = groupId,
                    previewThumbnail = encryptedBundle.previewThumbs.firstOrNull(),
                ),
            )

            val payloadDescriptors = encryptedBundle.payloads.map { payload ->
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

            val request = UploadFileRequest(
                driveId = driveId,
                keyHeader = keyHeader,
                metadata = unencryptedMetadata.encryptContent(keyHeader),
                payloads = encryptedBundle.payloads,
                thumbnails = encryptedBundle.thumbnails,
            )

            val enqueued = outboxSync.tryEnqueue(request)
            if (enqueued) {
                try {
                    optimisticWriter.writeNewFile(
                        driveId = driveId,
                        keyHeader = keyHeader,
                        unecryptedMetadata = unencryptedMetadata,
                        originalRecipientCount = 0,
                        fileSystemType = FileSystemType.Standard,
                        payloadDescriptors = payloadDescriptors,
                    )
                    Logger.d(tag = TAG) { "Optimistic write complete: $entryName uniqueId=$uniqueId" }
                } catch (e: Exception) {
                    Logger.e(e, TAG) { "Optimistic write failed (non-fatal): $entryName" }
                }
                uniqueId
            } else {
                null
            }
        } catch (e: Exception) {
            Logger.e(e, TAG) { "Failed to enqueue multi-payload upload: $entryName" }
            null
        } finally {
            for (temp in heicTemps) fileOperationsProvider.deleteTempFile(temp)
        }
    }

    suspend fun appendPages(
        file: VaultEntry,
        newFiles: List<Pair<String, String>>,
        scope: CoroutineScope,
    ): Boolean =
        fileOperationsProvider.withResolvedFiles(newFiles.map { it.first }) { resolvedPaths ->
            appendResolvedPages(
                file = file,
                newFiles = resolvedPaths.mapIndexed { i, resolved -> resolved to newFiles[i].second },
                scope = scope,
            )
        }

    private suspend fun appendResolvedPages(
        file: VaultEntry,
        newFiles: List<Pair<String, String>>,
        scope: CoroutineScope,
    ): Boolean {
        // See uploadResolvedFiles: reap the heic_converted_*.jpg temps once the
        // bundle has been encrypted, so they don't linger in cacheDir.
        val heicTemps = mutableListOf<String>()
        return try {
            val existingMaxIndex =
                file.payloadDescriptors.mapNotNull { it.key.removePrefix("vlt_pg_").toIntOrNull() }
                    .maxOrNull() ?: -1
            val startIndex = existingMaxIndex + 1

            val resolvedFiles = newFiles.map { (path, contentType) ->
                val converted = convertHeicIfNeeded(path, contentType)
                if (converted.first != path) heicTemps += converted.first
                converted
            }

            val allPayloads = mutableListOf<PayloadFile>()
            val allThumbnails = mutableListOf<ThumbnailFile>()

            resolvedFiles.forEachIndexed { i, (filePath, contentType) ->
                val key = vaultPayloadKey(startIndex + i)
                var previewThumbnail: EmbeddedThumb? = null
                var thumbnails = emptyList<ThumbnailFile>()

                if (contentType.startsWith("image/")) {
                    try {
                        val result = MessageThumbnailGenerator.generate(
                            filePath, key, fileOperationsProvider,
                        )
                        previewThumbnail = result.preview
                        thumbnails = result.thumbnails
                    } catch (e: Exception) {
                        Logger.w(e, TAG) { "Thumbnail generation failed for append payload $key" }
                    }
                }

                allPayloads += PayloadFile(
                    key = key,
                    filePath = filePath,
                    contentType = contentType,
                    previewThumbnail = previewThumbnail,
                )
                allThumbnails += thumbnails
            }

            val keyHeader = KeyHeader(
                iv = ByteArrayUtil.getRndByteArray(16), aesKey = file.keyHeader.aesKey
            )
            val encryptedBundle = payloadEncryptionService.encryptBundle(
                Uuid.random(), PayloadBundle(allPayloads, allThumbnails, emptyList()),
                keyHeader.aesKey, scope,
            )

            vaultService.enqueueFileContentUpdate(
                uniqueId = file.uniqueId,
                fileContent = VaultFileContent(
                    name = file.fileName,
                    label = file.label,
                    notes = file.notes
                ),
                groupId = file.groupId,
                versionTag = file.versionTag,
                keyHeader = file.keyHeader,
                manifest = UpdateManifest.build(
                    payloads = encryptedBundle.payloads,
                    thumbnails = encryptedBundle.thumbnails,
                    generatePayloadIv = false,
                ),
                payloads = encryptedBundle.payloads,
                thumbnails = encryptedBundle.thumbnails,
            )
        } catch (e: Exception) {
            Logger.e(e, TAG) { "Failed to enqueue append pages to ${file.uniqueId}" }
            false
        } finally {
            for (temp in heicTemps) fileOperationsProvider.deleteTempFile(temp)
        }
    }

    suspend fun deletePage(
        file: VaultEntry,
        payloadKey: String,
    ): Boolean {
        return try {
            val isLastPage = file.payloadDescriptors.size <= 1
            if (isLastPage) {
                return vaultService.deleteEntry(file.uniqueId, file.fileId)
            }

            vaultService.enqueueFileContentUpdate(
                uniqueId = file.uniqueId,
                fileContent = VaultFileContent(
                    name = file.fileName,
                    label = file.label,
                    notes = file.notes
                ),
                groupId = file.groupId,
                versionTag = file.versionTag,
                keyHeader = file.keyHeader,
                manifest = UpdateManifest.build(
                    toDeletePayloads = listOf(PayloadDeleteKey(payloadKey)),
                ),
            )
        } catch (e: Exception) {
            Logger.e(e, TAG) { "Failed to enqueue delete page $payloadKey from ${file.uniqueId}" }
            false
        }
    }

    suspend fun updateNotePayload(
        file: VaultEntry,
        newName: String,
        markdown: String,
        notePreview: String?,
        scope: CoroutineScope,
    ): Boolean {
        val tempPath = fileOperationsProvider.writeBytesToTempFile(
            markdown.encodeToByteArray(), "vault_note_", ".md"
        )
        return try {
            val existingKey = file.payloadDescriptors.firstOrNull()?.key
                ?: VaultEntry.DEFAULT_PAYLOAD_KEY

            val payload = PayloadFile(
                key = existingKey,
                filePath = tempPath,
                contentType = CONTENT_TYPE_MARKDOWN,
                descriptorContent = notePreview,
            )

            val keyHeader = KeyHeader(
                iv = ByteArrayUtil.getRndByteArray(16),
                aesKey = file.keyHeader.aesKey,
            )

            val encryptedBundle = payloadEncryptionService.encryptBundle(
                Uuid.random(),
                PayloadBundle(listOf(payload), emptyList(), emptyList()),
                keyHeader.aesKey,
                scope,
            )

            vaultService.enqueueFileContentUpdate(
                uniqueId = file.uniqueId,
                fileContent = VaultFileContent(
                    name = newName,
                    label = file.label,
                    notes = file.notes,
                ),
                groupId = file.groupId,
                versionTag = file.versionTag,
                keyHeader = file.keyHeader,
                manifest = UpdateManifest.build(
                    payloads = encryptedBundle.payloads,
                    generatePayloadIv = false,
                ),
                payloads = encryptedBundle.payloads,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(e, TAG) { "Failed to update note payload for ${file.uniqueId}" }
            false
        } finally {
            try {
                fileOperationsProvider.deleteTempFile(tempPath)
            } catch (_: Exception) {
            }
        }
    }

    suspend fun downloadPayload(file: VaultEntry, payloadKey: String): String? {
        return try {
            val payloadDescriptor = file.payloadDescriptors.find { it.key == payloadKey }
            val iv = payloadDescriptor?.iv
            val keyHeader = if (iv != null) {
                try {
                    KeyHeader(Base64.decode(iv), file.keyHeader.aesKey)
                } catch (_: Exception) {
                    file.keyHeader
                }
            } else {
                file.keyHeader
            }

            val bytes = driveFileProvider.getPayloadBytesDecrypted(
                driveId = file.driveId,
                fileId = file.fileId,
                key = payloadKey,
                keyHeader = keyHeader,
            )?.bytes ?: return null

            val ct = payloadDescriptor?.contentType ?: file.contentType
            val extension = ct.substringAfter("/", "bin").let {
                if (it == "jpeg") "jpg" else it
            }
            fileOperationsProvider.writeBytesToTempFile(bytes, "share_", ".$extension")
        } catch (e: Exception) {
            Logger.e(e, TAG) { "Failed to download payload $payloadKey from ${file.fileId}" }
            null
        }
    }

    private suspend fun buildMultiPayloadBundle(
        files: List<Pair<String, String>>,
        onPdfPageCount: ((Int) -> Unit)? = null,
        notePreview: String? = null,
    ): PayloadBundle {
        val allPayloads = mutableListOf<PayloadFile>()
        val allThumbnails = mutableListOf<ThumbnailFile>()
        val allPreviews = mutableListOf<EmbeddedThumb>()

        files.forEachIndexed { index, (filePath, contentType) ->
            val key = vaultPayloadKey(index)
            var previewThumbnail: EmbeddedThumb? = null
            var thumbnails = emptyList<ThumbnailFile>()

            if (contentType.startsWith("image/")) {
                try {
                    val result = MessageThumbnailGenerator.generate(
                        filePath, key, fileOperationsProvider,
                    )
                    previewThumbnail = result.preview
                    thumbnails = result.thumbnails
                } catch (e: Exception) {
                    Logger.w(e, TAG) { "Thumbnail generation failed for payload $key" }
                }
            } else if (contentType == "application/pdf") {
                try {
                    val pdfResult = generatePdfThumbnailFromFile(filePath, 320)
                    val thumbBytes = pdfResult?.thumbnailBytes
                    if (thumbBytes != null) {
                        // Tiny embedded preview for appData (must fit under MaxEmbeddedThumbBytes)
                        val tinyThumb = createImageThumbnail(
                            thumbBytes, key, tinyThumbSize, isTinyThumb = true,
                        )
                        previewThumbnail = EmbeddedThumb(
                            pixelWidth = tinyThumb.pixelWidth,
                            pixelHeight = tinyThumb.pixelHeight,
                            contentType = tinyThumb.contentType,
                            content = Base64.encode(tinyThumb.thumbnailBytes),
                        )
                        // Larger thumbnail for crisp card display
                        val naturalSize = ImageUtils.getNaturalSize(thumbBytes)
                        thumbnails = listOf(
                            ThumbnailFile(
                                pixelWidth = naturalSize.pixelWidth,
                                pixelHeight = naturalSize.pixelHeight,
                                thumbnailBytes = thumbBytes,
                                key = key,
                                contentType = "image/jpeg",
                                quality = 80,
                            ),
                        )
                    }
                    if (pdfResult != null && index == 0) onPdfPageCount?.invoke(pdfResult.pageCount)
                } catch (e: Exception) {
                    Logger.w(e, TAG) { "PDF thumbnail generation failed for payload $key" }
                }
            }

            allPayloads += PayloadFile(
                key = key,
                filePath = filePath,
                contentType = contentType,
                previewThumbnail = previewThumbnail,
                descriptorContent = if (contentType == CONTENT_TYPE_MARKDOWN) notePreview else null,
            )
            allThumbnails += thumbnails
            if (previewThumbnail != null) allPreviews += previewThumbnail
        }

        return PayloadBundle(
            payloads = allPayloads,
            thumbnails = allThumbnails,
            previewThumbs = allPreviews,
        )
    }

    private suspend fun convertHeicIfNeeded(
        filePath: String,
        contentType: String,
    ): Pair<String, String> {
        if (contentType != "image/heic" && contentType != "image/heif") {
            return filePath to contentType
        }
        val heicBytes = fileOperationsProvider.readFileBytes(filePath)
        val jpegBytes = convertHeicToJpeg(heicBytes) ?: return filePath to contentType
        val convertedPath = fileOperationsProvider.writeBytesToTempFile(
            jpegBytes, "heic_converted_", ".jpg"
        )
        return convertedPath to "image/jpeg"
    }
}
