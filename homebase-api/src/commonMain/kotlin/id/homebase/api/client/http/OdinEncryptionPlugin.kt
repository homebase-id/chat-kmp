package id.homebase.homebasekmppoc.prototype.lib.http

import co.touchlab.kermit.Logger
import id.homebase.homebasekmppoc.prototype.lib.crypto.CryptoHelper
import id.homebase.homebasekmppoc.prototype.lib.serialization.OdinSystemSerializer
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.http.content.TextContent
import io.ktor.util.*
import io.ktor.utils.io.*
import kotlinx.serialization.serializer

object OdinEncryptionKeys {
    val Secret = AttributeKey<ByteArray>("OdinSecret")
    val Override = AttributeKey<Boolean>("OdinOverride")
}

val OdinEncryptionPlugin =
    createClientPlugin("OdinEncryption") {

        // REQUEST ENCRYPTION
        on(Send) { request ->
            val secret = client.attributes.getOrNull(OdinEncryptionKeys.Secret)
            val override = client.attributes.getOrNull(OdinEncryptionKeys.Override) ?: false

            if (secret == null || secret.isEmpty() || override) return@on proceed(request)

            when (request.method.value.uppercase()) {
                "POST", "PUT", "PATCH", "DELETE" -> {
                    val currentBody = request.body
                    if (currentBody is TextContent && currentBody.text.isNotBlank()) {
                        val payload = CryptoHelper.encryptData(currentBody.text, secret)
                        request.setBody(
                            TextContent(
                                OdinSystemSerializer.json.encodeToString(payload),
                                ContentType.Application.Json
                            )
                        )

                    }
                }

                else -> {
                    val originalUrl = request.url.toString()
                    val encryptedUrl =
                        CryptoHelper.uriWithEncryptedQueryString(originalUrl, secret)
                    request.url.parameters.clear()
                    request.url.takeFrom(encryptedUrl)
                }
            }
            proceed(request)
        }

        // RESPONSE DECRYPTION
        transformResponseBody { response, content, requestedType ->
            val secret =
                this@createClientPlugin.client.attributes.getOrNull(
                    OdinEncryptionKeys.Secret
                )
                    ?: return@transformResponseBody null
            val override = this@createClientPlugin.client.attributes.getOrNull(
                OdinEncryptionKeys.Override
            ) ?: false

            if (secret.isEmpty() || override || response.status.value == 204) {
                return@transformResponseBody null
            }

            val text = content.readRemaining().readText()

            val jsonText =
                if (text.contains("\"data\"") && text.contains("\"iv\"")) {
                    try {
                        CryptoHelper.decryptContentAsString(text, secret)
                    } catch (e: Exception) {
                        Logger.e(e) { "Decrypt failed, using original text" }
                        text
                    }
                } else {
                    text
                }

            // If the caller wants a String, return the text directly
            if (requestedType.type == String::class) {
                return@transformResponseBody jsonText
            }

            // If the caller wants ByteReadChannel, return it as-is
            if (requestedType.type == ByteReadChannel::class) {
                return@transformResponseBody ByteReadChannel(jsonText)
            }

            // Otherwise, deserialize to the requested type using OdinSystemSerializer
            try {
                val kType =
                    requestedType.kotlinType
                        ?: throw IllegalStateException(
                            "No KType available for ${requestedType.type}"
                        )
                val serializer = OdinSystemSerializer.json.serializersModule.serializer(kType)
                OdinSystemSerializer.json.decodeFromString(serializer, jsonText)
            } catch (e: Exception) {
                Logger.e(e) {
                    "Failed to deserialize response to ${requestedType.type.qualifiedName}: ${e.message}"
                }
                throw e
            }
        }
    }


val OdinErrorPlugin =
    createClientPlugin("OdinErrorPlugin") {

        on(Send) { request ->
            try {
                proceed(request)
            } catch (throwable: Throwable) {

                val response = (throwable as? ResponseException)?.response
                    ?: throw throwable

                if (response.status.value == 404) throw throwable

                val secret =
                    client.attributes.getOrNull(OdinEncryptionKeys.Secret)
                        ?: throw throwable

                val override =
                    client.attributes.getOrNull(OdinEncryptionKeys.Override) ?: false

                if (secret.isEmpty() || override) throw throwable

                val bodyText =
                    try {
                        response.bodyAsText()
                    } catch (_: Exception) {
                        throw throwable
                    }

                if (bodyText.contains("\"data\"") && bodyText.contains("\"iv\"")) {
                    try {
                        val decrypted =
                            CryptoHelper.decryptContentAsString(bodyText, secret)

                        Logger.e {
                            "[odin-kotlin] ${response.status.value} ${request.url}: $decrypted"
                        }
                    } catch (e: Exception) {
                        Logger.e(e) { "Failed to decrypt encrypted error payload" }
                    }
                }

                throw throwable
            }
        }
    }
