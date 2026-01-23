package id.homebase.homebasekmppoc.prototype.lib.video

import co.touchlab.kermit.Logger
import id.homebase.homebasekmppoc.prototype.lib.core.SecureByteArray
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteReadChannel

/**
 * Simple HTTP server for serving video content locally
 * Supports both HLS manifests/segments and regular video files
 *
 * This is fully common code - Ktor server works across all platforms!
 * Auth tokens are registered with content and used for proxying remote URLs
 */
class LocalVideoServer {
    private lateinit var server: EmbeddedServer<*, *>
    private lateinit var serverUrl: String
    private val contentRegistry = mutableMapOf<String, ContentData>()
    private val httpClient = HttpClient() // Reusable HTTP client for proxy requests

    private data class ContentData(
        val data: SecureByteArray,
        val contentType: String,
        val authTokenHeaderName: String? = null,
        val authToken: String? = null
    )

    /**
     * Get the server URL (server must be started first)
     */
    fun getServerUrl(): String {
        check(::serverUrl.isInitialized) { "Server not started yet" }
        return serverUrl
    }

    /**
     * Start the server on a random available port
     * Should only be called once
     * @return The URL where the server is accessible
     */
    suspend fun start(): String {
        check(!::server.isInitialized) { "Server already started" }

        server = embeddedServer(CIO, port = 0) {
            routing {
                // Serve registered content by ID with Range request support
                get("/content/{id}") {
                    val id = call.parameters["id"]
                    if (id == null) {
                        call.response.status(HttpStatusCode.BadRequest)
                        return@get
                    }

                    val content = contentRegistry[id]
                    if (content == null) {
                        Logger.w("LocalVideoServer") { "Content not found: $id" }
                        call.response.status(HttpStatusCode.NotFound)
                        return@get
                    }

                    val data = content.data.unsafeBytes
                    val totalSize = data.size.toLong()

                    // Parse Range header (e.g., "bytes=0-1023")
                    val rangeHeader = call.request.headers["Range"]

                    if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                        // Handle range request
                        val rangeSpec = rangeHeader.substringAfter("bytes=")
                        val (startStr, endStr) = rangeSpec.split("-", limit = 2)

                        val start = startStr.toLongOrNull() ?: 0
                        val end = if (endStr.isEmpty()) totalSize - 1 else (endStr.toLongOrNull() ?: totalSize - 1)

                        val actualEnd = minOf(end, totalSize - 1)
                        val length = (actualEnd - start + 1).toInt()

                        Logger.d("LocalVideoServer") { "Range request for $id: bytes=$start-$actualEnd/$totalSize (${content.contentType})" }

                        // Set 206 Partial Content status
                        call.response.status(HttpStatusCode.PartialContent)
                        call.response.headers.append("Content-Range", "bytes $start-$actualEnd/$totalSize")
                        call.response.headers.append("Accept-Ranges", "bytes")
                        call.response.headers.append("Content-Length", length.toString())

                        // Send the requested range
                        call.respondBytes(
                            bytes = data.sliceArray(start.toInt()..actualEnd.toInt()),
                            contentType = ContentType.parse(content.contentType)
                        )
                    } else {
                        // Normal request - send entire content
                        Logger.d("LocalVideoServer") { "Serving full content: $id (${data.size} bytes, ${content.contentType})" }

                        call.response.headers.append("Accept-Ranges", "bytes")
                        call.response.headers.append("Content-Length", totalSize.toString())

                        call.respondBytes(
                            bytes = data,
                            contentType = ContentType.parse(content.contentType)
                        )
                    }
                }

                // Proxy endpoint for remote URLs with auth header
                get("/proxy") {
                    val url = call.request.queryParameters["url"]
                    if (url == null) {
                        Logger.w("LocalVideoServer") { "Proxy request missing url parameter" }
                        call.response.status(HttpStatusCode.BadRequest)
                        return@get
                    }

                    // Get manifest ID to look up the associated auth token from content data
                    val manifestId = call.request.queryParameters["manifestId"]
                    val authTokenHeaderName = manifestId?.let { contentRegistry[it]?.authTokenHeaderName }
                    val authToken = manifestId?.let { contentRegistry[it]?.authToken }

                    try {
                        Logger.i("LocalVideoServer") { "Proxying request to: $url" }

                        val response = httpClient.get(url) {
                            // Forward all headers from the client request
                            call.request.headers.forEach { key, values ->
                                if (key.lowercase() != "host" && key.lowercase() != "accept-encoding") {
                                    values.forEach { value ->
                                        header(key, value)
                                    }
                                }
                            }
                            if (authTokenHeaderName != null && authToken != null) {
                                header(authTokenHeaderName, authToken)
                                Logger.d("LocalVideoServer") { "Added $authTokenHeaderName header to proxy request" }
                            }
                        }

                        Logger.i("LocalVideoServer") { "Streaming response, status: ${response.status}" }

                        // Set response status to match backend (important for 206 Partial Content)
                        call.response.status(response.status)

                        // Copy response headers (including Content-Range for byte range requests)
                        response.headers.forEach { key, values ->
                            // Skip headers that Ktor will set automatically
                            if (key.lowercase() != "content-length" && key.lowercase() != "content-type") {
                                values.forEach { value ->
                                    call.response.headers.append(key, value)
                                }
                            }
                        }

                        // Stream the response body directly (no buffering)
                        val responseChannel: ByteReadChannel = response.bodyAsChannel()
                        val contentTypeString = response.headers.get("Content-Type")
                            ?: "application/octet-stream"

                        // Use custom streaming content
                        call.respond(object : OutgoingContent.ReadChannelContent() {
                            override val contentType = ContentType.parse(contentTypeString)
                            override fun readFrom(): ByteReadChannel = responseChannel
                        })

                        Logger.d("LocalVideoServer") { "Streaming complete for: $url" }
                    } catch (e: Exception) {
                        Logger.e("LocalVideoServer") { "Proxy request failed: ${e.message}" }
                        call.response.status(HttpStatusCode.InternalServerError)
                    }
                }

                // Health check endpoint
                get("/health") {
                    call.respondBytes("OK".encodeToByteArray())
                }
            }
        }.start(wait = false)

