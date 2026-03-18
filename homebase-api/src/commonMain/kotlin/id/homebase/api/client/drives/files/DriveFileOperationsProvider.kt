package id.homebase.api.client.drives.files

import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.common.OdinId
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.serialization.OdinSystemSerializer
import io.ktor.client.HttpClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.Uuid

@Serializable
data class SendReadReceiptByEndTimeRequestV2(
    val fileType: Int?,
    val dataType: Int?,
    val groupId: Uuid?,
    val endTime: UnixTimeUtc
)

@Serializable
data class SendReadReceiptRequest(
    val files: List<Uuid>
)

@Serializable
data class SendReadReceiptResult(
    val results: List<SendReadReceiptResultFileItem>
)

@Serializable
data class SendReadReceiptResultFileItem(
    val fileId: Uuid,
    val status: List<SendReadReceiptResultRecipientStatusItem>
)

@Serializable
data class SendReadReceiptResultRecipientStatusItem(
    val recipient: OdinId?,
    val status: SendReadReceiptResultStatus
)

@Serializable
enum class SendReadReceiptResultStatus {

    @SerialName("notConnectedToOriginalSender")
    NotConnectedToOriginalSender,

    @SerialName("fileDoesNotExist")
    FileDoesNotExist,

    @SerialName("fileDoesNotHaveSender")
    FileDoesNotHaveSender,

    @SerialName("missingGlobalTransitId")
    MissingGlobalTransitId,

    @SerialName("enqueued")
    Enqueued,

    @SerialName("cannotSendReadReceiptToSelf")
    CannotSendReadReceiptToSelf
}

@OptIn(ExperimentalEncodingApi::class)
public class DriveFileOperationsProvider(
    httpClient: HttpClient,
    credentialsManager: CredentialsManager
) : OdinApiProviderBase(httpClient, credentialsManager) {

    companion object {
        private const val TAG = "DriveFileOperationsProvider"
    }

    suspend fun sendReadReceiptBatch(
        driveId: Uuid,
        fileIds: List<Uuid>
    ): SendReadReceiptResult {

        ValidationUtil.requireValidUuid(driveId, "driveId")
        ValidationUtil.requireValidUuidList(fileIds, "fileIds")

        val creds = requireCreds()

        val endpoint = "/drives/$driveId/files/send-read-receipt-batch"

        val request =
            SendReadReceiptRequest(
                files = fileIds
            )

        val response = encryptedPostJson(
            url = apiUrl(creds.domain, endpoint),
            token = creds.accessToken,
            jsonBody = OdinSystemSerializer.serialize(request),
            secret = creds.secret
        )

        throwForFailure(response)

        return deserialize(response.body)
    }

    suspend fun sendReadReceiptBatch(
        driveId: Uuid,
        fileType: Int?,
        dataType: Int?,
        groupId: Uuid?,
        endTime: UnixTimeUtc
    ): SendReadReceiptResult {

        ValidationUtil.requireValidUuid(driveId, "driveId")

        val creds = requireCreds()

        val endpoint = "/drives/$driveId/files/send-read-receipt-batch-by-time"

        val request =
            SendReadReceiptByEndTimeRequestV2(
                fileType = fileType,
                dataType = dataType,
                groupId = groupId,
                endTime = endTime
            )

        val response = encryptedPostJson(
            url = apiUrl(creds.domain, endpoint),
            token = creds.accessToken,
            jsonBody = OdinSystemSerializer.serialize(request),
            secret = creds.secret
        )

        throwForFailure(response)

        return deserialize(response.body)
    }

}

