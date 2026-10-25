package id.homebase.core.ui.screens.contactbook.components

import id.homebase.core.ui.screens.contactbook.ContactFieldValidation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhoneSeededE164Test {

    private fun country(iso: String) = countries.first { it.iso == iso }

    @Test
    fun `a national seed publishes the number the field already displays`() {
        val seeded = seededE164("4155553695", country("US"))

        assertEquals("+14155553695", seeded)
        assertTrue(ContactFieldValidation.isValidPhone(requireNotNull(seeded)))
    }

    @Test
    fun `a seed already in E164 publishes nothing`() {
        for (stored in listOf("+14155550123", "+442079460018", "+390669821234")) {
            assertNull(seededE164(stored, country("US")), stored)
        }
    }

    @Test
    fun `a blank seed publishes nothing`() {
        for (blank in listOf("", "   ", "-", "()")) {
            assertNull(seededE164(blank, country("US")), blank)
        }
    }

    @Test
    fun `an unknown dial code publishes nothing rather than reassigning the country`() {
        // splitE164 leaves the national part empty here, so there is no number to re-file
        // under the fallback country.
        assertNull(seededE164("+9991234567", country("US")))
    }

    @Test
    fun `a seed carrying separators is published as digits`() {
        assertEquals("+14155553695", seededE164("(415) 555-3695", country("US")))
    }
}

class PhoneSharedDialCodeTest {

    @Test
    fun `a shared dial code seeds its main country without a region`() {
        assertEquals("US", splitE164("+14155550123").first?.iso)
        assertEquals("RU", splitE164("+74951234567").first?.iso)
    }

    @Test
    fun `a shared dial code follows the device region`() {
        assertEquals("CA", splitE164("+16135550123", "CA").first?.iso)
        assertEquals("US", splitE164("+14155550123", "DK").first?.iso)
    }
}
