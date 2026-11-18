package id.homebase.api.client.drives.files

import id.homebase.api.common.SecureByteArray
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// The vectors odin-js runs too; the sealed ones were made with another AES-GCM (Python's cryptography).
class DescriptorContentVectorsTest {

    private val vectors: JsonObject = Json.parseToJsonElement(
        javaClass.getResource("/uploads-descriptor-vectors.json")!!.readText(),
    ).jsonObject
    private val key = SecureByteArray(Base64.decode(vectors.getValue("aesKey").jsonPrimitive.content))

    private fun JsonObject.str(name: String) = getValue(name).jsonPrimitive.content
    private fun list(name: String) = vectors.getValue(name).jsonArray.map { it.jsonObject }

    @Test
    fun everyPlainVectorParsesAndWritesBackTheSame() {
        list("plain").forEach { v ->
            val content = v.str("descriptorContent")
            val parsed = assertNotNull(DescriptorContentV1.parseOrNull(content), v.str("name"))
            assertEquals(content, parsed.encode(), v.str("name"))
        }
    }

    @Test
    fun everySealedVectorOpensWithTheFileKey() = runTest {
        list("sealed").forEach { v ->
            assertEquals(v.str("plaintext"), DescriptorContentSeal.open(v.str("descriptorContent"), key), v.str("name"))
        }
    }

    @Test
    fun aBrokenSealOpensToNothing() = runTest {
        list("doesNotOpen").forEach { v -> assertNull(DescriptorContentSeal.open(v.str("descriptorContent"), key), v.str("name")) }
    }

    @Test
    fun theSealedLengthIsWhatSealingWrites() = runTest {
        for (size in listOf(1, 2, 3, 100, 700, 733, 734, 1000)) {
            assertEquals(DescriptorContentSeal.seal("a".repeat(size), key).length, DescriptorContentSeal.sealedLength(size), "size $size")
        }
    }

    @Test
    fun noOlderFormIsReadAsTheCurrentOne() {
        vectors.getValue("olderForms").jsonArray.forEach { assertNull(DescriptorContentV1.parseOrNull(it.jsonPrimitive.content)) }
    }
}
