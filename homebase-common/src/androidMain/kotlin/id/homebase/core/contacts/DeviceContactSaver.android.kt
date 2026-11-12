package id.homebase.core.contacts

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Website
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberDeviceContactSaver(): DeviceContactSaver? {
    val context = LocalContext.current
    return remember(context) { AndroidDeviceContactSaver(context) }
}

internal class AndroidDeviceContactSaver(private val context: Context) : DeviceContactSaver {
    override fun open(contact: DeviceContact): Boolean {
        val intent = contact.toInsertIntent()
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}

internal fun DeviceContact.toInsertIntent(): Intent {
    val rows = ArrayList<ContentValues>()
    phones.forEach { number ->
        rows += ContentValues().apply {
            put(ContactsContract.Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
            put(Phone.NUMBER, number)
            put(Phone.TYPE, Phone.TYPE_MOBILE)
        }
    }
    emails.forEach { address ->
        rows += ContentValues().apply {
            put(ContactsContract.Data.MIMETYPE, Email.CONTENT_ITEM_TYPE)
            put(Email.ADDRESS, address)
            put(Email.TYPE, Email.TYPE_OTHER)
        }
    }
    if (website.isNotBlank()) {
        rows += ContentValues().apply {
            put(ContactsContract.Data.MIMETYPE, Website.CONTENT_ITEM_TYPE)
            put(Website.URL, website)
            put(Website.TYPE, Website.TYPE_HOMEPAGE)
        }
    }
    return Intent(ContactsContract.Intents.Insert.ACTION).apply {
        type = ContactsContract.RawContacts.CONTENT_TYPE
        putExtra(ContactsContract.Intents.Insert.NAME, displayName.ifBlank { "$givenName $surname".trim() })
        if (organization.isNotBlank()) putExtra(ContactsContract.Intents.Insert.COMPANY, organization)
        putParcelableArrayListExtra(ContactsContract.Intents.Insert.DATA, rows)
    }
}
