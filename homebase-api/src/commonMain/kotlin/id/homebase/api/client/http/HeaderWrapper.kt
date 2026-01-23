package id.homebase.homebasekmppoc.prototype.lib.http

import id.homebase.api.client.drives.HomebaseFile
import id.homebase.homebasekmppoc.prototype.lib.authentication.AuthState
import id.homebase.homebasekmppoc.prototype.lib.crypto.KeyHeader
import id.homebase.homebasekmppoc.prototype.lib.drives.files.PayloadDescriptor

class HeaderWrapper(
    val authenticated: AuthState.Authenticated,
    val header: HomebaseFile
) {

    val payloads = header.fileMetadata.payloads ?: emptyList()

    val isEncrypted: Boolean get() = header.fileMetadata.isEncrypted

    val payloadDescriptors: List<PayloadDescriptor> get() = payloads

    //

    suspend fun decryptKeyHeader(): KeyHeader? {
        if (!isEncrypted) {
            return null
        }

        val result =  header.keyHeader
        return result
    }

    //

    fun getPayloadWrapper(key: String): PayloadWrapper {
        val payload = payloads.find { it.key == key }
        if (payload == null) {
            throw Exception("Payload with key $key not found")
        }
        return PayloadWrapper(authenticated, header, payload)
    }

    //

    fun getVideoPayloadWrappers(): List<PayloadWrapper> {
        return payloads
            .filter { it.contentType?.contains("video") == true }
            .map { payloadDescriptor -> PayloadWrapper(authenticated, header, payloadDescriptor) }
    }

    //



}


