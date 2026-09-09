package id.homebase.core.email

import id.homebase.api.isAndroid
import id.homebase.api.isIos
import id.homebase.core.util.Platform
import id.homebase.core.util.isWeb

/**
 * What each platform needs in order to launch a mail app. Every field is nullable because there is
 * no universal "open my inbox" link — mailto: composes a new message rather than opening anything —
 * so launching is per-platform: an Android package name, a macOS app name, a binary on PATH.
 *
 * iOS is absent on purpose: there is no Thunderbird for iPhone or iPad yet.
 */
data class MailClientDescriptor(
    val displayName: String,
    /** Android package, used with getLaunchIntentForPackage. Needs a <queries> entry to be visible. */
    val androidPackage: String? = null,
    /** macOS application name for `open -a`. */
    val macAppName: String? = null,
    /** Linux executable on PATH. */
    val linuxBinary: String? = null,
    /** Windows executable, looked up on PATH. */
    val windowsBinary: String? = null,
)

/**
 * The one mail app we give setup instructions for.
 *
 * It is the only client whose OpenPGP support we have actually tested against this server's
 * encrypt-to-key delivery; a picker of half-working alternatives cost people evenings.
 */
object Thunderbird {
    val client = MailClientDescriptor(
        displayName = "Thunderbird",
        androidPackage = "net.thunderbird.android",
        macAppName = "Thunderbird",
        linuxBinary = "thunderbird",
        windowsBinary = "thunderbird.exe",
    )

    /** Locale-redirecting landing page; it offers the installer for whatever the browser is on. */
    const val DESKTOP_DOWNLOAD_URL = "https://www.thunderbird.net/download/"
    const val WINDOWS_STORE_URL = "https://apps.microsoft.com/detail/9pm5vm1s3vmq"
    const val FLATHUB_URL = "https://flathub.org/apps/org.mozilla.Thunderbird"
    const val PLAY_URL = "https://play.google.com/store/apps/details?id=net.thunderbird.android"
    const val FDROID_URL = "https://f-droid.org/packages/net.thunderbird.android/"
    const val IOS_ROADMAP_URL = "https://roadmaps.thunderbird.net/en-US/ios/"

    /** Thunderbird for Android delegates every OpenPGP operation to this app. */
    const val OPENKEYCHAIN_PLAY_URL =
        "https://play.google.com/store/apps/details?id=org.sufficientlysecure.keychain"
    const val OPENKEYCHAIN_FDROID_URL =
        "https://f-droid.org/packages/org.sufficientlysecure.keychain/"
}

/**
 * Which set of Thunderbird instructions and download links applies here. Desktop is split by OS
 * because the store links differ, even though the download page detects the OS itself.
 */
enum class MailSetupPlatform {
    ANDROID,
    IOS,
    WINDOWS,
    MACOS,
    LINUX,
    /** A browser: Thunderbird still has to be installed on the computer running it. */
    WEB,
}

fun currentMailSetupPlatform(): MailSetupPlatform = when {
    isAndroid() -> MailSetupPlatform.ANDROID
    isIos() -> MailSetupPlatform.IOS
    isWeb() -> MailSetupPlatform.WEB
    else -> {
        val os = Platform.osName.lowercase()
        when {
            os.contains("win") -> MailSetupPlatform.WINDOWS
            os.contains("mac") || os.contains("darwin") -> MailSetupPlatform.MACOS
            else -> MailSetupPlatform.LINUX
        }
    }
}

/**
 * Whether this platform can launch [client] at all — so the UI can offer the button only when it
 * would do something. Not the same as "installed": that is only known at launch time.
 */
expect fun canLaunchMailClient(client: MailClientDescriptor): Boolean

/**
 * Opens the user's mail app. Returns false when it is not installed or cannot be launched, so the
 * caller can say so rather than appearing to do nothing.
 */
expect suspend fun launchMailClient(client: MailClientDescriptor): Boolean
