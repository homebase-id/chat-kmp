package id.homebase.agent

import id.homebase.api.decodeUrl
import java.io.BufferedReader
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.selects.select

class CallbackParams(val identity: String, val publicKey: String, val salt: String)

sealed interface CallbackParse {
    class Ok(val params: CallbackParams) : CallbackParse
    class Bad(val message: String) : CallbackParse
}

private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")

fun parseCallbackInput(raw: String, expectedState: String, expectedIdentity: String? = null): CallbackParse {
    val input = raw.trim().trim('"', '\'', '<', '>').trim()
    if (input.isEmpty()) return CallbackParse.Bad("empty input")
    val withoutFragment = input.substringBefore('#')
    val query =
        when {
            SCHEME.containsMatchIn(withoutFragment) ->
                if ('?' in withoutFragment) withoutFragment.substringAfter('?')
                else return CallbackParse.Bad("that URL has no query string; paste the full address of the page that failed to load")
            '?' in withoutFragment -> withoutFragment.substringAfter('?')
            '=' in withoutFragment -> withoutFragment
            else -> return CallbackParse.Bad("not a callback URL (expected http://localhost:<port>/authorization-code-callback?identity=...&state=...)")
        }
    val params =
        query.split("&").filter { it.isNotEmpty() }.associate {
            val parts = it.split("=", limit = 2)
            parts[0] to decodeUrl(parts.getOrElse(1) { "" })
        }
    params["error"]?.let { return CallbackParse.Bad("the identity server reported an error: $it ${params["error_description"].orEmpty()}".trim()) }
    val missing = listOf("identity", "public_key", "salt", "state").filter { params[it].isNullOrEmpty() }
    if (missing.isNotEmpty()) return CallbackParse.Bad("missing ${missing.joinToString(", ")} in the pasted text")
    if (params["state"] != expectedState) {
        return CallbackParse.Bad("state does not match this login attempt; paste the URL from the approval you started with this command")
    }
    val returned = params.getValue("identity")
    if (expectedIdentity != null && !returned.trim().equals(expectedIdentity.trim(), ignoreCase = true)) {
        return CallbackParse.Bad("approved as $returned but this login was started for $expectedIdentity — sign in as $expectedIdentity in the browser and try again")
    }
    return CallbackParse.Ok(CallbackParams(params.getValue("identity"), params.getValue("public_key"), params.getValue("salt")))
}

suspend fun awaitCallback(
    inputs: ReceiveChannel<String>,
    expectedState: String,
    expectedIdentity: String? = null,
    onError: (String) -> Unit,
): CallbackParams {
    while (true) {
        val raw = select { inputs.onReceiveCatching { it } }.getOrNull()
        if (raw == null) {
            kotlinx.coroutines.awaitCancellation()
        }
        when (val parsed = parseCallbackInput(raw, expectedState, expectedIdentity)) {
            is CallbackParse.Ok -> return parsed.params
            is CallbackParse.Bad -> onError(parsed.message)
        }
    }
}

// EOF just ends the pump (no close): the HTTP callback keeps working when stdin is closed.
fun pumpLines(reader: BufferedReader, sink: (String) -> Unit) {
    while (true) {
        val line = try { reader.readLine() } catch (_: java.io.IOException) { null } ?: return
        if (line.isNotBlank()) sink(line)
    }
}

fun browserOpener(os: String, env: Map<String, String>, noBrowser: Boolean): String? {
    if (noBrowser) return null
    val name = os.lowercase()
    return when {
        "mac" in name -> "open"
        "linux" in name -> if (!env["DISPLAY"].isNullOrBlank() || !env["WAYLAND_DISPLAY"].isNullOrBlank()) "xdg-open" else null
        else -> null
    }
}

fun onPath(binary: String, path: String? = System.getenv("PATH")): Boolean =
    path.orEmpty().split(File.pathSeparator).any { it.isNotEmpty() && File(it, binary).let { f -> f.isFile && f.canExecute() } }

fun terminalQr(url: String): String? {
    if (!onPath("qrencode")) return null
    return runCatching {
        val p = ProcessBuilder("qrencode", "-t", "ANSIUTF8", url).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(3, TimeUnit.SECONDS) || p.exitValue() != 0) null else out
    }.getOrNull()
}
