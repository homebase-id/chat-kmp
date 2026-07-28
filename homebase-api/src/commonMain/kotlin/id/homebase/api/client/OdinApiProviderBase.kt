package id.homebase.api.client

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.upload.DriveUploadProvider.Companion.TAG
import id.homebase.api.common.SecureByteArray
import id.homebase.api.serialization.OdinSystemSerializer
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.readRawBytes
import io.ktor.http.contentLength
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readAvailable
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import id.homebase.api.common.OdinId
import io.ktor.client.plugins.onUpload
import io.ktor.client.request.delete
import io.ktor.client.request.parameter
import io.ktor.utils.io.charsets.Charsets
import io.ktor.utils.io.charsets.name
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException

typealias UploadProgress = suspend (bytesSent: Long, totalBytes: Long?) -> Unit

data class ByteApiResponse(
    val status: Int,
    val headers: Headers,
    val bytes: ByteArray,
    val contentType: String
) {
    companion object {
        val EMPTY_404 = ByteApiResponse(
            status = 404,
            headers = Headers.Empty,
            bytes = ByteArray(0),
            contentType = "application/octet-stream"
        )

        val EMPTY_200 = ByteApiResponse(
            status = 200,
            headers = Headers.Empty,
            bytes = ByteArray(0),
            contentType = "application/octet-stream"
        )
    }
}

