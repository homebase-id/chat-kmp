package id.homebase.api.common

import kotlin.test.Test
import kotlin.test.assertEquals

class IdnContractTest {

    @Test
    fun asciiNameIsUnchangedBothWays() {
        assertEquals("example.com", Idn.toAscii("example.com"))
        assertEquals("example.com", Idn.toUnicode("example.com"))
        assertEquals("frodo.dotyou.cloud", Idn.toAscii("frodo.dotyou.cloud"))
    }

    @Test
    fun asciiCaseIsPreserved() {
        assertEquals("Example.COM", Idn.toAscii("Example.COM"))
    }

    @Test
    fun punycodeInputToAsciiIsUnchanged() {
        assertEquals("xn--5cab8c.dk", Idn.toAscii("xn--5cab8c.dk"))
    }

    @Test
    fun asciiDomainNameLowercasesAndAcceptsPunycode() {
        assertEquals("xn--5cab8c.dk", AsciiDomainName("XN--5CAB8C.dk").domainName)
    }
}
