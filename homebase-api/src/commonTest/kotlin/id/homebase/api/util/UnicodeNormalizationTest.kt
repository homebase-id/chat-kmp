package id.homebase.api.util

import kotlin.test.Test
import kotlin.test.assertEquals

/** The [toNfc] contract, run against each platform's real implementation. */
class UnicodeNormalizationTest {

    @Test
    fun combiningMarksAreComposed() {
        assertEquals("é", "é".toNfc())
        assertEquals("Café.txt", "Café.txt".toNfc())
    }

    @Test
    fun canonicalSingletonsMapToTheirComposedForm() {
        // ANGSTROM SIGN -> LATIN CAPITAL LETTER A WITH RING ABOVE
        assertEquals("Å", "Å".toNfc())
    }

    @Test
    fun hangulJamoComposeToASyllable() {
        assertEquals("한", "한".toNfc())
    }

    @Test
    fun composedTextAsciiEmojiAndEmptyAreUnchanged() {
        for (s in listOf("", "Report.pdf", "été", "📁 photos", "日本語.txt")) {
            assertEquals(s, s.toNfc())
        }
    }
}
