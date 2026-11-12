package id.homebase.core.contacts

import platform.Contacts.CNLabelOther
import platform.Contacts.CNLabelPhoneNumberMobile
import platform.Contacts.CNLabelURLAddressHomePage
import platform.Contacts.CNLabeledValue
import platform.Contacts.CNPhoneNumber
import kotlin.test.Test
import kotlin.test.assertEquals

class DeviceContactCNContactTest {

    @Test
    fun structuredNamesAndOrganizationCarryOver() {
        val c = DeviceContact(displayName = "Ada Vance", givenName = "Ada", surname = "Vance", organization = "Homebase").toCNContact()
        assertEquals("Ada", c.givenName)
        assertEquals("Vance", c.familyName)
        assertEquals("Homebase", c.organizationName)
    }

    @Test
    fun displayNameFillsGivenNameWhenBothNamesBlank() {
        val c = DeviceContact(displayName = "Ada Vance").toCNContact()
        assertEquals("Ada Vance", c.givenName)
        assertEquals("", c.familyName)
    }

    @Test
    fun phonesAndEmailsKeepOrderAndLabels() {
        val c = DeviceContact(
            displayName = "Ada",
            phones = listOf("+14155550123", "+4512345678"),
            emails = listOf("a@example.com", "b@example.com"),
        ).toCNContact()
        val phones = c.phoneNumbers.map { it as CNLabeledValue }
        assertEquals(listOf("+14155550123", "+4512345678"), phones.map { (it.value as CNPhoneNumber).stringValue })
        assertEquals(listOf(CNLabelPhoneNumberMobile, CNLabelPhoneNumberMobile), phones.map { it.label })
        val emails = c.emailAddresses.map { it as CNLabeledValue }
        assertEquals(listOf("a@example.com", "b@example.com"), emails.map { it.value.toString() })
        assertEquals(listOf(CNLabelOther, CNLabelOther), emails.map { it.label })
    }

    @Test
    fun websiteBecomesHomePageUrl() {
        val c = DeviceContact(displayName = "Ada", website = "https://example.com").toCNContact()
        val urls = c.urlAddresses.map { it as CNLabeledValue }
        assertEquals(listOf("https://example.com"), urls.map { it.value.toString() })
        assertEquals(CNLabelURLAddressHomePage, urls.single().label)
    }

    @Test
    fun blankWebsiteAddsNoUrl() {
        assertEquals(0, DeviceContact(displayName = "Ada").toCNContact().urlAddresses.size)
    }
}
