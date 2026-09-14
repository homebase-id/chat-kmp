package id.homebase.core.email

/**
 * There is no Thunderbird for iPhone or iPad yet, so there is nothing to launch. The setup screen
 * says so and points at the roadmap instead of offering a button that cannot work.
 */
actual fun canLaunchMailClient(client: MailClientDescriptor): Boolean = false

actual suspend fun launchMailClient(client: MailClientDescriptor): Boolean = false
