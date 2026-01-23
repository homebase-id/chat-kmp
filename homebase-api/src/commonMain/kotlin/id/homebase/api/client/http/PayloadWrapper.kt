package id.homebase.homebasekmppoc.prototype.lib.http

import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.homebasekmppoc.lib.config.feedTargetDrive
import id.homebase.homebasekmppoc.prototype.lib.authentication.AuthState
import id.homebase.homebasekmppoc.prototype.lib.crypto.CryptoHelper
import id.homebase.homebasekmppoc.prototype.lib.crypto.KeyHeader
import id.homebase.homebasekmppoc.prototype.lib.drives.files.PayloadDescriptor
import id.homebase.homebasekmppoc.prototype.lib.serialization.OdinSystemSerializer
import id.homebase.homebasekmppoc.prototype.lib.video.VideoMetaData
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.http.contentLength
import kotlin.io.encoding.Base64

class PayloadWrapper(
    val authenticated: AuthState.Authenticated,
    val header: HomebaseFile,
    val payloadDescriptor: PayloadDescriptor
) {

    //

    val headerWrapper = HeaderWrapper(authenticated, header)

    val isEncrypted: Boolean
        get() = headerWrapper.isEncrypted

    //

    suspend fun decryptKeyHeader(): KeyHeader? {
        if (!isEncrypted) {
            return null
        }

        return header.keyHeader
    }

    //

    fun getCompositeKey(): String {
        return header.fileId.toString() + "-" + payloadDescriptor.key
    }

    //

    fun getPayloadUri(appOrOwner: AppOrOwner): String {
        val fileId = header.fileId

        // TODO: Seb - we need to get you converted to API v2
        val alias = header.driveId
        val type = feedTargetDrive.type // header.targetDrive.type
        val payloadKey = payloadDescriptor.key

        // calls backend DriveStorageControllerBase.GetPayloadStream
        val uri =
                "https://${authenticated.identity}/api/$appOrOwner/v1/drive/files/payload?alias=$alias&type=$type&fileId=$fileId&key=$payloadKey&xfst=128"

        return uri
    }

    //

    suspend fun getEncryptedPayloadUri(appOrOwner: AppOrOwner): String {
        val plain = getPayloadUri(appOrOwner)
        val cipher = CryptoHelper.uriWithEncryptedQueryString(plain, authenticated.sharedSecret)
        return cipher
    }

    //

    suspend fun getPayloadBytes(appOrOwner: AppOrOwner): ByteArray {
        val encryptedUri = getEncryptedPayloadUri(appOrOwner)

        Logger.d("PayloadPlayground") { "Making GET request to: $encryptedUri" }

        val client = createHttpClient()
        val response =
                client.get(encryptedUri) {
                    headers {
                        append(
                                "Cookie",
                                "${cookieNameFrom(appOrOwner)}=${authenticated.clientAuthToken}"
                        )
                    }
                }

        Logger.d("PayloadPlayground") { "Response length: ${response.contentLength()}" }

        val bytes = response.body<ByteArray>()
        Logger.d("PayloadPlayground") { "Payload length: ${bytes.size}" }

        if (!isEncrypted) {
            return bytes
        } else {
            val payloadIvBase64 =
                    payloadDescriptor.iv ?: throw Exception("No IV found in payload descriptor")
            val keyHeader = decryptKeyHeader() ?: throw Exception("Failed to decrypt KeyHeader")

            // Decrypt the payload using the KeyHeader's AES key BUT the payload's IV
            val payloadIv = Base64.decode(payloadIvBase64)
            Logger.d("PayloadPlayground") { "Using payload IV: ${payloadIv.size} bytes" }

            val decryptedBytes = keyHeader.decryptWithIv(bytes, payloadIv)
            Logger.d("PayloadPlayground") { "Decrypted payload length: ${decryptedBytes.size}" }

            return decryptedBytes
        }
    }

    //

    fun getVideoMetaData(appOrOwner: AppOrOwner): VideoMetaData {
        val playlistContent =
                payloadDescriptor.descriptorContent
                        ?: throw Exception("No descriptor content found in payload")

        val videoMetaData = OdinSystemSerializer.deserialize<VideoMetaData>(playlistContent)
        return videoMetaData
    }

    //

}
