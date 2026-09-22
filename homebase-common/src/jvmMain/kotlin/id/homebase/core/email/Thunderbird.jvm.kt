package id.homebase.core.email

import co.touchlab.kermit.Logger

/**
 * Desktop launches the app's executable. There is no install registry to consult, so "can launch"
 * only means we know a command to try — whether it exists is discovered by trying it.
 */
actual fun canLaunchMailClient(client: MailClientDescriptor): Boolean = commandFor(client) != null

actual suspend fun launchMailClient(client: MailClientDescriptor): Boolean {
    val command = commandFor(client) ?: return false

    return runCatching {
        ProcessBuilder(command).start()
        true
    }.getOrElse { e ->
        // Almost always "not installed": the binary is not on PATH.
        Logger.d(tag = "MailClient", throwable = e) { "Could not start ${client.displayName}" }
        false
    }
}

private fun commandFor(client: MailClientDescriptor): List<String>? {
    val os = System.getProperty("os.name")?.lowercase() ?: return null

    return when {
        os.contains("mac") -> client.macAppName?.let { listOf("open", "-a", it) }
        os.contains("win") -> client.windowsBinary?.let { listOf("cmd", "/c", "start", "", it) }
        else -> client.linuxBinary?.let { listOf(it) }
    }
}
