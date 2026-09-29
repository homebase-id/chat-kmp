package id.homebase.agent

import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

private const val USAGE =
    "usage: chat-agent login --profile <p> [--identity <domain>] | read --profile <p> [--limit <n>] | send --profile <p> <text> | watch --profile <p>"

fun main(args: Array<String>) {
    val command = args.firstOrNull()
    val rest = args.drop(1)
    val options = rest.chunked(2).associate { it[0] to it.getOrNull(1) }
    val sendText = rest.filterIndexed { i, _ -> i !in profileArgIndexes(rest) }.joinToString(" ")
    val profile = options["--profile"]
    if (command !in setOf("login", "read", "send", "watch") || profile == null) {
        System.err.println(USAGE)
        exitProcess(2)
    }
    try {
        Profile.select(profile)
        runBlocking {
            when (command) {
                "login" -> login(options["--identity"] ?: prompt("Homebase identity (e.g. me.homebase.id): "))
                "send" -> send(profile, sendText)
                "watch" -> watch(profile)
                else -> read(profile, options["--limit"]?.toIntOrNull() ?: 20)
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

private fun profileArgIndexes(args: List<String>): Set<Int> =
    args.indexOf("--profile").let { if (it < 0) emptySet() else setOf(it, it + 1) }
