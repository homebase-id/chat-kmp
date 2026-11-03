package id.homebase.api.serialization

import id.homebase.api.client.SharedSecretEncryptedPayload
import id.homebase.api.client.drives.files.FileMetadata
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.peer.PeerTokenResponse
import id.homebase.api.youauth.YouAuthTokenResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class Base64BytesTest {

    private val thumbB64 = "AAECAwQFBgcICQ=="
    private val payloadThumbB64 = "/+4="
    private val ivB64 = "AAAAAAAAAAAAAAAAAAAAAA=="
    private val storedB64 = "cw=="
    private val contentB64 = "Yw=="
    private val localIvB64 = "AQEBAQEBAQEBAQEBAQEBAQ=="

    private fun headerJson(thumb: String = thumbB64, payloadIv: String = ivB64) = """
        {
          "appData": {
            "content": "hello",
            "previewThumbnail": {"pixelWidth": 2, "pixelHeight": 3, "contentType": "image/webp", "content": "$thumb"}
          },
          "payloads": [{
            "key": "pld",
            "iv": "$payloadIv",
            "thumbnails": [{"pixelWidth": 20, "pixelHeight": 30, "contentType": "image/webp", "content": "$payloadThumbB64"}],
            "hash": {"algorithm": "sha256", "storedHash": "$storedB64", "contentHash": "$contentB64"}
          }],
          "localAppData": {"iv": "$localIvB64", "content": "enc"}
        }
    """.trimIndent()

    private fun JsonObject.obj(key: String) = getValue(key).jsonObject

    @Test
    fun headerRoundTripKeepsEveryConvertedFieldAsTheSameString() {
        val parsed = OdinSystemSerializer.deserialize<FileMetadata>(headerJson())
        val out = Json.parseToJsonElement(OdinSystemSerializer.serialize(parsed)).jsonObject

        assertEquals(thumbB64, out.obj("appData").obj("previewThumbnail").getValue("content").jsonPrimitive.content)
        val payload = (out.getValue("payloads") as JsonArray)[0].jsonObject
        assertEquals(ivB64, payload.getValue("iv").jsonPrimitive.content)
        val thumb = (payload.getValue("thumbnails") as JsonArray)[0].jsonObject
        assertEquals(payloadThumbB64, thumb.getValue("content").jsonPrimitive.content)
        assertEquals(storedB64, payload.obj("hash").getValue("storedHash").jsonPrimitive.content)
        assertEquals(contentB64, payload.obj("hash").getValue("contentHash").jsonPrimitive.content)
        assertEquals(localIvB64, out.obj("localAppData").getValue("iv").jsonPrimitive.content)
    }

    @Test
    fun parsedBytesAreTheDecodedBase64() {
        val parsed = OdinSystemSerializer.deserialize<FileMetadata>(headerJson())
        assertContentEquals(ByteArray(10) { it.toByte() }, parsed.appData.previewThumbnail?.content?.bytes)
        assertContentEquals(ByteArray(16), parsed.payloads?.single()?.iv?.bytes)
    }

    @Test
    fun invalidBase64InAHeaderFieldBecomesNullAndTheRestStillParses() {
        val parsed = OdinSystemSerializer.deserialize<FileMetadata>(headerJson(thumb = "not base64 !!!", payloadIv = ""))

        assertNull(parsed.appData.previewThumbnail?.content)
        assertNotNull(parsed.appData.previewThumbnail)
        assertNull(parsed.payloads?.single()?.iv)
        assertEquals("hello", parsed.appData.content)
        assertEquals("pld", parsed.payloads?.single()?.key)
        assertNotNull(parsed.payloads?.single()?.hash?.storedHash)
    }

    @Test
    fun nullHeaderFieldIsLeftOutOfTheOutput() {
        val parsed = OdinSystemSerializer.deserialize<FileMetadata>(headerJson(payloadIv = ""))
        val payload = (Json.parseToJsonElement(OdinSystemSerializer.serialize(parsed)).jsonObject.getValue("payloads") as JsonArray)[0].jsonObject
        assertEquals(false, payload.containsKey("iv"))
    }

    @Test
    fun descriptorsParsedFromTheSameJsonAreEqual() {
        val json = """{"key":"k","iv":"$ivB64"}"""
        val a = OdinSystemSerializer.deserialize<PayloadDescriptor>(json)
        val b = OdinSystemSerializer.deserialize<PayloadDescriptor>(json)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun encodingIsPaddedStandardBase64() {
        assertEquals("/+4=", Base64Bytes(byteArrayOf(0xFF.toByte(), 0xEE.toByte())).toBase64())
        assertEquals("AA==", Base64Bytes(byteArrayOf(0)).toBase64())
    }

    @Test
    fun youAuthTokenResponseRoundTrips() {
        val json = """{"base64SharedSecretCipher":"AAEC","base64SharedSecretIv":"$ivB64","base64ClientAuthTokenCipher":"/+4=","base64ClientAuthTokenIv":"$ivB64"}"""
        val parsed = OdinSystemSerializer.deserialize<YouAuthTokenResponse>(json)
        assertContentEquals(byteArrayOf(0, 1, 2), parsed.base64SharedSecretCipher.bytes)
        assertEquals(Json.parseToJsonElement(json), Json.parseToJsonElement(OdinSystemSerializer.serialize(parsed)))
    }

    @Test
    fun peerTokenResponseRoundTrips() {
        val json = """{"authenticationToken64":"AAEC","sharedSecret":"$ivB64"}"""
        val parsed = OdinSystemSerializer.deserialize<PeerTokenResponse>(json)
        assertEquals("AAEC", parsed.authenticationToken64.toBase64())
        assertEquals(Json.parseToJsonElement(json), Json.parseToJsonElement(OdinSystemSerializer.serialize(parsed)))
    }

    @Test
    fun sharedSecretEncryptedPayloadRoundTrips() {
        val json = """{"iv":"$ivB64","data":"/+4="}"""
        val parsed = OdinSystemSerializer.deserialize<SharedSecretEncryptedPayload>(json)
        assertEquals(Json.parseToJsonElement(json), Json.parseToJsonElement(OdinSystemSerializer.serialize(parsed)))
    }

    @Test
    fun strictFieldsRejectInvalidBase64() {
        assertFailsWith<Exception> {
            OdinSystemSerializer.deserialize<SharedSecretEncryptedPayload>("""{"iv":"not base64 !!!","data":"AAEC"}""")
        }
        assertFailsWith<Exception> {
            OdinSystemSerializer.deserialize<PeerTokenResponse>("""{"authenticationToken64":"@@@@","sharedSecret":"AAEC"}""")
        }
    }
}
