@file:OptIn(ExperimentalUuidApi::class, ExperimentalEncodingApi::class)

package id.homebase.core.ui.screens.vault

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.files.ExportDestination
import id.homebase.api.client.drives.files.PayloadDownloadService
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.upload.PayloadDeleteKey
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.withResolvedFiles
import id.homebase.api.image.MediaQuality
import id.homebase.api.sync.database.enqueued
import id.homebase.chat.services.LocalAttachmentContextStore
import id.homebase.upload.MetadataPolicy
import id.homebase.upload.PayloadBundle
import id.homebase.upload.MediaUploadSpec
import id.homebase.upload.UploadOptions
import id.homebase.upload.UploadOutcome
import id.homebase.upload.UploadService
import id.homebase.upload.UploadSource
import id.homebase.upload.UserUploadBuilder
import id.homebase.core.config.vaultLabeledDrive
import id.homebase.core.ui.screens.vault.model.VaultEntry
import id.homebase.core.ui.screens.vault.model.VaultFileContent
import id.homebase.core.ui.screens.vault.model.VAULT_FILE_TYPE
import id.homebase.core.util.CONTENT_TYPE_MARKDOWN
import id.homebase.core.util.extensionForMimeType
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.core.pdf.PDF_CARD_RENDER_WIDTH
import id.homebase.core.pdf.generatePdfThumbnailFromFile
import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val TAG = "VaultUploaderService"
private fun vaultPayloadKey(index: Int): String = "vlt_pg_${index.toString().padStart(2, '0')}"

