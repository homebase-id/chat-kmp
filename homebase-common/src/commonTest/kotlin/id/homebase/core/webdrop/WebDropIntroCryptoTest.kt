package id.homebase.core.webdrop

import id.homebase.api.crypto.AesCbc
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The intro blob shares the link key with the payloads, so it MUST NOT share an IV with any of
 * them - CBC under one key with a repeated IV leaks. This pins the construction the service uses:
 * fresh IV, encrypt the intro json, round-trip.
 */
@OptIn(ExperimentalEncodingApi::class)
class WebDropIntroCryptoTest {

    @Test
    fun introEncryptsUnderItsOwnIvAndRoundTrips() = runTest {
        val key = ByteArrayUtil.getRndByteArray(16)
        val payloadIvs = List(3) { ByteArrayUtil.getRndByteArray(16) }
        val introIv = ByteArrayUtil.getRndByteArray(16)

        assertTrue(payloadIvs.none { it.contentEquals(introIv) })

        val intro = WebDropIntroContent(
            recipientName = "Thomas Kragh-Muller",
            conditions = listOf(WebDropProtocol.ConditionRecipientOnly, WebDropProtocol.ConditionPersonalData),
        )
        val blob = WebDropIntro(
            iv = Base64.encode(introIv),
            data = Base64.encode(
                AesCbc.encrypt(OdinSystemSerializer.serialize(intro).encodeToByteArray(), key, introIv)
            ),
        )

        val plain = AesCbc.decrypt(Base64.decode(blob.data), key, Base64.decode(blob.iv))
        val back = OdinSystemSerializer.deserialize<WebDropIntroContent>(plain.decodeToString())
        assertEquals(intro, back)
    }
}
