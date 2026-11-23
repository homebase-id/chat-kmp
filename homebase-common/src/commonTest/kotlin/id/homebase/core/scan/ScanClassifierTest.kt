package id.homebase.core.scan

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScanClassifierTest {
    @Test
    fun luhn_acceptsValidAndRejectsInvalid() {
        assertTrue(luhnValid("4111111111111111"))
        assertFalse(luhnValid("4111111111111112"))
    }

    @Test
    fun paymentCard_numberWithSpacesOrDashes() {
        assertEquals(ScannedDocumentType.PaymentCard, classifyCardText("VISA\n4111 1111 1111 1111\n12/29"))
        assertEquals(ScannedDocumentType.PaymentCard, classifyCardText("4111-1111-1111-1111"))
    }

    @Test
    fun digitsFailingLuhn_areNotAPaymentCard() {
        assertEquals(ScannedDocumentType.Card, classifyCardText("4111 1111 1111 1112"))
    }

    @Test
    fun idKeywords() {
        assertEquals(ScannedDocumentType.IdCard, classifyCardText("Driving Licence\nDate of Birth 01.01.1990"))
        assertEquals(ScannedDocumentType.IdCard, classifyCardText("PASSPORT"))
        assertEquals(ScannedDocumentType.Card, classifyCardText("Adobe loyalty club"))
    }

    @Test
    fun idKeywordsWinOverALuhnValidDocumentNumber() {
        assertEquals(ScannedDocumentType.IdCard, classifyCardText("Driving Licence 4111 1111 1111 1111"))
    }

    @Test
    fun noText_isGenericCard() {
        assertEquals(ScannedDocumentType.Card, classifyCardText(""))
    }

    @Test
    fun cardShape_usesLongOverShortSideWithinTolerance() {
        assertTrue(isCardShaped(1600, 1009))
        assertTrue(isCardShaped(1009, 1600))
        assertFalse(isCardShaped(1600, 1131))
        assertFalse(isCardShaped(0, 0))
    }
}
