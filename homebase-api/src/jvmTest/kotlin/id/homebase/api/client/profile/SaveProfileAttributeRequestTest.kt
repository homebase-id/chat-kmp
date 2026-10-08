package id.homebase.api.client.profile

import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SaveProfileAttributeRequestTest {

    private fun encode(priority: Int?, circleIds: List<String>? = null): JsonObject {
        val request = SaveProfileAttributeRequest(
            type = ProfileAttributeTypes.PROFILE_CARD,
            visibility = "anonymous",
            data = JsonObject(mapOf("design" to JsonPrimitive("board"))),
            priority = priority,
            circleIds = circleIds,
        )
        return OdinSystemSerializer.json.parseToJsonElement(OdinSystemSerializer.json.encodeToString(request)).jsonObject
    }

    @Test
    fun priorityIsOmittedWhenNullSoExistingSavesAreUnchanged() {
        assertFalse(encode(null).containsKey("priority"))
    }

    @Test
    fun priorityIsWrittenWhenSet() {
        assertEquals(1000, encode(1000)["priority"]!!.jsonPrimitive.int)
    }

    @Test
    fun profileCardTypeMatchesTheSharedContractGuid() {
        assertEquals("9832dc5dd4ba12dd60acb853e7588f49", ProfileAttributeTypes.PROFILE_CARD)
        assertEquals("9832dc5dd4ba12dd60acb853e7588f49", encode(null)["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun circleIdsAreOmittedWhenNull() {
        assertFalse(encode(null).containsKey("circleIds"))
    }

    @Test
    fun circleIdsAreWrittenWhenSet() {
        assertEquals(JsonArray(listOf(JsonPrimitive("c1"))), encode(null, listOf("c1"))["circleIds"])
    }
}
