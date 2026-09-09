package id.homebase.core.email

/** A browser cannot launch a desktop application. */
actual fun canLaunchMailClient(client: MailClientDescriptor): Boolean = false

actual suspend fun launchMailClient(client: MailClientDescriptor): Boolean = false
