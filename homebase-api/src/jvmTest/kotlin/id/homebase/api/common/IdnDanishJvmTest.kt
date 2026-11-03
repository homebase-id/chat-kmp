package id.homebase.api.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IdnDanishJvmTest {

    @Test
    fun danishLettersConvertToPunycodeAndBack() {
        assertEquals("xn--5cab8c.dk", Idn.toAscii("æøå.dk"))
        assertEquals("xn--bg-lka.dk", Idn.toAscii("bøg.dk"))
        assertEquals("xn--blbrgrd-fxak7p.dk", Idn.toAscii("blåbærgrød.dk"))
        assertEquals("æøå.dk", Idn.toUnicode("xn--5cab8c.dk"))
    }

    @Test
    fun fromIdnProducesTheWireForm() {
        assertEquals("xn--5cab8c.dk", AsciiDomainName.fromIDN("æøå.dk").domainName)
        assertEquals("æøå.dk", AsciiDomainName("xn--5cab8c.dk").toIDN())
    }

    @Test
    fun invalidNamesAreRejected() {
        assertFailsWith<IllegalArgumentException> { Idn.toAscii("bad name.dk") }
        assertFailsWith<IllegalArgumentException> { Idn.toAscii("a".repeat(64) + ".dk") }
    }
}
