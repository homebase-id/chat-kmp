@file:OptIn(ExperimentalEncodingApi::class)

package id.homebase.chat.widget.video

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.DriveFileProvider
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import io.ktor.server.routing.get
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random
import kotlin.uuid.Uuid

/**
 * Localhost HTTP server that fronts encrypted HLS video for AVPlayer on iOS.
 *
 * Why this exists: AVPlayer's HLS engine is much stricter than ExoPlayer's about
 * custom URL schemes and resource-loader-served byteranges. The simplest reliable
 * path is to hand AVPlayer a real `http://127.0.0.1:<port>/...` URL pointing at a
 * playlist that conforms to standard HLS-AES-128 — i.e. `#EXT-X-KEY` is left intact
 * with the AES key embedded as a `data:` URI, and segment URIs point back at this
 * server. AVPlayer then fetches segments via HTTP Range and decrypts them itself
 * using stock HLS machinery. We do **zero crypto** here; this server only proxies
 * encrypted ciphertext from the existing payload cache.
 *
 * Lifetime: a single shared instance per process. Bound to `127.0.0.1` so it is
 * not reachable off-device. Sessions are keyed by an opaque UUID that the player
 * surface registers on play and unregisters on dispose — without a valid session
 * id even another app on the device cannot fetch anything useful.
 */
class LocalVideoServer private constructor() {

    private data class Session(
        val driveFileProvider: DriveFileProvider,
        val driveId: Uuid,
        val fileId: Uuid,
        val payloadKey: String,
        val keyHeader: KeyHeader,
        val originalPlaylist: String,
        val totalFileSize: Long,
    )

    var port: Int = 0
        private set

    private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val sessionsMutex = Mutex()
    private val sessions = mutableMapOf<String, Session>()

    suspend fun register(
        driveFileProvider: DriveFileProvider,
        driveId: Uuid,
        fileId: Uuid,
        payloadKey: String,
        keyHeader: KeyHeader,
        originalPlaylist: String,
        totalFileSize: Long,
    ): String {
        val id = Uuid.random().toString()
        sessionsMutex.withLock {
            sessions[id] = Session(
                driveFileProvider, driveId, fileId, payloadKey,
                keyHeader, originalPlaylist, totalFileSize,
            )
        }
        Logger.d(tag = TAG) { "session registered id=$id fileId=$fileId" }
        return id
    }

    suspend fun unregister(id: String) {
        sessionsMutex.withLock { sessions.remove(id) }
        Logger.d(tag = TAG) { "session unregistered id=$id" }
    }

    fun manifestUrl(id: String): String = "http://127.0.0.1:$port/manifest?id=$id"

    private suspend fun lookup(id: String?): Session? =
        if (id.isNullOrEmpty()) null else sessionsMutex.withLock { sessions[id] }

    private fun rewritePlaylist(s: Session, sessionId: String): String {
        val keyB64 = Base64.encode(s.keyHeader.aesKey.unsafeBytes)
        val keyDataUri = "data:application/octet-stream;base64,$keyB64"
        val segmentUrl = "http://127.0.0.1:$port/segment?id=$sessionId"
        val keyUriRegex = Regex("""URI="[^"]*"""")
        return s.originalPlaylist.lineSequence().map { line ->
            when {
                line.startsWith("#EXT-X-KEY") ->
                    keyUriRegex.replace(line, "URI=\"$keyDataUri\"")
                line.isNotBlank() && !line.startsWith("#") -> segmentUrl
                else -> line
            }
        }.joinToString("\n")
    }

    private suspend fun handleManifest(call: ApplicationCall) {
        val sessionId = call.request.queryParameters["id"]
        val s = lookup(sessionId)
        if (s == null || sessionId == null) {
            call.respond(HttpStatusCode.NotFound)
            return
        }
        val body = rewritePlaylist(s, sessionId)
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respondText(body, ContentType("application", "vnd.apple.mpegurl"))
    }

    private suspend fun handleSegment(call: ApplicationCall) {
        val sessionId = call.request.queryParameters["id"]
        val s = lookup(sessionId) ?: return call.respond(HttpStatusCode.NotFound)

        val total = s.totalFileSize
        val (start, endInclusive) = parseRange(call.request.header(HttpHeaders.Range), total)
        val length = endInclusive - start + 1
        if (start < 0 || endInclusive >= total || length <= 0) {
            call.respond(HttpStatusCode.RequestedRangeNotSatisfiable)
            return
        }

        Logger.d(tag = TAG) {
            "segment range start=$start end=$endInclusive length=$length total=$total fileId=${s.fileId}"
        }

        val bytes = try {
            s.driveFileProvider.getPayloadBytesEncryptedChunk(
                driveId = s.driveId,
                fileId = s.fileId,
                key = s.payloadKey,
                chunkStart = start,
                chunkLength = length,
            )
        } catch (e: Exception) {
            Logger.e(throwable = e, tag = TAG) { "segment fetch failed: ${e.message}" }
            null
        }

        if (bytes == null) {
            call.respond(HttpStatusCode.BadGateway)
            return
        }

        call.response.header(HttpHeaders.AcceptRanges, "bytes")
        call.response.header(HttpHeaders.ContentRange, "bytes $start-$endInclusive/$total")
        call.respondBytes(
            bytes = bytes,
            contentType = ContentType("video", "mp2t"),
            status = HttpStatusCode.PartialContent,
        )
    }

    private fun startOnPort(port: Int) {
        engine = embeddedServer(CIO, port = port, host = "127.0.0.1") {
            routing {
                get("/manifest") { handleManifest(call) }
                get("/segment") { handleSegment(call) }
            }
        }.also { it.start(wait = false) }
        this.port = port
    }

    companion object {
        private const val TAG = "LocalVideoServer"

        // Ephemeral / dynamic range. Same convention as the desktop callback server.
        private const val START_PORT = 49152
        private const val END_PORT = 65535
        private const val MAX_PORT_ATTEMPTS = 100

        @Volatile private var instance: LocalVideoServer? = null
        private val startMutex = Mutex()

        suspend fun shared(): LocalVideoServer {
            instance?.let { return it }
            startMutex.withLock {
                instance?.let { return it }
                val s = LocalVideoServer()
                var lastError: Throwable? = null
                repeat(MAX_PORT_ATTEMPTS) {
                    val candidate = START_PORT + Random.nextInt(END_PORT - START_PORT)
                    try {
                        s.startOnPort(candidate)
                        Logger.i(tag = TAG) { "local video server listening on 127.0.0.1:$candidate" }
                        instance = s
                        return s
                    } catch (e: Throwable) {
                        lastError = e
                    }
                }
                error("LocalVideoServer failed to bind after $MAX_PORT_ATTEMPTS attempts: ${lastError?.message}")
            }
        }

        private fun parseRange(header: String?, total: Long): Pair<Long, Long> {
            // AVPlayer sends `bytes=a-b` or `bytes=a-`. Multi-range (comma-separated) is
            // legal HTTP but never observed from HLS clients — fall through on the first
            // form regardless. No header → full content.
            if (header == null) return 0L to (total - 1)
            val spec = header.removePrefix("bytes=").substringBefore(",")
            val dash = spec.indexOf('-')
            if (dash < 0) return 0L to (total - 1)
            val start = spec.substring(0, dash).toLongOrNull() ?: 0L
            val tail = spec.substring(dash + 1)
            val end = if (tail.isBlank()) total - 1 else tail.toLongOrNull() ?: (total - 1)
            return start to end
        }
    }
}
