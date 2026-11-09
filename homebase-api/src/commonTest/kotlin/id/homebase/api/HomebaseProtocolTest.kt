package id.homebase.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomebaseProtocolTest {
    @Test fun encrypted_header_size_is_base64_of_padded_aes_cbc() {
        assertEquals(24, HomebaseProtocol.encryptedHeaderContentBytes(""))
        assertEquals(7000, HomebaseProtocol.encryptedHeaderContentBytes("x".repeat(5247)))
        assertEquals(7020, HomebaseProtocol.encryptedHeaderContentBytes("x".repeat(5248)))
        assertTrue(HomebaseProtocol.fitsHeaderContent("x".repeat(5247)))
        assertFalse(HomebaseProtocol.fitsHeaderContent("x".repeat(5248)))
    }
}
