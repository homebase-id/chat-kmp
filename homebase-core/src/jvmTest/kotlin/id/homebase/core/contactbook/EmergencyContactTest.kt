@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.contactbook

import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.contacts.Contact
import id.homebase.api.client.contacts.ContactContent
import id.homebase.api.client.contacts.toCanonicalAppId
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.core.config.AppConfig
import kotlinx.coroutines.test.runTest
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

    // ── isSelf guard (issue #982 bug 2) ─────────────────────────────────────────

    private suspend fun credentialsManagerFor(activeDomain: String?): CredentialsManager =
        CredentialsManager().apply {
            if (activeDomain == null) return@apply
            val creds = ApiCredentials.create(
                domain = OdinId(activeDomain),
                clientAccessToken = "test-token",
                sharedSecret = SecureByteArray("0123456789abcdef".encodeToByteArray()),
            )
            storeCredentials(creds)
            setActiveCredentials(creds)
        }

    @Test
    fun isSelf_sameDomain_isTrue() = runTest {
        val creds = credentialsManagerFor("sam.dotyou.cloud")
        assertTrue(creds.isSelf(OdinId("sam.dotyou.cloud")))
    }

    @Test
    fun isSelf_caseInsensitive_isTrue() = runTest {
        val creds = credentialsManagerFor("sam.dotyou.cloud")
        assertTrue(creds.isSelf(OdinId("SAM.DOTYOU.CLOUD")))
    }

    @Test
    fun isSelf_differentDomain_isFalse() = runTest {
        val creds = credentialsManagerFor("sam.dotyou.cloud")
        assertFalse(creds.isSelf(OdinId("frodo.dotyou.cloud")))
    }

    @Test
    fun isSelf_noActiveDomain_isFalse() = runTest {
        // Fail-open (not-self) when the active domain can't be resolved, mirroring the VM filter's
        // existing behavior — a transient resolution failure must not block a real designation.
        val creds = credentialsManagerFor(activeDomain = null)
        assertFalse(creds.isSelf(OdinId("sam.dotyou.cloud")))
    }
}