class VaultUploaderService(
    private val uploadService: UploadService,
    private val fileOperationsProvider: FileOperationsProvider,
    private val driveFileProvider: DriveFileProvider,
    private val payloadDownloadService: PayloadDownloadService,
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
        label: String? = null,
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
                label = label,
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
        label: String?,
        uniqueId: Uuid,
    ): Uuid? {
        return try {
            val keyHeader = KeyHeader.newRandom16()

            var pdfPageCount: Int? = null
            val bundle = vaultBundle(files, notePreview = notePreview) { if (pdfPageCount == null) pdfPageCount = it }

            val content = OdinSystemSerializer.serialize(
                VaultFileContent(name = entryName, label = label, notes = notes, pdfPageCount = pdfPageCount)
            )
            // previewThumbs pass through encryption unchanged, so derive from the plaintext bundle.
            val unencryptedMetadata = UploadFileMetadata(
                allowDistribution = false,
                isEncrypted = true,
                appData = UploadAppFileMetaData(
                    uniqueId = uniqueId,
                    content = content,
                    fileType = VAULT_FILE_TYPE,
                    groupId = groupId,
                    previewThumbnail = bundle.previewThumbs.firstOrNull(),
                ),
            )

            // Local-only vault file (allowDistribution=false). UploadService owns the spine:
            // encrypt (fail-soft) → enqueue → seed cache → optimistic writeNewFile.
            val outcome = uploadService.upload(
                MediaUploadSpec(
                    driveId = driveId,
                    uniqueId = uniqueId,
                    keyHeader = keyHeader,
                    bundle = bundle,
                    metadata = unencryptedMetadata,
                    originalRecipientCount = 0,
                ),
                scope = scope,
            )
            if (outcome is UploadOutcome.Enqueued) uniqueId else null
        } catch (e: Exception) {
            Logger.e(e, TAG) { "Failed to enqueue multi-payload upload: $entryName" }
            null
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
        return try {
            val existingMaxIndex =
                file.payloadDescriptors.mapNotNull { it.key.removePrefix("vlt_pg_").toIntOrNull() }
                    .maxOrNull() ?: -1
            val startIndex = existingMaxIndex + 1

            val bundle = vaultBundle(newFiles, startIndex)

            // Plaintext bundle — UploadService.updateFile (via enqueueFileContentUpdate) encrypts
            // (fail-soft), builds the manifest, and writes the optimistic update.
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
                bundle = bundle,
            )
        } catch (e: Exception) {
            Logger.e(e, TAG) { "Failed to enqueue append pages to ${file.uniqueId}" }
            false
        }
    }

    /**
     * Replaces the image payload at [payloadKey] in place with [newFilePath]
     * (the edited bytes). Same encryption/thumbnail/outbox path as
     * [appendResolvedPages] — only the key is reused so the page is updated
     * rather than appended.
     */
    suspend fun replacePagePayload(
        file: VaultEntry,
        payloadKey: String,
        newFilePath: String,
        contentType: String,
        scope: CoroutineScope,
    ): Boolean =
        fileOperationsProvider.withResolvedFiles(listOf(newFilePath)) { resolved ->
            replaceResolvedPagePayload(
                file = file,
                payloadKey = payloadKey,
                newFilePath = resolved.first(),
                contentType = contentType,
                scope = scope,
            )
        }

    private suspend fun replaceResolvedPagePayload(
        file: VaultEntry,
        payloadKey: String,
        newFilePath: String,
        contentType: String,
        scope: CoroutineScope,
    ): Boolean {
        return try {
            val bundle = vaultBundle(listOf(newFilePath to contentType), keyFor = { payloadKey })

            vaultService.enqueueFileContentUpdate(
                uniqueId = file.uniqueId,
                fileContent = VaultFileContent(
                    name = file.fileName,
                    label = file.label,
                    notes = file.notes,
                ),
                groupId = file.groupId,
                versionTag = file.versionTag,
                keyHeader = file.keyHeader,
                bundle = bundle,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(e, TAG) { "Failed to enqueue replace page $payloadKey on ${file.uniqueId}" }
            false
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
                toDeletePayloads = listOf(PayloadDeleteKey(payloadKey)),
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
    ): Boolean = vaultService.runDurable {
        // Run the whole persist (temp write → encrypt → enqueue) on the service scope so backing out
        // of the editor mid-save doesn't cancel it before the outbox row commits (issue #927).
        val tempPath = fileOperationsProvider.writeBytesToTempFile(
            markdown.encodeToByteArray(), "vault_note_", ".md"
        )
        try {
            val existingKey = file.payloadDescriptors.firstOrNull()?.key
                ?: VaultEntry.DEFAULT_PAYLOAD_KEY

            val payload = PayloadFile(
                key = existingKey,
                filePath = tempPath,
                contentType = CONTENT_TYPE_MARKDOWN,
                descriptorContent = notePreview,
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
                bundle = PayloadBundle(listOf(payload), emptyList(), emptyList()),
                // A note edit is a full-state single-payload overwrite: write the new body through +
                // seed it (so a read-back before send is correct) and coalesce rapid re-saves so the
                // newest body wins instead of being dropped as AlreadyQueued (issue #927).
                fileId = file.fileId,
                replace = true,
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
                // The descriptor IV is authoritative: an update reuses the file AES key with a
                // fresh IV, so file.keyHeader.iv is stale for the new payload. Do NOT fall back
                // to it on a malformed descriptor IV — decrypting with the wrong IV silently
                // returns corrupt bytes. Let the failure hit the outer catch → null (issue #927).
                KeyHeader(iv.bytes, file.keyHeader.aesKey)
            } else {
                file.keyHeader
            }

            val ct = payloadDescriptor?.contentType ?: file.contentType
            val extension = extensionForMimeType(ct.substringBefore(';').trim().lowercase())
                ?: file.fileName.substringAfterLast('.', "").takeIf { it.length in 1..5 && it.all(Char::isLetterOrDigit) }
                ?: ct.substringAfter("/", "bin")
            // Stream-decrypt into a disposable upload-temp file (#845) — bounded RAM
            // for any payload size; the old byte path buffered the whole payload
            // (~2×) in memory. Same dir + sweep lifecycle as writeBytesToTempFile.
            payloadDownloadService.exportToTemp(
                driveId = file.driveId,
                fileId = file.fileId,
                key = payloadKey,
                keyHeader = keyHeader,
                destination = ExportDestination.UploadTemp("share_", ".$extension"),
            )
        } catch (e: Exception) {
            Logger.e(e, TAG) { "Failed to download payload $payloadKey from ${file.fileId}" }
            null
        }
    }

    // Vault keeps a photo's metadata and its file as it is: it's the user's own encrypted storage (UPLOADS.md section 5).
    private suspend fun vaultBundle(
        files: List<Pair<String, String>>,
        startIndex: Int = 0,
        notePreview: String? = null,
        keyFor: (Int) -> String = { vaultPayloadKey(startIndex + it) },
        onPdfPageCount: ((Int) -> Unit)? = null,
    ): PayloadBundle = UserUploadBuilder.build(
        sources = files.map { (path, type) ->
            UploadSource(path, type, appContent = notePreview?.takeIf { type == CONTENT_TYPE_MARKDOWN })
        },
        options = UploadOptions(quality = MediaQuality.ORIGINAL, metadata = MetadataPolicy.KEEP),
        fileOps = fileOperationsProvider,
        pdfPageRenderer = { path ->
            generatePdfThumbnailFromFile(path, PDF_CARD_RENDER_WIDTH)?.also { onPdfPageCount?.invoke(it.pageCount) }?.thumbnailBytes
        },
        payloadKey = keyFor,
    )
}
