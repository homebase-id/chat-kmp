package id.homebase.agent

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import kotlin.system.exitProcess
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking

private const val USAGE =
    "usage: chat-agent login --profile <p> [--identity <domain>] | read --profile <p> [--conversation <id>] [--limit <n>] | conversations --profile <p> | send --profile <p> [--conversation <id>] <text> | watch --profile <p> | mcp --profile <p>  (global: --verbose)"

fun main(args: Array<String>) {
    val command = args.firstOrNull()
    val verbose = "--verbose" in args
    val rest = args.drop(1).filter { it != "--verbose" }
    Logger.setMinSeverity(if (verbose) Severity.Verbose else Severity.Warn)
    val options = rest.chunked(2).associate { it[0] to it.getOrNull(1) }
    val sendText = rest.filterIndexed { i, _ -> i !in flagIndexes(rest, "--profile", "--conversation") }.joinToString(" ")
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
                "watch" -> watch(profile)
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

private fun flagIndexes(args: List<String>, vararg flags: String): Set<Int> =
    flags.flatMap { f -> args.indexOf(f).let { if (it < 0) emptyList() else listOf(it, it + 1) } }.toSet()
