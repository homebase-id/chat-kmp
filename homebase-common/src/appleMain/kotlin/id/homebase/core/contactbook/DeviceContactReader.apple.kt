package id.homebase.core.contactbook

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Contacts.CNContact
import platform.Contacts.CNContactEmailAddressesKey
import platform.Contacts.CNContactFamilyNameKey
import platform.Contacts.CNContactFetchRequest
import platform.Contacts.CNContactGivenNameKey
import platform.Contacts.CNContactPhoneNumbersKey
import platform.Contacts.CNContactStore
import platform.Contacts.CNLabeledValue
import platform.Contacts.CNPhoneNumber
import platform.Foundation.NSString

/**
 * Reads the device address book via `CNContactStore`. Enumerates every contact
 * once and flattens each to a [DeviceContact], taking the first phone / first
 * email as the primary. The caller must already hold contacts authorization;
 * `enumerateContactsWithFetchRequest` throws into its `error` out-param when the
 * permission is missing, which we surface as an empty list (the import flow gates
 * on the permission result before calling, so this is the belt-and-suspenders case).
 */
@OptIn(ExperimentalForeignApi::class)
actual suspend fun readDeviceContacts(): List<DeviceContact> = withContext(Dispatchers.Default) {
    val store = CNContactStore()
    val keys = listOf(
        CNContactGivenNameKey,
        CNContactFamilyNameKey,
        CNContactPhoneNumbersKey,
        CNContactEmailAddressesKey,
    )
    val request = CNContactFetchRequest(keysToFetch = keys)

    val results = ArrayList<DeviceContact>()
    runCatching {
        store.enumerateContactsWithFetchRequest(request, error = null) { contact, _ ->
            contact?.let { results += it.toDeviceContact() }
        }
    }
    results
}

@OptIn(ExperimentalForeignApi::class)
private fun CNContact.toDeviceContact(): DeviceContact {
    val given = givenName.trim().ifEmpty { null }
    val surname = familyName.trim().ifEmpty { null }
    val display = listOfNotNull(given, surname).joinToString(" ").trim()

    val phone = (phoneNumbers.firstOrNull() as? CNLabeledValue)
        ?.value?.let { it as? CNPhoneNumber }
        ?.stringValue?.trim()?.ifEmpty { null }

    val email = (emailAddresses.firstOrNull() as? CNLabeledValue)
        ?.value?.let { it as? NSString }
        ?.toString()?.trim()?.ifEmpty { null }

    return DeviceContact(
        displayName = display.ifEmpty { phone ?: email.orEmpty() },
        givenName = given,
        surname = surname,
        phone = phone,
        email = email,
    )
}

actual fun isDeviceContactsSupported(): Boolean = true
