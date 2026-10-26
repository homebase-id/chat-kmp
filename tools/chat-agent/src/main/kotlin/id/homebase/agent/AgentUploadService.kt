package id.homebase.agent

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.drives.upload.DriveUploadProvider
import id.homebase.api.client.drives.upload.TransitOptions
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.file.JvmFileOperationsProvider
import id.homebase.api.video.VideoPayloadProcessor
import id.homebase.upload.PayloadBundle
import id.homebase.upload.PayloadBundleEncryptionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlin.uuid.Uuid

// The agent has no outbox DB, so UploadService (outbox-backed) can't be built headless; this is its synchronous counterpart.
class AgentUploadService(private val session: Session, private val fileOps: JvmFileOperationsProvider) {
    private val encryptor = PayloadBundleEncryptionService(fileOps, VideoPayloadProcessor(fileOps), EventBus())

    suspend fun upload(
        uniqueId: Uuid,
        keyHeader: KeyHeader,
        metadata: UploadFileMetadata,
        transit: TransitOptions,
        bundle: PayloadBundle?,
        preEncrypted: Boolean,
    ) {
        val encrypted = bundle?.let {
            if (preEncrypted) it else encryptor.encryptBundle(uniqueId, it, keyHeader.aesKey, CoroutineScope(currentCoroutineContext()))
        }
        DriveUploadProvider(session.http, session.credentials, fileOps).uploadFile(
            UploadFileRequest(
                driveId = SystemDriveConstants.chatDrive.alias,
                keyHeader = keyHeader,
                metadata = metadata,
                transitOptions = transit,
                payloads = encrypted?.payloads.orEmpty(),
                thumbnails = encrypted?.thumbnails.orEmpty(),
            )
        )
    }
}
