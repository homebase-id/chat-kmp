package id.homebase.api.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/** The desktop client on CIO over TLS, against a local server that counts the TCP connections it accepts. */
class CioConnectionReuseTest {

    // Ignored until CIO reuses connections (#2671).
    @Ignore
    @Test
    fun fiveSequentialPostsShareOneTcpConnection() {
        val keyStore = selfSignedKeyStore()
        val server = TlsServer(keyStore)
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore) }.trustManagers.filterIsInstance<X509TrustManager>().first()
        val client = HttpClient(CIO) {
            homebaseDefaults()
            engine { https { trustManager = trust } }
        }
        try {
            runBlocking {
                repeat(5) {
                    client.post("https://localhost:${server.port}/api/v2/relay") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"blob":"$it"}""")
                    }
                }
            }
            assertEquals(1, server.accepted.get(), "TCP connections for 5 sequential POSTs; requests:\n${server.requests.joinToString("\n")}")
        } finally {
            client.close()
            server.close()
        }
    }

    private fun selfSignedKeyStore(): KeyStore {
        val file = File.createTempFile("tls-test", ".p12").apply { delete(); deleteOnExit() }
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        val exit = ProcessBuilder(
            keytool, "-genkeypair", "-alias", "localhost", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
            "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
            "-storetype", "PKCS12", "-keystore", file.path, "-storepass", PASSWORD, "-keypass", PASSWORD,
        ).redirectErrorStream(true).start().run { inputStream.readBytes(); waitFor() }
        check(exit == 0) { "keytool exited $exit" }
        return KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, PASSWORD.toCharArray()) } }
    }

    private class TlsServer(keyStore: KeyStore) {
        val accepted = AtomicInteger()
        val requests = CopyOnWriteArrayList<String>()
        private val socket: SSLServerSocket
        val port: Int get() = socket.localPort

        init {
            val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, PASSWORD.toCharArray()) }
            val context = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
            socket = context.serverSocketFactory.createServerSocket(0, 50, InetAddress.getLoopbackAddress()) as SSLServerSocket
            thread(isDaemon = true, name = "tls-test-accept") {
                while (!socket.isClosed) {
                    val connection = runCatching { socket.accept() }.getOrNull() ?: break
                    accepted.incrementAndGet()
                    thread(isDaemon = true, name = "tls-test-connection") { serve(connection) }
                }
            }
        }

        // Answers every request on the connection and keeps it open; HTTP/1.1 is keep-alive unless a side says close.
        private fun serve(connection: Socket) = runCatching {
            connection.use {
                val input = it.getInputStream()
                val output = it.getOutputStream()
                while (true) {
                    val headers = readHeaders(input) ?: break
                    requests += headers.trim().replace("\r\n", " | ")
                    val length = headers.lineSequence()
                        .firstOrNull { line -> line.startsWith("content-length:", ignoreCase = true) }
                        ?.substringAfter(':')?.trim()?.toInt() ?: 0
                    input.readNBytes(length)
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}".encodeToByteArray())
                    output.flush()
                }
            }
        }

        private fun readHeaders(input: InputStream): String? {
            val bytes = StringBuilder()
            while (!bytes.endsWith("\r\n\r\n")) {
                val b = input.read()
                if (b < 0) return null
                bytes.append(b.toChar())
            }
            return bytes.toString()
        }

        fun close() = socket.close()
    }

    private companion object {
        const val PASSWORD = "changeit"
    }
}
