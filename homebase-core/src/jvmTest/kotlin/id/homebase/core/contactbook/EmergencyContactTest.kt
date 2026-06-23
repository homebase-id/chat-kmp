@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.contactbook

import id.homebase.api.client.contacts.Contact
import id.homebase.api.client.contacts.ContactContent
import id.homebase.api.client.contacts.toCanonicalAppId
import id.homebase.core.config.AppConfig
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Pins the read side of the app-data emergency flag: [Contact.isEmergencyContact] decodes only THIS
 * app's slot ([AppConfig.APP_ID]) and tolerates absent/foreign/malformed data.
 */
class EmergencyContactTest {

    private val uid = Uuid.parse("11111111-1111-1111-1111-111111111111")
    private val ourSlot = AppConfig.APP_ID.toCanonicalAppId()

    private fun contact(appData: Map<String, String>?) =
        Contact(uniqueId = uid, versionTag = null, content = ContactContent(appData = appData))

    @Test
    fun absentAppData_isNotEmergency() {
        assertFalse(contact(null).isEmergencyContact())
    }

    @Test
    fun ourSlotTrue_isEmergency() {
        assertTrue(contact(mapOf(ourSlot to """{"isEmergencyContact":true}""")).isEmergencyContact())
    }

    @Test
    fun ourSlotFalse_isNotEmergency() {
        assertFalse(contact(mapOf(ourSlot to """{"isEmergencyContact":false}""")).isEmergencyContact())
    }

    @Test
    fun anotherAppsSlot_isNotReadAsOurs() {
        val other = "99999999-9999-9999-9999-999999999999"
        assertFalse(contact(mapOf(other to """{"isEmergencyContact":true}""")).isEmergencyContact())
    }

    @Test
    fun malformedSlot_isNotEmergency() {
        assertFalse(contact(mapOf(ourSlot to "not json {{{")).isEmergencyContact())
    }
}
