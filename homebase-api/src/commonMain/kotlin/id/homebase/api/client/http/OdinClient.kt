package id.homebase.homebasekmppoc.prototype.lib.http

import id.homebase.homebasekmppoc.prototype.lib.serialization.OdinSystemSerializer
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.cookies.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.*
import io.ktor.serialization.kotlinx.json.*

data class CreateHttpClientOptions(
    val overrideEncryption: Boolean = false
)

/**
 * HTTP client for making authenticated requests to Odin backend Handles query string encryption and
 * authentication headers
 */
open class OdinClient(
    private val providerOptions: ProviderOptions
) {

    // ─────────────────────────────────────────────
    // EXISTING PUBLIC API — UNCHANGED
    // ─────────────────────────────────────────────

    fun getSharedSecret(): ByteArray? =
        providerOptions.sharedSecret

    fun getHostIdentity(): String =
        providerOptions.hostIdentity

    fun getLoggedInIdentity(): String? =
        providerOptions.loggedInIdentity

    fun isAuthenticated(): Boolean =
        getSharedSecret() != null

    fun getRoot(): String =
        "https://${getHostIdentity()}"

    fun getEndpointUrl(): String =
        "${getRoot()}/api/v2/"

    fun getHeaders(): Map<String, String> =
        providerOptions.headers ?: emptyMap()

    // ─────────────────────────────────────────────
    // HTTP CLIENT MANAGEMENT (RESETTABLE)
    // ─────────────────────────────────────────────

    private var encryptedClient: HttpClient? = null

    private var plainClient: HttpClient? = null

    protected fun buildHttpClient(
        encryptionEnabled: Boolean
    ): HttpClient {
        return HttpClient {
            expectSuccess = true

            defaultRequest {
                url(getEndpointUrl())
                providerOptions.headers?.forEach { (k, v) ->
                    header(k, v)
                }
            }

            // TODO: change for production code
            install(HttpTimeout) {
                requestTimeoutMillis = 120_000   // total request time
                connectTimeoutMillis = 15_000    // TCP connect
                socketTimeoutMillis = 120_000    // read/write stall
            }

            install(OdinErrorPlugin)

            if (encryptionEnabled) {
                install(OdinEncryptionPlugin)
            }

            install(ContentNegotiation) {
                register(
                    ContentType.Application.OctetStream,
                    KotlinxSerializationConverter(OdinSystemSerializer.json)
                )
                json(OdinSystemSerializer.json)
            }

            install(HttpCookies) {
                storage = AcceptAllCookiesStorage()
            }

            install(Logging) {
                logger = Logger.DEFAULT
                level = LogLevel.HEADERS
            }
        }.apply {
            attributes.put(
                OdinEncryptionKeys.Secret,
                getSharedSecret() ?: ByteArray(0)
            )
        }
    }

    // ─────────────────────────────────────────────
    // SHARED CLIENT ACCESS
    // ─────────────────────────────────────────────
    fun client(encrypted: Boolean = true): HttpClient {
        return if (encrypted) {
            encryptedClient ?: buildHttpClient(true).also {
                encryptedClient = it
            }
        } else {
            plainClient ?: buildHttpClient(false).also {
                plainClient = it
            }
        }
    }


    // ─────────────────────────────────────────────
    // LIFECYCLE
    // ─────────────────────────────────────────────

    fun close() {
        encryptedClient?.close()
        plainClient?.close()
        encryptedClient = null
        plainClient = null
    }

    open fun createHttpClient(): HttpClient = createHttpClient(CreateHttpClientOptions())

    open fun createHttpClient(createHttpClientOptions: CreateHttpClientOptions
    ): HttpClient =client(encrypted = !createHttpClientOptions.overrideEncryption)
}



