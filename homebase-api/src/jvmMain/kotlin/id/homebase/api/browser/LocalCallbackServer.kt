package id.homebase.api.browser

import co.touchlab.kermit.Logger
import io.ktor.http.ContentType
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import java.net.ServerSocket

/**
 * Local HTTP server for handling YouAuth callbacks on desktop.
 *
 * JVM ONLY.
 */
object LocalCallbackServer {

    private const val TAG = "LocalCallbackServer"

    private const val START_PORT = 49152
    private const val END_PORT = 65535
    private const val MAX_PORT_ATTEMPTS = 100

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? =
        null
    private var currentPort: Int = 0

    private var onCallbackUrl: ((String) -> Unit)? = null

    fun start(onCallbackUrl: (String) -> Unit, preferredPort: Int = 0): Int {
        this.onCallbackUrl = onCallbackUrl
        if (server != null) return currentPort

        val portsToTry =
            if (preferredPort > 0) listOf(preferredPort) + randomPorts()
            else randomPorts()

        for (port in portsToTry) {
            try {
                server =
                    embeddedServer(CIO, port = port) {
                        routing {

                            /** OAuth callback */
                            get("/authorization-code-callback") {
                                val fullUrl =
                                    "http://localhost:$port${call.request.local.uri}"

                                Logger.d(tag = TAG) { "Received callback: $fullUrl" }
                                LocalCallbackServer.onCallbackUrl?.invoke(fullUrl)

                                call.respondText(
                                    text = CALLBACK_HTML,
                                    contentType = ContentType.Text.Html
                                )
                            }

                            /** Bring desktop app to foreground */
                            get("/focus") {
                                Logger.d(tag = TAG) { "Focus requested from browser" }

                                DesktopAppFocusManager.requestFocus()
                                call.respondText("OK", ContentType.Text.Plain)

                                delay(750)
                                stop()
                            }

                            /** Health check */
                            get("/") {
                                call.respondText(
                                    "OAuth Callback Server running",
                                    ContentType.Text.Plain
                                )
                            }
                        }
                    }.start(wait = false)

                currentPort = port
                Logger.i(tag = TAG) { "Callback server started on port $port" }
                return port
            } catch (_: Exception) {
                server = null
            }
        }

        Logger.e(tag = TAG) { "Failed to start callback server" }
        return -1
    }

    fun stop() {
        server?.stop(1000, 2000)
        server = null
        currentPort = 0
        Logger.i(tag = TAG) { "Callback server stopped" }
    }

    fun isRunning(): Boolean = server != null
    fun getPort(): Int = currentPort

    /* ---------------- helpers ---------------- */

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

    fun setCallBack(onCallbackUrl: (String) -> Unit) {
        this.onCallbackUrl = onCallbackUrl
    }

    /* ---------------- HTML ---------------- */

    private const val CALLBACK_HTML = """
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8"/>
  <title>Homebase Chat — Authentication Complete</title>
  <style>
    body {
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
      background: #ffffff;
      color: #171717;
      display: flex;
      align-items: center;
      justify-content: center;
      height: 100vh;
      margin: 0;
    }
    .box {
      border: 1px solid #eaeaea;
      border-radius: 14px;
      padding: 2.25rem;
      text-align: center;
      max-width: 440px;
      box-shadow: 0 10px 28px rgba(0,0,0,0.06);
    }
    h1 {
      font-size: 1.35rem;
      margin-bottom: 0.75rem;
      font-weight: 600;
    }
    p {
      color: #555;
      margin: 0.5rem 0;
      line-height: 1.5;
    }
    .hint {
      font-size: 0.9rem;
      color: #777;
      margin-top: 1.25rem;
    }
    button {
      margin-top: 1.75rem;
      padding: 0.75rem 1.5rem;
      font-size: 1rem;
      border-radius: 8px;
      border: none;
      background: #171717;
      color: #ffffff;
      cursor: pointer;
    }
    button:hover {
      background: #000000;
    }
  </style>
</head>
<body>
  <div class="box">
    <h1>You're signed in</h1>

    <p>
      Authentication for <strong>Homebase&nbsp;Chat</strong> is complete.
    </p>

    <p>
      You can now continue using the application.
    </p>

    <button onclick="openApp()">Return to Homebase Chat</button>

    <p class="hint">
      You may also close this browser tab if it doesn’t close automatically.
    </p>
  </div>

  <script>
    function openApp() {
      fetch('/focus')
        .finally(() => {
          try { window.close(); } catch (_) {}
        });
    }
  </script>
</body>
</html>
"""
}
