package id.homebase.api.client

import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.common.SecureByteArray
import id.homebase.api.serialization.OdinSystemSerializer
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.contentType

data class ByteApiResponse(
    val status: Int,
    val headers: Headers,
    val bytes: ByteArray,
    val contentType: String
)

abstract class OdinApiProviderBase(
    protected val httpClient: HttpClient,
    protected val credentialsManager: CredentialsManager
) {

    private val HOST_URL_REGEX = Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*://[^/]+""")

    protected data class ActiveCredentials(
        val domain: String,
        val accessToken: String,
        val secret: SecureByteArray
    )

    protected suspend fun requireCreds(): ActiveCredentials {
        val (domain, token, secret) =
            checkNotNull(credentialsManager.getActiveCredentials())
        return ActiveCredentials(domain, token, secret)
    }

    protected fun apiUrl(domain: String, path: String): String =
        "https://$domain/api/v2$path"

    // ------------------------------------------------------------
    // Core request primitive
    // ------------------------------------------------------------

    protected suspend fun request(
        block: suspend () -> HttpResponse,
        secret: SecureByteArray?
    ): ApiResponse {
        val response = block()
        val rawBody = response.body<String>()
        val isEncrypted = response.headers["X-SSE"] == "1"

        val body =
            if (isEncrypted && secret != null) {
                CryptoHelper.decryptContentAsString(rawBody, secret.unsafeBytes)
            } else {
                rawBody
            }

        return ApiResponse(
            status = response.status.value,
            headers = response.headers,
            body = body
        )
    }

    protected suspend fun requestBytes(
        block: suspend () -> HttpResponse
    ): ByteApiResponse {

        val response = block()
        val bytes = response.readRawBytes()

        val contentType =
            response.headers["decryptedcontenttype"]
                ?: response.headers[HttpHeaders.ContentType]
                ?: "application/octet-stream"

        return ByteApiResponse(
            status = response.status.value,
            headers = response.headers,
            bytes = bytes,
            contentType = contentType
        )
    }

    // ------------------------------------------------------------
    // Plain requests (JSON already serialized)
    // ------------------------------------------------------------
    protected suspend fun plainGet(
        url: String,
        token: String
    ): ApiResponse {
        requireHostInUrl(url)

        return request(
            {
                httpClient.get(url) {
                    bearerAuth(token)
                    accept(ContentType.Application.Json)
                }
            },
            secret = null
        )
    }

    protected suspend fun plainPutJson(
        url: String,
        token: String,
        jsonBody: String
    ): ApiResponse {
        requireHostInUrl(url)

        return request(
            {
                httpClient.put(url) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    accept(ContentType.Application.Json)
                    setBody(TextContent(jsonBody, ContentType.Application.Json))
                }
            },
            secret = null
        )
    }

    protected suspend fun plainPatchJson(
        url: String,
        token: String,
        jsonBody: String
    ): ApiResponse {
        requireHostInUrl(url)

        return request(
            {
                httpClient.patch(url) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    accept(ContentType.Application.Json)
                    setBody(TextContent(jsonBody, ContentType.Application.Json))
                }
            },
            secret = null
        )
    }

    protected suspend fun plainPostJson(
        url: String,
        token: String,
        jsonBody: String
    ): ApiResponse {
        requireHostInUrl(url)
        return request(
            {
                httpClient.post(url) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    accept(ContentType.Application.Json)
                    setBody(TextContent(jsonBody, ContentType.Application.Json))
                }
            },
            secret = null
        )
    }


    protected suspend fun plainPostMultipart(
        url: String,
        token: String,
        formData: MultiPartFormDataContent
    ): ApiResponse {
        requireHostInUrl(url)

        return request(
            {
                httpClient.post(url) {
                    bearerAuth(token)
                    setBody(formData)
                }
            },
            secret = null
        )
    }

    protected suspend fun plainPatchMultipart(
        url: String,
        token: String,
        formData: MultiPartFormDataContent
    ): ApiResponse {
        requireHostInUrl(url)

        return request(
            {
                httpClient.patch(url) {
                    bearerAuth(token)
                    setBody(formData)
                }
            },
            secret = null
        )
    }

    protected suspend fun encryptedGet(
        url: String,
        token: String,
        secret: SecureByteArray
    ): ApiResponse {
        requireHostInUrl(url)

        return request(
            {
                httpClient.get(url) {
                    bearerAuth(token)
                    accept(ContentType.Application.Json)
                }
            },
            secret = secret
        )
    }

    protected suspend fun encryptedPostJson(
        url: String,
        token: String,
        jsonBody: String,
        secret: SecureByteArray
    ): ApiResponse {
        requireHostInUrl(url)

        return request(
            {
                httpClient.post(url) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    accept(ContentType.Application.Json)
                    setBody(
                        TextContent(
                            OdinSystemSerializer.serialize(
                                CryptoHelper.encryptData(
                                    jsonBody,
                                    secret.unsafeBytes
                                )
                            ),
                            ContentType.Application.Json
                        )
                    )
                }
            },
            secret = secret
        )
    }

    protected suspend fun encryptedPatchJson(
        url: String,
        token: String,
        jsonBody: String,
        secret: SecureByteArray
    ): ApiResponse {
        requireHostInUrl(url)

        return request(
            {
                httpClient.patch(url) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    accept(ContentType.Application.Json)
                    setBody(
                        TextContent(
                            OdinSystemSerializer.json.encodeToString(
                                CryptoHelper.encryptData(jsonBody, secret.unsafeBytes)
                            ),
                            ContentType.Application.Json
                        )
                    )
                }
            },
            secret = secret
        )
    }

    protected inline fun <reified T> deserialize(json: String): T =
        OdinSystemSerializer.deserialize(json)

    protected fun throwForFailure(response: ApiResponse) {
        if (response.status in 200..299) return

        when (response.status) {
            400 -> {
                val problem = deserialize<ProblemDetails>(response.body)
                throw ClientException(
                    status = 400,
                    errorCode = problem.errorCodeEnumOrUnhandled(),
                    message = problem.title ?: "Invalid request",
                    correlationId = problem.correlationId(),
                    problem = problem
                )
            }

            401 -> throw UnauthorizedException()

            403 -> throw ForbiddenException()

            404 -> throw NotFoundException()

            in 500..599 -> {
                val problem = runCatching {
                    deserialize<ProblemDetails>(response.body)
                }.getOrNull()

                throw ServerException(
                    status = response.status,
                    correlationId = problem?.correlationId(),
                    problem = problem
                )
            }

            else -> {
                throw ServerException(
                    status = response.status,
                    correlationId = null,
                    problem = null
                )
            }
        }
    }

    protected fun throwForFailure(response: Any) {
        val status: Int
        val headers: Headers
        val body: String

        when (response) {
            is ApiResponse -> {
                status = response.status
                headers = response.headers
                body = response.body
            }

            is ByteApiResponse -> {
                status = response.status
                headers = response.headers
                body = runCatching { response.bytes.decodeToString() }
                    .getOrDefault("")
            }

            else -> error("Unsupported response type")
        }

        if (status in 200..299) return

        throwForFailure(
            ApiResponse(
                status = status,
                headers = headers,
                body = body
            )
        )
    }

    fun requireHostInUrl(url: String) {
        if (!HOST_URL_REGEX.containsMatchIn(url)) {
            throw IllegalArgumentException("URL must include a scheme and host: $url")
        }
    }

}
