package id.homebase.agent

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import kotlin.system.exitProcess
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking

private const val USAGE =
    "usage: chat-agent login --profile <p> [--identity <domain>] | read --profile <p> [--conversation <id>] [--limit <n>] | conversations --profile <p> | send --profile <p> [--conversation <id>] [--file <path>] [text] | watch --profile <p> | mcp --profile <p> [--conversation <id>] [--read-only] | brain-test --profile <p>  (global: --verbose)"

private val VALUE_FLAGS = setOf("--profile", "--conversation", "--limit", "--identity", "--attach", "--file")
private const val VERBOSE = "--verbose"
private const val READ_ONLY = "--read-only"
private const val ATTACH_LATEST = "--attach-latest-image"

fun main(args: Array<String>) {
    val options = mutableMapOf<String, String>()
    val positional = mutableListOf<String>()
    var verbose = false
    var readOnly = false
    var attachLatest = false
    val attach = mutableListOf<String>()
    var i = 0
    while (i < args.size) {
        val arg = args[i]
        when {
            arg == VERBOSE -> verbose = true
            arg == READ_ONLY -> readOnly = true
            arg == ATTACH_LATEST -> attachLatest = true
            arg == "--attach" && i + 1 < args.size -> attach += args[++i]
            arg in VALUE_FLAGS && i + 1 < args.size -> options[arg] = args[++i]
            else -> positional += arg
        }
        i++
    }
    val command = positional.firstOrNull()
    val sendText = positional.drop(1).joinToString(" ")
    Logger.setMinSeverity(if (verbose) Severity.Verbose else Severity.Warn)
    val profile = options["--profile"]
    if (command !in setOf("login", "read", "conversations", "send", "watch", "mcp", "brain-test") || profile == null) {
        System.err.println(USAGE)
        exitProcess(2)
    }
    try {
        Profile.select(profile)
        runBlocking {
            when (command) {
                "login" -> login(options["--identity"] ?: prompt("Homebase identity (e.g. me.homebase.id): "))
                "send" -> send(profile, sendText, options["--conversation"]?.let { Uuid.parse(it) }, options["--file"])
                "watch" -> watch(profile, verbose)
                "brain-test" -> brainTest(profile, attach, attachLatest)
                "mcp" -> mcp(profile, options["--conversation"]?.let { Uuid.parse(it) }, readOnly)
                "conversations" -> conversations(profile)
                else -> read(
                    profile,
                    options["--limit"]?.toIntOrNull() ?: 20,
                    options["--conversation"]?.let { Uuid.parse(it) },
                )
            }
        }
        Profile.harden(Profile.dataDir(profile))
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
