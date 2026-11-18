package id.homebase.core.ui.screens.card

import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.drives.AclViewer
import id.homebase.api.client.profile.ProfileAttribute
import id.homebase.api.client.profile.ProfileAttributeTypes
import id.homebase.api.client.profile.ProfileVisibility
import id.homebase.core.ui.screens.profile.visibleAttribute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Runs card-selection.json: which stored card a viewer sees. The same file is meant to be copied to odin-core and any other reader of the contract. */
class CardSelectionFixtureTest {

    private val fixture = Json.parseToJsonElement(
        assertNotNull(javaClass.classLoader.getResource("card-contract/card-selection.json")).readText(),
    ).jsonObject

    private fun stored(name: String): ProfileAttribute {
        val card = fixture.getValue("cards").jsonObject.getValue(name).jsonObject
        val visibility = ProfileVisibility.entries.first { it.wireValue == card.getValue("visibility").jsonPrimitive.content }
        val circles = card.getValue("circleIds").jsonArray.map { it.jsonPrimitive.content }
        return ProfileAttribute(
            id = Uuid.random(),
            type = ProfileAttributeTypes.PROFILE_CARD,
            versionTag = Uuid.random(),
            visibility = visibility,
            data = buildJsonObject { put("design", name) },
            acl = AccessControlList(requiredSecurityGroup = visibility.wireValue, circleIdList = circles),
            priority = card.getValue("priority").jsonPrimitive.int,
        )
    }

    @Test
    fun everyCaseSelectsTheExpectedCard() {
        val cases = fixture.getValue("cases").jsonArray.map { it.jsonObject }
        assert(cases.isNotEmpty())
        for (case in cases) {
            val tier = ProfileVisibility.entries.first { it.wireValue == case.getValue("tier").jsonPrimitive.content }
            val viewer = AclViewer(tier, case.getValue("circleIds").jsonArray.map { it.jsonPrimitive.content }.toSet())
            val attributes = case.getValue("stored").jsonArray.map { stored(it.jsonPrimitive.content) }
            val picked = attributes.visibleAttribute(ProfileAttributeTypes.PROFILE_CARD, viewer, listOf("design"))
            assertEquals(
                (case.getValue("expected") as? JsonPrimitive)?.contentOrNull,
                picked?.data?.get("design")?.jsonPrimitive?.content,
                case.getValue("name").jsonPrimitive.content,
            )
        }
    }
}
