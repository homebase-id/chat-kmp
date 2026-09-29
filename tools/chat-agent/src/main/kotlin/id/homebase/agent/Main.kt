package id.homebase.agent

import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

private const val USAGE =
    "usage: chat-agent login --profile <p> [--identity <domain>] | read --profile <p> [--limit <n>]"

fun main(args: Array<String>) {
    val command = args.firstOrNull()
    val options = args.drop(1).chunked(2).associate { it[0] to it.getOrNull(1) }
    val profile = options["--profile"]
    if (command !in setOf("login", "read") || profile == null) {
        System.err.println(USAGE)
        exitProcess(2)
    }
    try {
        Profile.select(profile)
        runBlocking {
            when (command) {
                "login" -> login(options["--identity"] ?: prompt("Homebase identity (e.g. me.homebase.id): "))
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