        // Get the actual port that was assigned by the system
        val actualPort = server.engine.resolvedConnectors().first().port
        serverUrl = "http://127.0.0.1:$actualPort"
        Logger.i("LocalVideoServer") { "Video server started at $serverUrl" }
        return serverUrl
    }

    /**
     * Stop the server
     */
    fun stop() {
        if (::server.isInitialized) {
            server.stop(1000, 2000)
        }
        contentRegistry.clear()
        httpClient.close()
        Logger.i("LocalVideoServer") { "Video server stopped" }
    }

    /**
     * Register video data that can be served
     */
    fun registerContent(
        id: String,
        data: ByteArray,
        contentType: String,
        authTokenHeaderName: String? = null,
        authToken: String? = null) {
        contentRegistry[id] = ContentData(
            SecureByteArray(data),
            contentType,
            authTokenHeaderName,
            authToken)
        if (authToken != null) {
            Logger.d("LocalVideoServer") { "Registered content: $id (${data.size} bytes, $contentType) with auth token" }
        } else {
            Logger.d("LocalVideoServer") { "Registered content: $id (${data.size} bytes, $contentType)" }
        }
    }

    /**
     * Unregister content to free up memory
     * @param id The content identifier to remove
     */
    fun unregisterContent(id: String) {
        val removed = contentRegistry.remove(id)
        if (removed != null) {
            Logger.d("LocalVideoServer") { "Unregistered content: $id (freed ${removed.data.unsafeBytes.size} bytes)" }
        } else {
            Logger.w("LocalVideoServer") { "Attempted to unregister non-existent content: $id" }
        }
    }

    /**
     * Get the URL for accessing registered content
     * @param id The content identifier
     * @return Full URL to access the content
     */
    fun getContentUrl(id: String): String {
        return "$serverUrl/content/$id"
    }
}
