package id.homebase.api.client.peer

import co.touchlab.kermit.Logger
import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.UploadProgress
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.upload.TransferUploadStatus
import id.homebase.api.client.drives.upload.TransitInstructionSet
import id.homebase.api.client.drives.upload.TransitUploadResult
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.client.drives.upload.UploadManifest
import id.homebase.api.client.drives.upload.buildSharedSecretEncryptedUploadDescriptor
import id.homebase.api.client.drives.upload.buildTransitFormData
import id.homebase.api.client.drives.upload.calculateUploadSize
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.serialization.OdinSystemSerializer
import io.ktor.client.HttpClient

/**
 * Writes a file straight onto a **peer's** drive (a community owner's collaborative drive) over
 * transit. Mirrors the JS `uploadFileOverPeer` (`js-lib/.../peer/peerData/Upload/PeerFileUploader.ts`).
 *
 * The user's OWN host (`creds.domain`) brokers the send: the request is posted to the local host,
 * which forwards the encrypted file to the [TransitInstructionSet.recipients] (the owner) and lands
 * it on [TransitInstructionSet.remoteTargetDrive]. Descriptor/payload encryption is identical to the
 * own-host upload path — the caller pre-encrypts `metadata.appData.content` and we encrypt the
 * descriptor with the caller's own shared secret (see [buildSharedSecretEncryptedUploadDescriptor]).
 *
 * This is what honours the previously-unused [id.homebase.api.client.drives.upload.TransitOptions.remoteTargetDrive].
 */
class PeerDriveUploadProvider(
    httpClient: HttpClient,
    credentialsManager: CredentialsManager,
    private val fileOperationsProvider: FileOperationsProvider,
) : OdinApiProviderBase(httpClient, credentialsManager) {

    /**
     * Send [request]'s file to the owner-hosted drive. [request.transitOptions] must carry a non-null
     * `remoteTargetDrive` and at least one recipient (the owner). Returns the transit result, or null
     * if the broker rejected the request; throws if any recipient is reported `EnqueuedFailed`.
     */
    suspend fun uploadFileOverPeer(
        request: UploadFileRequest,
        onProgress: UploadProgress? = null,
    ): TransitUploadResult? {
        val transit = request.transitOptions
        val remoteTargetDrive = requireNotNull(transit?.remoteTargetDrive) {
            "uploadFileOverPeer requires transitOptions.remoteTargetDrive"
        }
        val recipients = transit?.recipients
        require(!recipients.isNullOrEmpty()) {
            "uploadFileOverPeer requires at least one recipient (the drive owner)"
        }

        val creds = requireCreds()
        val sharedSecret = creds.secret.unsafeBytes

        val transferIv = ByteArrayUtil.getRndByteArray(16)
        val sharedSecretEncryptedDescriptor = buildSharedSecretEncryptedUploadDescriptor(
            request.keyHeader,
            request.metadata,
            sharedSecret,
            transferIv = transferIv,
        )

        val instructions = TransitInstructionSet(
            transferIv = transferIv,
            manifest = UploadManifest.build(
                request.payloads,
                request.thumbnails,
                generatePayloadIv = request.metadata.isEncrypted,
            ),
            remoteTargetDrive = remoteTargetDrive,
            recipients = recipients,
            schedule = transit.schedule,
            priority = transit.priority,
            notificationOptions = if (transit.useAppNotification == true) transit.appNotificationOptions else null,
        )

        val data = buildTransitFormData(
            instructionSet = instructions,
            sharedSecretEncryptedDescriptor = sharedSecretEncryptedDescriptor,
            payloads = request.payloads,
            thumbnails = request.thumbnails,
            fileOperationsProvider = fileOperationsProvider,
        )

        val totalUploadSize = calculateUploadSize(
            request.payloads,
            request.thumbnails,
            sharedSecretEncryptedDescriptor,
            fileOperationsProvider,
        )
        val wrappedProgress = onProgress?.let { progress ->
            suspend { sent: Long, _: Long? -> progress(sent, totalUploadSize) }
        }

        // V2 over-peer send is a per-drive route carrying owner + driveId in the path; the multipart
        // body is the same V1 transit bundle (TransitInstructionSet with remoteTargetDrive +
        // recipients, then metadata, then payload/thumbnail parts).
        val owner = recipients.first()
        val url = apiUrl(creds.domain, "/peer/$owner/drives/${remoteTargetDrive.alias}/files/send")
        Logger.i(tag = TAG) {
            "uploadFileOverPeer: POST $url recipients=${recipients.map { it.domainName }} " +
                "remoteDrive=$remoteTargetDrive"
        }

        val response = plainPostMultipart(
            url = url,
            token = creds.accessToken,
            formData = data,
            onProgress = wrappedProgress,
        )

        if (response.status !in 200..299) {
            Logger.w(tag = TAG) { "uploadFileOverPeer: FAIL status=${response.status} body=${response.body.take(300)}" }
            throwForFailure(response)
            return null
        }

        val result: TransitUploadResult = OdinSystemSerializer.deserialize(response.body)

        val failed = result.recipientStatus.filterValues { it == TransferUploadStatus.EnqueuedFailed }
        if (failed.isNotEmpty()) {
            throw IllegalStateException("uploadFileOverPeer: recipients failed to enqueue: ${failed.keys}")
        }

        Logger.i(tag = TAG) {
            "uploadFileOverPeer: OK gtid=${result.remoteGlobalTransitIdFileIdentifier?.globalTransitId} " +
                "status=${result.recipientStatus}"
        }
        return result
    }

    companion object {
        private const val TAG = "PeerDriveUpload"
    }
}
