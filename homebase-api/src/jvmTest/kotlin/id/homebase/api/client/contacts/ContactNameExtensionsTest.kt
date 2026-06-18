package id.homebase.api.client.contacts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the single name-resolution helpers shared by the contact-book and chat read paths
 * (Phase 2 of CONTACT_STACK_CONSOLIDATION.md). Both paths now route through these, so this is the
 * one place the display-name/initials behavior is asserted.
 */
class ContactNameExtensionsTest {

    @Test
    fun resolveDisplayName_prefersDisplayName() {
        assertEquals(
            "Sam Q. Public",
            ContactName(displayName = "Sam Q. Public", givenName = "Sam", surname = "Public")
                .resolveDisplayName(odinId = "sam.dotyou.cloud"),
        )
    }

    @Test
    fun resolveDisplayName_composesGivenAndSurnameWhenNoDisplayName() {
        assertEquals(
            "Frodo Baggins",
            ContactName(givenName = "Frodo", surname = "Baggins").resolveDisplayName(),
        )
    }

    @Test
    fun resolveDisplayName_fallsBackOdinIdThenPhoneThenEmail() {
        assertEquals("merry.demo.rocks", (null as ContactName?).resolveDisplayName(odinId = "merry.demo.rocks"))
        assertEquals("+1-555-1", (null as ContactName?).resolveDisplayName(phone = "+1-555-1"))
        assertEquals("a@b.c", (null as ContactName?).resolveDisplayName(email = "a@b.c"))
    }

    @Test
    fun resolveDisplayName_nullWhenNothingRenderable() {
        assertNull((null as ContactName?).resolveDisplayName())
        assertNull(ContactName(displayName = "  ").resolveDisplayName())
    }

    @Test
    fun resolveDisplayName_syncedContact_displayNameOnly() {
        // The connection-synced shape that surfaced the "None" symptom.
        assertEquals(
            "Samwise Gamgee",
            ContactName(displayName = "Samwise Gamgee").resolveDisplayName(odinId = "samwise.gamgee.demo.rocks"),
        )
    }

    @Test
    fun initials_fromGivenAndSurname() {
        assertEquals("FB", ContactName(givenName = "Frodo", surname = "Baggins").initials())
    }

    @Test
    fun initials_fromDisplayNameTokens() {
        assertEquals("SG", ContactName(displayName = "Samwise Gamgee").initials())
        assertEquals("S", ContactName(displayName = "Sauron").initials())
    }

    @Test
    fun initials_questionMarkWhenEmpty() {
        assertEquals("?", (null as ContactName?).initials())
        assertEquals("?", ContactName().initials())
    }
}
