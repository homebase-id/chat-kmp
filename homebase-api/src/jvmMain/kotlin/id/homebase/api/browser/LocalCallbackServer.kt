package id.homebase.api.browser

import co.touchlab.kermit.Logger
import id.homebase.api.youauth.YouAuthFlowManager
import io.ktor.http.ContentType
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.net.ServerSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Local HTTP server for handling YouAuth callbacks on desktop.
 */
object LocalCallbackServer {

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var currentPort: Int = 0

    private const val START_PORT = 49152
    private const val END_PORT = 65535
    private const val MAX_PORT_ATTEMPTS = 100

    fun start(scope: CoroutineScope, preferredPort: Int = 0): Int {
        if (server != null) return currentPort

        val portsToTry =
            if (preferredPort > 0) {
                listOf(preferredPort) + randomPorts()
            } else {
                randomPorts()
            }

        for (port in portsToTry) {
            try {
                server = embeddedServer(CIO, port = port) {
                    routing {
                        get("/authorization-code-callback") {
                            val fullUrl =
                                "http://localhost:$currentPort${call.request.local.uri}"

                            Logger.d("LocalCallbackServer") {
                                "Received callback: $fullUrl"
                            }

                            scope.launch {
                                try {
                                    YouAuthFlowManager.handleCallback(fullUrl)
                                } catch (e: Exception) {
                                    Logger.e("LocalCallbackServer") {
                                        "Callback error: ${e.message}"
                                    }
                                }
                            }

                            call.respondText(
                                text = CALLBACK_HTML,
                                contentType = ContentType.Text.Html
                            )

                            scope.launch {
                                delay(1000)
                                stop()
                            }
                        }

                        get("/") {
                            call.respondText(
                                "OAuth Callback Server running",
                                ContentType.Text.Plain
                            )
                        }
                    }
                }.start(wait = false)

                currentPort = port
                return currentPort

            } catch (_: Exception) {
                server = null
            }
        }

        return -1
    }

    fun stop() {
        server?.stop(1000, 2000)
        server = null
        currentPort = 0
    }

    fun isRunning(): Boolean = server != null
    fun getPort(): Int = currentPort

    private fun randomPorts(): List<Int> =
        (0 until MAX_PORT_ATTEMPTS).map {
            START_PORT + (Math.random() * (END_PORT - START_PORT)).toInt()
        }

    fun findAvailablePort(): Int {
        repeat(MAX_PORT_ATTEMPTS) {
            val port =
                START_PORT + (Math.random() * (END_PORT - START_PORT)).toInt()
            try {
                ServerSocket(port).use { return port }
            } catch (_: Exception) {
            }
        }
        return -1
    }

    private const val CALLBACK_HTML = """
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8"/>
  <title>Authentication Complete</title>
  <style>
    body {
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
      background: #fff;
      color: #171717;
      display: flex;
      align-items: center;
      justify-content: center;
      height: 100vh;
      margin: 0;
    }
    .box {
      border: 1px solid #eaeaea;
      border-radius: 12px;
      padding: 2rem;
      text-align: center;
      max-width: 400px;
    }
    h1 {
      font-size: 1.25rem;
      margin-bottom: 0.5rem;
    }
    p {
      color: #666;
    }
  </style>
</head>
<body>
  <div class="box">
    <h1>Authentication Complete</h1>
    <p>You can close this window now.</p>
  </div>
</body>
</html>
"""
}
