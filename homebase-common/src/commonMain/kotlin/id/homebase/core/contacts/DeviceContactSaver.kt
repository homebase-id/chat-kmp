package id.homebase.core.contacts

import androidx.compose.runtime.Composable

/** A contact to hand to the platform's own "new contact" screen. Values are shown to the user as is. */
data class DeviceContact(
    val displayName: String,
    val givenName: String = "",
    val surname: String = "",
    val organization: String = "",
    val phones: List<String> = emptyList(),
    val emails: List<String> = emptyList(),
    val website: String = "",
)

/**
 * Opens the platform's own "new contact" screen pre-filled with [DeviceContact]; the user picks the
 * account and saves there, so the app needs no contacts permission and never writes the address book.
 * Returns false when no app could take the request (no contacts app installed).
 */
fun interface DeviceContactSaver {
    fun open(contact: DeviceContact): Boolean
}

/** Null where the platform has no address book the app can hand a contact to; callers then hide the option. */
@Composable
expect fun rememberDeviceContactSaver(): DeviceContactSaver?
