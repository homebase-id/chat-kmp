@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.contactbook

import id.homebase.api.client.contacts.Contact
import id.homebase.api.client.contacts.ContactContent
import id.homebase.api.client.contacts.toCanonicalAppId
import id.homebase.core.config.AppConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Pins the read side of the app-data can-locate flag: [Contact.iCanLocate] decodes only THIS app's
 * slot ([AppConfig.APP_ID]) and tolerates absent/foreign/malformed data.
 */
class EmergencyContactTest {

    private val uid = Uuid.parse("11111111-1111-1111-1111-111111111111")
    private val ourSlot = AppConfig.APP_ID.toCanonicalAppId()

    private fun contact(appData: Map<String, String>?) =
        Contact(uniqueId = uid, versionTag = null, content = ContactContent(appData = appData))

    @Test
    fun absentAppData_isNotLocatable() {
        assertFalse(contact(null).iCanLocate())
    }

    @Test
    fun ourSlotTrue_isLocatable() {
        assertTrue(contact(mapOf(ourSlot to """{"iCanLocate":true}""")).iCanLocate())
    }

    @Test
    fun ourSlotFalse_isNotLocatable() {
        assertFalse(contact(mapOf(ourSlot to """{"iCanLocate":false}""")).iCanLocate())
    }

    @Test
    fun anotherAppsSlot_isNotReadAsOurs() {
        val other = "99999999-9999-9999-9999-999999999999"
        assertFalse(contact(mapOf(other to """{"iCanLocate":true}""")).iCanLocate())
    }

    @Test
    fun malformedSlot_isNotLocatable() {
        assertFalse(contact(mapOf(ourSlot to "not json {{{")).iCanLocate())
    }

    // ── filterLocatable dedup (issue #982) ──────────────────────────────────────

    private val locatableAppData = mapOf(ourSlot to """{"iCanLocate":true}""")

    private fun contactWith(uniqueId: Uuid, odinId: String?, locatable: Boolean = true) = Contact(
        uniqueId = uniqueId,
        versionTag = null,
        content = ContactContent(
            odinId = odinId,
            appData = if (locatable) locatableAppData else null,
        ),
    )

    @Test
    fun filterLocatable_sameOdinIdDifferentUniqueId_keepsOnlyOne() {
        val manual = contactWith(Uuid.parse("11111111-1111-1111-1111-111111111111"), odinId = "sam.dotyou.cloud")
        val identity = contactWith(Uuid.parse("22222222-2222-2222-2222-222222222222"), odinId = "sam.dotyou.cloud")

        val result = listOf(manual, identity).filterLocatable()

        assertTrue(result.size == 1)
    }

    @Test
    fun filterLocatable_keepsNewestRowPerOdinId() {
        val stale = contactWith(Uuid.parse("11111111-1111-1111-1111-111111111111"), odinId = "sam.dotyou.cloud")
        val fresh = contactWith(Uuid.parse("22222222-2222-2222-2222-222222222222"), odinId = "sam.dotyou.cloud")

        // Newest-first order (mirrors ContactRepository.contacts): fresh comes first.
        val result = listOf(fresh, stale).filterLocatable()

        assertEquals(listOf(fresh), result)
    }

    @Test
    fun filterLocatable_differentOdinIds_keepsBoth() {
        val a = contactWith(Uuid.parse("11111111-1111-1111-1111-111111111111"), odinId = "sam.dotyou.cloud")
        val b = contactWith(Uuid.parse("22222222-2222-2222-2222-222222222222"), odinId = "frodo.dotyou.cloud")

        assertEquals(listOf(a, b), listOf(a, b).filterLocatable())
    }

    @Test
    fun filterLocatable_dropsNonLocatableContacts() {
        val locatable = contactWith(Uuid.parse("11111111-1111-1111-1111-111111111111"), odinId = "sam.dotyou.cloud")
        val notLocatable = contactWith(
            Uuid.parse("22222222-2222-2222-2222-222222222222"),
            odinId = "frodo.dotyou.cloud",
            locatable = false,
        )

        assertEquals(listOf(locatable), listOf(locatable, notLocatable).filterLocatable())
    }
}
