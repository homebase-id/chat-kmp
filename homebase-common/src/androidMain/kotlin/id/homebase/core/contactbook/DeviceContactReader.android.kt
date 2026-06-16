package id.homebase.core.contactbook

import android.provider.ContactsContract
import id.homebase.api.ActivityProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads the device address book via `ContactsContract`. One row per aggregated
 * contact; the primary phone and primary email are taken from the
 * `CommonDataKinds` tables (super-primary first, else first seen). Runs on the
 * IO dispatcher because the cursor walk is slow for large address books.
 *
 * The caller must already hold `READ_CONTACTS`; with the permission missing the
 * query throws a SecurityException, which we let propagate so the import flow
 * surfaces it rather than silently importing nothing.
 */
actual suspend fun readDeviceContacts(): List<DeviceContact> = withContext(Dispatchers.IO) {
    val resolver = ActivityProvider.requireApplicationContext().contentResolver

    // Pass 1 — phones keyed by contactId (super-primary wins, else first).
    val phones = HashMap<Long, String>()
    resolver.query(
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY,
        ),
        null, null, null,
    )?.use { c ->
        val idIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
        val numIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
        val primIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY)
        while (c.moveToNext()) {
            val id = c.getLong(idIdx)
            val number = c.getString(numIdx)?.trim().orEmpty()
            if (number.isEmpty()) continue
            val isSuper = primIdx >= 0 && c.getInt(primIdx) == 1
            if (isSuper || !phones.containsKey(id)) phones[id] = number
        }
    }

    // Pass 2 — emails keyed by contactId.
    val emails = HashMap<Long, String>()
    resolver.query(
        ContactsContract.CommonDataKinds.Email.CONTENT_URI,
        arrayOf(
            ContactsContract.CommonDataKinds.Email.CONTACT_ID,
            ContactsContract.CommonDataKinds.Email.ADDRESS,
            ContactsContract.CommonDataKinds.Email.IS_SUPER_PRIMARY,
        ),
        null, null, null,
    )?.use { c ->
        val idIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Email.CONTACT_ID)
        val addrIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)
        val primIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Email.IS_SUPER_PRIMARY)
        while (c.moveToNext()) {
            val id = c.getLong(idIdx)
            val addr = c.getString(addrIdx)?.trim().orEmpty()
            if (addr.isEmpty()) continue
            val isSuper = primIdx >= 0 && c.getInt(primIdx) == 1
            if (isSuper || !emails.containsKey(id)) emails[id] = addr
        }
    }

    // Pass 3 — structured name per contact (display + given/family).
    val results = ArrayList<DeviceContact>()
    resolver.query(
        ContactsContract.Contacts.CONTENT_URI,
        arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
        ),
        null, null,
        ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " ASC",
    )?.use { c ->
        val idIdx = c.getColumnIndex(ContactsContract.Contacts._ID)
        val nameIdx = c.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
        while (c.moveToNext()) {
            val id = c.getLong(idIdx)
            val display = c.getString(nameIdx)?.trim().orEmpty()
            val phone = phones[id]
            val email = emails[id]
            if (display.isEmpty() && phone == null && email == null) continue

            val (given, surname) = splitName(display)
            results += DeviceContact(
                displayName = display.ifEmpty { phone ?: email.orEmpty() },
                givenName = given,
                surname = surname,
                phone = phone,
                email = email,
            )
        }
    }

    results
}

private fun splitName(display: String): Pair<String?, String?> {
    val tokens = display.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        tokens.size >= 2 -> tokens.first() to tokens.last()
        tokens.size == 1 -> tokens.first() to null
        else -> null to null
    }
}

actual fun isDeviceContactsSupported(): Boolean = true