abstract class OdinApiProviderBase(
    protected val httpClient: HttpClient,
    protected val credentialsManager: CredentialsManager
) {

    private val HOST_URL_REGEX = Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*://[^/]+""")

    protected data class ActiveCredentials(
        val domain: OdinId,
        val accessToken: String,
        val secret: SecureByteArray
    )

    protected suspend fun requireCreds(): ActiveCredentials {
        val (domain, token, secret) =
            checkNotNull(credentialsManager.getActiveCredentials())
        return ActiveCredentials(domain, token, secret)
    }

    protected fun apiUrl(domain: OdinId, path: String): String =
        "https://$domain/api/v2$path"

    // ------------------------------------------------------------
    // Core request primitive
    // ------------------------------------------------------------

    /**
     * Run a network operation, mapping any transport failure (timeout,
     * connection refused, DNS, dropped socket) to a typed [NetworkException].
     * Cancellation is rethrown untouched so structured concurrency still works.
     * Non-network failures (decrypt, parse) are NOT caught here — they fall
     * outside [block] and propagate unchanged.
     */
    private suspend fun <T> networkCall(block: suspend () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw NetworkException(e)
        } catch (e: Exception) {
            // Some engines raise connect-time transport failures that are NOT IOExceptions —
            // Ktor CIO (Desktop) throws UnresolvedAddressException (an IllegalArgumentException)
            // on DNS/offline. Wrap those too so every engine surfaces one typed NetworkException;
            // anything that isn't a transport failure rethrows unchanged.
            if (e.isTransientNetworkFailure()) throw NetworkException(e)
            throw e
        }

    protected suspend fun request(
        block: suspend () -> HttpResponse,
        secret: SecureByteArray?
    ): ApiResponse {
        // The connect AND the body read are both network I/O — a socket can drop
        // mid-read — so both run under networkCall, which maps transport failures
        // to a typed NetworkException instead of leaking a raw IOException.
        val (response, rawBody) = networkCall {
            val r = block()
            r to r.body<String>()
        }

        // Decrypt when the server flags it (X-SSE) OR the body is unmistakably the shared-secret
        // envelope. The header is the fast path used on native; but browsers strip non-safelisted
        // response headers from cross-origin (CORS) responses unless the server sends
        // `Access-Control-Expose-Headers: X-SSE`, so on web we fall back to detecting the
        // {"iv","data"} envelope by shape and decrypt it anyway.
        val body =
            if (secret != null &&
                (response.headers["X-SSE"] == "1" || looksLikeEncryptedEnvelope(rawBody))
            ) {
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

    /**
     * True when [body] is unmistakably a [SharedSecretEncryptedPayload] envelope — a JSON object
     * with non-blank `iv` and `data` (both required). Lets [request] decide to decrypt when the
     * `X-SSE` response header isn't visible to JS (cross-origin/CORS responses on web strip it).
     * Success payloads (PagedResult, ServerFile, …) lack these fields, so they parse-fail here and
     * are left untouched.
     */
    private fun looksLikeEncryptedEnvelope(body: String): Boolean {
        return try {
            val payload = OdinSystemSerializer.deserialize<SharedSecretEncryptedPayload>(body)
            payload.iv.isNotBlank() && payload.data.isNotBlank()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Fetch a binary body fully into RAM. When [maxBytes] is set (#845), the read
     * is size-guarded: an over-limit `Content-Length` is refused BEFORE the body
     * is read, and a length-less (chunked) response is aborted the moment the
     * running count passes the limit — worst-case RAM is limit + one chunk, never
     * unbounded. The throw is [PayloadTooLargeException] (not an IOException), so
     * [networkCall] passes it through untyped-wrapped and the outbox classifier /
     * callers can treat it as non-retryable.
     */
    protected suspend fun requestBytes(
        maxBytes: Long? = null,
        block: suspend () -> HttpResponse
    ): ByteApiResponse {

        val (response, bytes) = networkCall {
            val r = block()
            if (maxBytes != null) {
                val contentLength = r.contentLength()
                if (contentLength != null && contentLength > maxBytes) {
                    // Best-effort body discard — some engines throw their own
                    // length-mismatch error from cancel; never let that mask the
                    // typed refusal.
                    runCatching { r.bodyAsChannel().cancel(null) }
                    throw PayloadTooLargeException(sizeBytes = contentLength, limitBytes = maxBytes)
                }
                r to readBodyCapped(r, maxBytes)
            } else {
                r to r.readRawBytes()
            }
        }

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

    /**
     * Read the response body while counting — the chunked-transfer fallback for
     * the [requestBytes] size guard, for responses without a Content-Length.
     */
    private suspend fun readBodyCapped(response: HttpResponse, maxBytes: Long): ByteArray {
        val channel = response.bodyAsChannel()
        val chunks = ArrayList<ByteArray>()
        var total = 0L
        val buf = ByteArray(64 * 1024)
        while (true) {
            val read = channel.readAvailable(buf, 0, buf.size)
            if (read == -1) break
            if (read == 0) continue
            total += read
            if (total > maxBytes) {
                channel.cancel(null)
                throw PayloadTooLargeException(sizeBytes = -1, limitBytes = maxBytes)
            }
            chunks.add(buf.copyOf(read))
        }
        val out = ByteArray(total.toInt())
        var off = 0
        for (c in chunks) {
            c.copyInto(out, off)
            off += c.size
        }
        return out
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
        formData: MultiPartFormDataContent,
        onProgress: UploadProgress? = null
    ): ApiResponse {
        requireHostInUrl(url)

        return request(
            {
                httpClient.post(url) {
                    bearerAuth(token)
                    setBody(formData)

                    onUpload { sent, total ->
                        onProgress?.invoke(sent, total)
                    }
                }
            },
            secret = null
        )
    }

    protected suspend fun plainPatchMultipart(
        url: String,
        token: String,
        formData: MultiPartFormDataContent,
        onProgress: UploadProgress? = null
    ): ApiResponse {
        requireHostInUrl(url)

        return request(
            {
                httpClient.patch(url) {
                    bearerAuth(token)
                    setBody(formData)

                    onUpload { sent, total ->
                        onProgress?.invoke(sent, total)
                    }
                }
            },
            secret = null
        )
    }

    protected suspend fun encryptedGet(
        url: String,
        token: String,
        secret: SecureByteArray,
        queryString: String? = null
    ): ApiResponse {

        requireHostInUrl(url)

        return request(
            {
                httpClient.get(url) {
                    bearerAuth(token)
                    accept(ContentType.Application.Json)

                    if (!queryString.isNullOrBlank()) {
                        val encrypted = OdinSystemSerializer.serialize(
                            CryptoHelper.encryptData(
                                queryString,
                                secret.unsafeBytes
                            )
                        )

                        parameter("ss", encrypted)
                    }
                }
            },
            secret = secret
        )
    }

    protected suspend fun encryptedPutJson(
        url: String,
        token: String,
        jsonBody: String,
        secret: SecureByteArray
    ): ApiResponse {
        requireHostInUrl(url)

        return request(
            {
                httpClient.put(url) {
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


    protected suspend fun encryptedDelete(
        url: String,
        token: String,
        secret: SecureByteArray,
        jsonBody: String? = null
    ): ApiResponse {
        requireHostInUrl(url)

        return request(
            {
                httpClient.delete(url) {
                    bearerAuth(token)
                    accept(ContentType.Application.Json)

                    if (jsonBody != null) {
                        contentType(ContentType.Application.Json)
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
                }
            },
            secret = secret
        )
    }


    protected suspend fun encryptedPostJson(
        url: String,
        token: String,
        jsonBody: String,
        secret: SecureByteArray,
        // Extra request headers (e.g. X-ODIN-FILE-SYSTEM-TYPE for a Comment-filesystem query).
        // Empty by default so existing callers are unaffected.
        extraHeaders: Map<String, String> = emptyMap(),
    ): ApiResponse {
        requireHostInUrl(url)

        return request(
            {
                httpClient.post(url) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    accept(ContentType.Application.Json)
                    extraHeaders.forEach { (name, value) -> header(name, value) }
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
                Logger.e(tag = TAG) {
                    "BadRequest (400) Returned from Server - code: ${problem.errorCodeEnumOrUnhandled()} (raw: ${problem.errorCode()}).  title: ${problem.title}"
                }

                throw ClientException(
                    status = 400,
                    errorCode = problem.errorCodeEnumOrUnhandled(),
                    message = problem.title ?: "Invalid request",
                    correlationId = problem.correlationId(),
                    problem = problem
                )
            }

            401 -> throw UnauthorizedException()

            403 -> {
                // OdinSecurityException carries no errorCode (always NoErrorCode/0), but its
                // title is a real, specific reason ("Forbidden: sam.example.com must have valid
                // connection to be added to a circle") — parse it best-effort for logging/support
                // triage. Never branch app logic on this text; there's no structured code to match.
                val problem = runCatching { deserialize<ProblemDetails>(response.body) }.getOrNull()
                throw ForbiddenException(problem)
            }

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
