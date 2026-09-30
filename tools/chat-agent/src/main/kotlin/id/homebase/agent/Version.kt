package id.homebase.agent

import java.util.Properties

fun agentVersion(properties: Properties = loadVersion()): String = properties.getProperty("sha", "unknown")

fun versionLine(properties: Properties = loadVersion()): String =
    "chat-agent ${agentVersion(properties)} built ${properties.getProperty("date", "unknown")}"

private fun loadVersion() = Properties().also { p ->
    object {}.javaClass.getResourceAsStream("/chat-agent-version.properties")?.use { p.load(it) }
}
