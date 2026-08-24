package id.homebase.core.email

/**
 * iOS needs each app's URL scheme, and every scheme must also be declared in
 * LSApplicationQueriesSchemes before the system will even answer whether it can be opened.
 *
 * None are filled in yet: the schemes are undocumented and change between versions, so they are
 * left unset rather than guessed. The setup instructions work regardless — this only affects
 * whether we can offer a launch button.
 */
actual fun canLaunchMailClient(client: MailClientDescriptor): Boolean = false

actual suspend fun launchMailClient(client: MailClientDescriptor): Boolean = false
