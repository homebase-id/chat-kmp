package id.homebase.agent

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import kotlin.system.exitProcess
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking

private const val USAGE =
    "usage: chat-agent login --profile <p> [--identity <domain>] | read --profile <p> [--conversation <id>] [--limit <n>] | conversations --profile <p> | send --profile <p> [--conversation <id>] <text> | watch --profile <p> | mcp --profile <p>  (global: --verbose)"

private val VALUE_FLAGS = setOf("--profile", "--conversation", "--limit", "--identity")
private const val VERBOSE = "--verbose"

fun main(args: Array<String>) {
    val options = mutableMapOf<String, String>()
    val positional = mutableListOf<String>()
    var verbose = false
    var i = 0
    while (i < args.size) {
        val arg = args[i]
        when {
            arg == VERBOSE -> verbose = true
            arg in VALUE_FLAGS && i + 1 < args.size -> options[arg] = args[++i]
            else -> positional += arg
        }
        i++
    }
    val command = positional.firstOrNull()
    val sendText = positional.drop(1).joinToString(" ")
    Logger.setMinSeverity(if (verbose) Severity.Verbose else Severity.Warn)
    val profile = options["--profile"]
    if (command !in setOf("login", "read", "conversations", "send", "watch", "mcp") || profile == null) {
        System.err.println(USAGE)
        exitProcess(2)
    }
    try {
        Profile.select(profile)
        runBlocking {
            when (command) {
                "login" -> login(options["--identity"] ?: prompt("Homebase identity (e.g. me.homebase.id): "))
                "send" -> send(profile, sendText, options["--conversation"]?.let { Uuid.parse(it) })
                "watch" -> watch(profile, verbose)
                "mcp" -> mcp(profile)
                "conversations" -> conversations(profile)
                else -> read(
                    profile,
                    options["--limit"]?.toIntOrNull() ?: 20,
                    options["--conversation"]?.let { Uuid.parse(it) },
                )
            }
        }
    } catch (e: Exception) {
        System.err.println("error: ${e.message}")
        exitProcess(1)
    }
    exitProcess(0)
}

private fun prompt(question: String): String {
    print(question)
    return readlnOrNull()?.trim().orEmpty().ifEmpty { error("identity required") }
}
