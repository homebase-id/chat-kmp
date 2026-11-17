package id.homebase.core.ui.screens.filesync

import id.homebase.api.crypto.AesCbc
import id.homebase.api.file.FileOperationsProvider
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.forms.InputProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64

// Byte for byte: ISO-8859-1 maps every byte to one char and back.
internal fun multipart(body: ByteArray, request: HttpRequestData): Map<String, ByteArray> {
    val boundary = request.body.contentType!!.parameter("boundary")!!
    return String(body, Charsets.ISO_8859_1).split("--$boundary").mapNotNull { part ->
        val name = Regex("name=\"([^\"]+)\"").find(part.substringBefore("\r\n\r\n"))?.groupValues?.get(1) ?: return@mapNotNull null
        name to part.substringAfter("\r\n\r\n").removeSuffix("\r\n").toByteArray(Charsets.ISO_8859_1)
    }.toMap()
}

/** An upload's descriptor (encryptedKeyHeader, fileMetadata), opened with the app's shared [secret] and the request's transfer IV. */
internal suspend fun uploadDescriptor(body: ByteArray, request: HttpRequestData, secret: ByteArray): JsonObject {
    val parts = multipart(body, request)
    val transferIv = Base64.decode(Json.parseToJsonElement(parts.getValue("instructions").decodeToString()).jsonObject["transferIv"]!!.jsonPrimitive.content)
    return Json.parseToJsonElement(AesCbc.decrypt(parts.getValue("metadata"), secret, transferIv).decodeToString()).jsonObject
}

internal object NoFiles : FileOperationsProvider {
    override fun getCacheDirectory() = ""
    override fun openFileInput(path: String): InputProvider = error("not used")
    override suspend fun readFileBytes(path: String): ByteArray = error("not used")
    override fun deleteTempFile(path: String) = false
    override fun getFileSize(path: String) = 0L
    override suspend fun writeBytesToTempFile(bytes: ByteArray, prefix: String, suffix: String): String = error("not used")
    override suspend fun writeBytesToShareOutboundFile(bytes: ByteArray, suffix: String): String = error("not used")
    override suspend fun writeStream(path: String, data: Flow<ByteArray>) = error("not used")
}
