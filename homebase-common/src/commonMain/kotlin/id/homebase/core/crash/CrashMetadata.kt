package id.homebase.core.crash

/**
 * Static, platform-supplied crash context captured at [CrashReporting.install] time.
 * Built from values cheaply available before Koin/DB (PackageManager / NSBundle /
 * system properties) so it is valid even for an init-time crash.
 */
data class CrashMetadata(
    val appVersion: String,
    val buildType: String,
    val platform: String,
    val device: String,
    val buildTime: String,
)
