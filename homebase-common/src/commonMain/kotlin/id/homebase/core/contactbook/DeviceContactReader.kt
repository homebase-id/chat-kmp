package id.homebase.core.contactbook

/**
 * A single entry read from the device's native address book, flattened to the
 * fields the contact-book importer keeps. Multi-valued phones/emails are
 * collapsed to a single primary value at read time (see the v1 import decision:
 * "pick primary, drop rest") so the model stays aligned with the server's
 * single-phone / single-email contact shape.
 */
data class DeviceContact(
    val displayName: String,
    val givenName: String? = null,
    val surname: String? = null,
    /** Primary phone number, if the device entry has one. */
    val phone: String? = null,
    /** Primary email address, if the device entry has one. */
    val email: String? = null,
) {
    /** A device entry with neither a phone nor an email carries nothing worth importing. */
    val hasContactPoint: Boolean get() = !phone.isNullOrBlank() || !email.isNullOrBlank()
}

/**
 * Reads the device address book. Mobile-only: the Android actual queries
 * `ContactsContract`, the iOS actual enumerates `CNContactStore`. Desktop (JVM)
 * and Web have no address book and return an empty list.
 *
 * The caller must hold [id.homebase.core.permissions.PermissionType.CONTACTS]
 * before calling — implementations do not request it. Runs on a background
 * dispatcher inside the actual; large address books are slow to enumerate.
 */
expect suspend fun readDeviceContacts(): List<DeviceContact>

/**
 * Whether the current platform exposes a device address book at all. Used by the
 * UI to hide the "Import contacts" entry point on desktop/web.
 */
expect fun isDeviceContactsSupported(): Boolean
