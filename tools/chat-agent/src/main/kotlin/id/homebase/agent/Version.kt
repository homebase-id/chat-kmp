package id.homebase.agent

import java.util.Properties

fun versionLine(properties: Properties = loadVersion()): String =
    "chat-agent ${properties.getProperty("sha", "unknown")} built ${properties.getProperty("date", "unknown")}"

private fun loadVersion() = Properties().also { p ->
    object {}.javaClass.getResourceAsStream("/chat-agent-version.properties")?.use { p.load(it) }
}
