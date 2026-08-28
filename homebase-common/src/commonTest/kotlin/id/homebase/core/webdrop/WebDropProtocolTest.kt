@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.webdrop

import id.homebase.api.HomebaseProtocol
import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class WebDropProtocolTest {

    @Test
    fun linkCarriesTheKeyOnlyInTheFragment() {
        val drive = Uuid.parse("6d1711af-8b93-43ef-b798-b84d51f25828")
        val drop = Uuid.parse("edee430a-73d4-49ae-a9ae-2d3091957702")
        val key = ByteArray(WebDropProtocol.KeyBytes) { it.toByte() }

        val link = WebDropProtocol.buildLink("frodo.dotyou.cloud", drive, drop, key)

        val (page, fragment) = link.split("#", limit = 2)
        assertEquals("https://frodo.dotyou.cloud/apps/web-drop/d/$drive/$drop", page)
        // 16 bytes -> exactly 22 base64url chars, no padding, nothing the server ever sees
        assertEquals(22, fragment.length)
        assertTrue(fragment.none { it == '=' || it == '+' || it == '/' })
    }

    @Test
    fun burnTtlIsANegativeDurationInMilliseconds() {
        assertEquals(-1_200_000, WebDropProtocol.burnTtl())
    }

    @Test
    fun absoluteTtlIsCappedAtThirtyDays() {
        val now = 1_700_000_000_000
        val capped = WebDropProtocol.absoluteTtl(now, 90.days)
        assertEquals(now + WebDropProtocol.MaxLifetime.inWholeMilliseconds, capped)
    }

    @Test
    fun attachmentCapLeavesOneSlotForTheManifest() {
        assertEquals(HomebaseProtocol.MaxPayloadsPerFile - 1, WebDropProtocol.MaxFilesPerDrop)
        // every generated key must satisfy the server's ^[a-z0-9_]{8,10}$ payload key rule
        val keyPattern = Regex("^[a-z0-9_]{8,10}$")
        (0 until WebDropProtocol.MaxFilesPerDrop).forEach { index ->
            assertTrue(keyPattern.matches(WebDropProtocol.dataPayloadKey(index)))
        }
        assertTrue(keyPattern.matches(WebDropProtocol.ManifestPayloadKey))
    }

    @Test
    fun dropContentRoundTripsAndStaysSmall() {
        val content = WebDropDropContent(
            ivs = (0 until WebDropProtocol.MaxFilesPerDrop).associate {
                WebDropProtocol.dataPayloadKey(it) to "AAAAAAAAAAAAAAAAAAAAAA=="
            } + (WebDropProtocol.ManifestPayloadKey to "AAAAAAAAAAAAAAAAAAAAAA=="),
        )

        val json = OdinSystemSerializer.serialize(content)
        // cleartext appData.content has a server cap; a maximal drop must fit with headroom
        assertTrue(json.length < HomebaseProtocol.MaxHeaderContentBytes / 2, "size ${json.length}")

        val back = OdinSystemSerializer.deserialize<WebDropDropContent>(json)
        assertEquals(content, back)
    }

    @Test
    fun receiptRoundTrips() {
        val receipt = WebDropReceiptContent(
            name = "passport.jpg",
            files = listOf(
                WebDropManifestEntry(key = "wdr_dat1", name = "passport.jpg", contentType = "image/jpeg", size = 12345),
            ),
            url = "https://frodo.dotyou.cloud/apps/web-drop/d/x/y#z",
            ttl = -1_200_000,
            createdAt = 1_700_000_000_000,
        )
        val back = OdinSystemSerializer.deserialize<WebDropReceiptContent>(OdinSystemSerializer.serialize(receipt))
        assertEquals(receipt, back)
    }
}
