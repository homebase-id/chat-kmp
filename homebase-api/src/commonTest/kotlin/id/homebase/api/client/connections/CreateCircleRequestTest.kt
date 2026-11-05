@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.api.client.connections

import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.drives.TargetDrive
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.youauth.DrivePermission
import id.homebase.api.youauth.DrivePermissionSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class CreateCircleRequestTest {
    private val alias = Uuid.parse("11111111-1111-1111-1111-111111111111")
    private val type = Uuid.parse("22222222-2222-2222-2222-222222222222")

    private fun request() = CreateAppCircleRequest(
        name = "Climbing",
        slug = "c-0123456789ab",
        description = "Wall buddies",
        emoji = "🧗",
        driveGrants = listOf(
            DriveGrantRequest(
                PermissionedDrive(TargetDrive(alias, type), DrivePermissionSet(listOf(DrivePermission.Read)))
            )
        ),
    )

    @Test
    fun requestCarriesSlugAndWrappedDriveGrants() {
        val json = Json.parseToJsonElement(OdinSystemSerializer.serialize(request())).jsonObject

        assertEquals("c-0123456789ab", json["slug"]!!.jsonPrimitive.content)
        assertEquals("🧗", json["emoji"]!!.jsonPrimitive.content)
        val grant = (json["driveGrants"] as JsonArray).single().jsonObject["permissionedDrive"]!!.jsonObject
        assertEquals("read", grant["permission"]!!.jsonPrimitive.content)
        val drive = grant["drive"]!!.jsonObject
        assertEquals(alias.toString(), drive["alias"]!!.jsonPrimitive.content)
        assertEquals(type.toString(), drive["type"]!!.jsonPrimitive.content)
        assertTrue(json["permissions"] is JsonObject)
    }

    @Test
    fun optionalFieldsAreOmittedWhenNull() {
        val json = OdinSystemSerializer.serialize(request().copy(description = null, emoji = null))
        val obj = Json.parseToJsonElement(json).jsonObject
        assertTrue("description" !in obj || obj["description"].toString() == "null")
    }

    @Test
    fun guidResponseParsesFromBothWireShapes() {
        val id = Uuid.parse("9e22b429-52f7-4d25-80e1-1250b651d343")
        assertEquals(id, OdinSystemSerializer.deserialize<Uuid>("\"9e22b42952f74d2580e11250b651d343\""))
        assertEquals(id, OdinSystemSerializer.deserialize<Uuid>("\"9e22b429-52f7-4d25-80e1-1250b651d343\""))
    }

    @Test
    fun slugIsReadBack() {
        val circle = OdinSystemSerializer.deserialize<RedactedCircleDefinition>(
            """{"id":"aa","name":"Climbing","slug":"c-0123456789ab"}"""
        )
        assertEquals("c-0123456789ab", circle.slug)
        assertNull(OdinSystemSerializer.deserialize<RedactedCircleDefinition>("""{"id":"aa"}""").slug)
    }

    @Test
    fun errorCodesMatchTheServer() {
        assertEquals(OdinClientErrorCode.CircleSlugTaken, OdinClientErrorCode.fromInt(3021))
        assertEquals(OdinClientErrorCode.ContactNotReviewed, OdinClientErrorCode.fromInt(3022))
    }

    @Test
    fun deleteQueryCarriesRemoveMembers() {
        assertEquals("/connections/circles/delete?removeMembers=true", deleteCirclePath(true))
        assertEquals("/connections/circles/delete?removeMembers=false", deleteCirclePath(false))
    }
}
