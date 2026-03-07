package id.homebase.chat.services.convo

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.files.ThumbnailFile
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.client.drives.upload.UpdateManifest
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.PayloadBundle
import id.homebase.chat.services.PayloadBundleEncryptionService
import id.homebase.core.config.chatTargetDrive
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope

class ConversationUpdater(
    private val credentialsManager: CredentialsManager,
    private val conversationRepository: ConversationRepository,
    private val payloadBundleEncryptionService: PayloadBundleEncryptionService,
    private val scope: CoroutineScope
) {

    private val chatDrive = chatTargetDrive.alias

    suspend fun updateConversation(
        conversationId: Uuid,
        title: String?,
        recipients: List<OdinId>,
        unencryptedPayloadBundle: PayloadBundle? = null
    ) {
        val credentials = credentialsManager.requireActiveCredentials()
        val domain = credentials.domain

        val conversationFile =
            conversationRepository.getConversationHomebaseFile(conversationId)
                ?: error("No conversation found")

        val normalizedRecipients = normalizeRecipients(recipients, domain)

        val keyHeader = KeyHeader(
            iv = ByteArrayUtil.getRndByteArray(16),
            aesKey = conversationFile.keyHeader.aesKey
        )

        val content = buildConversationContent(title, normalizedRecipients)

        val encryptedBundle =
            prepareUpdateBundle(
                conversationId,
                unencryptedPayloadBundle,
                keyHeader.aesKey,
                conversationFile.fileMetadata.appData.previewThumbnail
            )

        val metadata =
            buildConversationMetadata(
                conversationId,
                content,
                encryptedBundle.previewThumb,
                conversationFile.fileMetadata.versionTag
            )

        conversationRepository.updateConversationFile(
            conversationId = conversationId,
            dependencyUniqueId = null,
            keyHeader = keyHeader,
            unencryptedMetadata = metadata,
            unencryptedPayloadBundle = unencryptedPayloadBundle,
            recipients = normalizedRecipients,
            scope = scope
        )
    }

    private fun normalizeRecipients(
        recipients: List<OdinId>,
        self: OdinId
    ) = (recipients + self).distinct()

    private fun buildConversationContent(
        title: String?,
        recipients: List<OdinId>
    ) =
        ConversationAppDataJson(
            title = title ?: "",
            recipients = recipients,
            version = 1
        )

    private fun buildConversationMetadata(
        conversationId: Uuid,
        content: ConversationAppDataJson,
        previewThumb: EmbeddedThumb?,
        versionTag: Uuid?
    ) =
        UploadFileMetadata(
            allowDistribution = true,
            isEncrypted = true,
            versionTag = versionTag,
            appData = UploadAppFileMetaData(
                uniqueId = conversationId,
                fileType = ChatProtocol.ConversationFileType,
                content = OdinSystemSerializer.serialize(content),
                previewThumbnail = previewThumb
            )
        )

    private suspend fun prepareUpdateBundle(
        conversationId: Uuid,
        payloadBundle: PayloadBundle?,
        aesKey: SecureByteArray,
        existingPreview: EmbeddedThumb?
    ): UpdateBundleResult {

        if (payloadBundle == null) {
            return UpdateBundleResult(
                manifest = UpdateManifest.build(
                    payloads = null,
                    toDeletePayloads = null,
                    thumbnails = null,
                    generatePayloadIv = false
                ),
                payloads = emptyList(),
                thumbnails = emptyList(),
                previewThumb = existingPreview
            )
        }

        val encryptedBundle =
            payloadBundleEncryptionService.encryptBundle(
                conversationId,
                payloadBundle,
                aesKey,
                scope
            )

        return UpdateBundleResult(
            manifest = UpdateManifest.build(
                payloads = encryptedBundle.payloads,
                toDeletePayloads = null,
                thumbnails = encryptedBundle.thumbnails,
                generatePayloadIv = false
            ),
            payloads = encryptedBundle.payloads,
            thumbnails = encryptedBundle.thumbnails,
            previewThumb = encryptedBundle.previewThumbs.minByOrNull { it.pixelWidth }
        )
    }

    private data class UpdateBundleResult(
        val manifest: UpdateManifest,
        val payloads: List<PayloadFile>,
        val thumbnails: List<ThumbnailFile>,
        val previewThumb: EmbeddedThumb?
    )
}