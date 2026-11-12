package id.homebase.core.contacts

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Website
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeviceContactSaverAndroidTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val contact = DeviceContact(
        displayName = "Ada Vance",
        organization = "Homebase",
        phones = listOf("+14155550123", "+4512345678"),
        emails = listOf("ada@example.com", "ada@work.example.com"),
        website = "https://ada.example.com",
    )

    private fun allowInsert() {
        shadowOf(context as android.app.Application).checkActivities(true)
        val probe = Intent(ContactsContract.Intents.Insert.ACTION).setType(ContactsContract.RawContacts.CONTENT_TYPE)
        shadowOf(context.packageManager).addResolveInfoForIntent(
            probe,
            android.content.pm.ResolveInfo().apply {
                activityInfo = android.content.pm.ActivityInfo().apply {
                    packageName = "com.example.contacts"
                    name = "Editor"
                }
            },
        )
    }

    @Test
    fun opensTheNewContactScreenWithEveryValue() {
        allowInsert()

        assertTrue(AndroidDeviceContactSaver(context).open(contact))

        val intent = shadowOf(context as android.app.Application).nextStartedActivity
        assertEquals(ContactsContract.Intents.Insert.ACTION, intent.action)
        assertEquals(ContactsContract.RawContacts.CONTENT_TYPE, intent.type)
        assertEquals("Ada Vance", intent.getStringExtra(ContactsContract.Intents.Insert.NAME))
        assertEquals("Homebase", intent.getStringExtra(ContactsContract.Intents.Insert.COMPANY))

        @Suppress("DEPRECATION")
        val rows = intent.getParcelableArrayListExtra<ContentValues>(ContactsContract.Intents.Insert.DATA)!!
        assertEquals(5, rows.size)
        assertEquals(
            listOf(Phone.CONTENT_ITEM_TYPE, Phone.CONTENT_ITEM_TYPE, Email.CONTENT_ITEM_TYPE, Email.CONTENT_ITEM_TYPE, Website.CONTENT_ITEM_TYPE),
            rows.map { it.getAsString(ContactsContract.Data.MIMETYPE) },
        )
        assertEquals(listOf("+14155550123", "+4512345678"), rows.take(2).map { it.getAsString(Phone.NUMBER) })
        assertEquals(listOf("ada@example.com", "ada@work.example.com"), rows.subList(2, 4).map { it.getAsString(Email.ADDRESS) })
        assertEquals("https://ada.example.com", rows[4].getAsString(Website.URL))
        assertEquals(Phone.TYPE_MOBILE, rows[0].getAsInteger(Phone.TYPE))
        assertEquals(Email.TYPE_OTHER, rows[2].getAsInteger(Email.TYPE))
        assertEquals(Website.TYPE_HOMEPAGE, rows[4].getAsInteger(Website.TYPE))
    }

    @Test
    fun blankDisplayNameFallsBackToGivenAndSurname() {
        val intent = DeviceContact(displayName = "", givenName = "Ada", surname = "Vance").toInsertIntent()

        assertEquals("Ada Vance", intent.getStringExtra(ContactsContract.Intents.Insert.NAME))
        assertNull(intent.getStringExtra(ContactsContract.Intents.Insert.COMPANY))
    }

    @Test
    fun returnsFalseWhenNoAppCanHandleIt() {
        shadowOf(context as android.app.Application).checkActivities(true)
        assertFalse(AndroidDeviceContactSaver(context).open(contact))
    }
}
