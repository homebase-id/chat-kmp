package id.homebase.core.email

/**
 * The mail apps we can offer setup steps for, and what each platform needs in order to launch one.
 *
 * There is no universal "open my inbox" link — mailto: composes a new message rather than opening
 * anything — so launching is per-app and per-platform: an Android package name, a macOS app name,
 * a Linux binary, an iOS URL scheme. Each is nullable because most apps exist on some platforms
 * and not others (Thunderbird has no iOS build at all).
 *
 * Anything unknown here simply means the app cannot be launched from us; the setup instructions
 * still work.
 */
data class MailClientDescriptor(
    val id: String,
    val displayName: String,
    /** Where it runs, for the picker's subtitle. */
    val platforms: String,
    /** Android package, used with getLaunchIntentForPackage. */
    val androidPackage: String? = null,
    /** macOS application name for `open -a`. */
    val macAppName: String? = null,
    /** Linux executable on PATH. */
    val linuxBinary: String? = null,
    /** Windows executable, looked up on PATH. */
    val windowsBinary: String? = null,
    /**
     * iOS URL scheme. Unverified for every app here — schemes are undocumented and change, and
     * iOS also requires each one to be declared in LSApplicationQueriesSchemes before it can even
     * be probed. Left null until confirmed on a device rather than guessed.
     */
    val iosScheme: String? = null,
)

object MailClientCatalog {
    /** The generic option: instructions only, nothing to launch. */
    const val OTHER_ID = "other"

    val clients: List<MailClientDescriptor> = listOf(
        MailClientDescriptor(
            id = "thunderbird",
            displayName = "Thunderbird",
            platforms = "Windows, macOS, Linux",
            macAppName = "Thunderbird",
            linuxBinary = "thunderbird",
            windowsBinary = "thunderbird.exe",
            // No iOS build exists.
        ),
        MailClientDescriptor(
            id = "fairemail",
            displayName = "FairEmail",
            platforms = "Android",
            androidPackage = "eu.faircode.email",
        ),
        MailClientDescriptor(
            id = "canary",
            displayName = "Canary Mail",
            platforms = "iOS, macOS",
            androidPackage = "io.canarymail.android",
            macAppName = "Canary Mail",
        ),
        MailClientDescriptor(
            id = OTHER_ID,
            displayName = "Another mail app",
            platforms = "Server settings and your key, step by step",
        ),
    )

    fun byId(id: String?): MailClientDescriptor? = clients.firstOrNull { it.id == id }
}

/**
 * Whether this platform can launch [client] at all — so the UI can offer the button only when it
 * would do something. Not the same as "installed": that is only known at launch time.
 */
expect fun canLaunchMailClient(client: MailClientDescriptor): Boolean

/**
 * Opens the user's chosen mail app. Returns false when it is not installed or cannot be launched,
 * so the caller can say so rather than appearing to do nothing.
 */
expect suspend fun launchMailClient(client: MailClientDescriptor): Boolean
