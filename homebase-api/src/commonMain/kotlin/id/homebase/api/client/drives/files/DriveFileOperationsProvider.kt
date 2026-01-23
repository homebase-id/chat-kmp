package id.homebase.api.client.drives.files

import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.homebasekmppoc.prototype.lib.drives.files.ValidationUtil
import id.homebase.homebasekmppoc.prototype.lib.serialization.OdinSystemSerializer
import io.ktor.client.HttpClient
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.Uuid
import kotlinx.serialization.Serializable

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
    val status: String
)

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

}

