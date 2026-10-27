package id.homebase.api.storage

import id.homebase.api.share.NotificationExtensionStore
import platform.Foundation.NSUserDefaults
import platform.Security.errSecInteractionNotAllowed

actual object SecureStorage {
    private const val SERVICE_NAME = "id.homebase.api.securestorage"

    /**
     * Marker written to NSUserDefaults on first use. UserDefaults lives in the app container and
     * IS destroyed when the app is deleted; the Keychain is NOT. Its absence therefore means
     * "this install has never run before".
     */
    private const val INSTALL_MARKER = "id.homebase.api.securestorage.installed"

    private val keychain = KeychainStore(SERVICE_NAME)

    init {
        purgeKeychainIfFreshInstall()
    }

    /**
     * Drop Keychain state left behind by a previous install.
     *
     * iOS deliberately preserves Keychain items across app deletion, which for us is actively
     * harmful: this service holds both the YouAuth credentials and the SQLCipher key
     * ([id.homebase.api.sync.database.DatabaseKeyManager]), while the database file itself lives
     * in the app container and IS deleted. A reinstall would otherwise restore a session for an
     * identity whose local data is gone, and hand a brand-new empty database the key of the old
     * one — a mismatch that makes every subsequent DB read throw.
     *
     * Keeping this in `init` means it runs exactly once, on the first touch of this object,
     * before any get/put can observe stale values — no startup-ordering wiring required.
     */
    private fun purgeKeychainIfFreshInstall() {
        val defaults = NSUserDefaults.standardUserDefaults
        if (defaults.boolForKey(INSTALL_MARKER)) return
        // Before the first unlock UserDefaults reads as empty too, which would look like a fresh install.
        if (keychain.read(key = null).status == errSecInteractionNotAllowed) return

        clear()
        NotificationExtensionStore.clearCredentials()
        defaults.setBool(true, INSTALL_MARKER)
    }

    actual fun put(key: String, value: String) = keychain.put(key, value)

    actual fun get(key: String): String? = keychain.get(key)

    /** [get] with the OSStatus kept, so errSecItemNotFound can be told apart from a locked keychain. */
    internal fun readKeychain(key: String): KeychainStore.Read = keychain.read(key)

    // Never Unavailable: on iOS the passphrase only switches encryption on; KeychainRawKeyStore reads the raw key's OSStatus.
    actual fun read(key: String): SecureRead =
        get(key).toSecureRead()

    actual fun remove(key: String) = keychain.delete(key)

    actual fun contains(key: String): Boolean = get(key) != null

    actual fun clear() = keychain.clear()
}
