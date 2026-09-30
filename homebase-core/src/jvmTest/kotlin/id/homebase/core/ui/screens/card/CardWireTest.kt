package id.homebase.core.ui.screens.card

import id.homebase.api.client.ClientException
import id.homebase.api.client.profile.ProfileAttributeTypes
import id.homebase.api.client.profile.ProfileVisibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class CardWireTest {

    @Test
    fun savingThePublicCardPutsTheContractBody() = runTest {
        val wire = CardWireHarness()
        val overrides = CardOverrides(palette = CardPalette(accent = "#abcdef"))

        assertTrue(wire.cardRepository().savePublic("board", overrides))

        val body = wire.putBodies.single().jsonObject
        assertEquals("9832dc5dd4ba12dd60acb853e7588f49", body["type"]?.jsonPrimitive?.content)
        assertEquals(ProfileAttributeTypes.PROFILE_CARD, body["type"]?.jsonPrimitive?.content)
        assertEquals("anonymous", body["visibility"]?.jsonPrimitive?.content)
        assertEquals(1000, body["priority"]?.jsonPrimitive?.int)
        val data = body["data"]!!.jsonObject
        assertEquals(JsonPrimitive("board"), data["design"])
        assertEquals(overrides.toJson(), data["overrides"])
        assertFalse("label" in data)
        assertFalse("id" in body)
        assertFalse("circleIds" in body)
    }

    @Test
    fun aRealUnknownTypeProblemDetailsIsRememberedAndNeverSurfaces() = runTest {
        val wire = CardWireHarness { CardWireHarness.Reply.Problem(400, CardWireHarness.UNKNOWN_CARD_TYPE_400) }
        val repo = wire.cardRepository()

        assertFalse(repo.savePublic("board"))
        assertEquals(1, wire.puts)

        assertFalse(repo.savePublic("poster"))
        assertEquals(1, wire.puts, "the second save must not reach the server")
    }

    @Test
    fun resetForgetsTheUnsupportedAnswerForTheNextIdentity() = runTest {
        val wire = CardWireHarness { n ->
            if (n == 1) CardWireHarness.Reply.Problem(400, CardWireHarness.UNKNOWN_CARD_TYPE_400) else CardWireHarness.Reply.Ok
        }
        val repo = wire.cardRepository()

        assertFalse(repo.savePublic("board"))
        repo.reset()

        assertTrue(repo.savePublic("board"))
        assertEquals(2, wire.puts)
    }

    @Test
    fun aDifferentArgumentErrorStillThrows() = runTest {
        val wire = CardWireHarness { CardWireHarness.Reply.Problem(400, CardWireHarness.UNKNOWN_PHOTO_TYPE_400) }
        val repo = wire.cardRepository()

        assertFailsWith<ClientException> { repo.savePublic("board") }
        assertFailsWith<ClientException> { repo.savePublic("board") }
        assertEquals(2, wire.puts, "a different 400 must not be remembered as an unsupported server")
    }

    @Test
    fun savingACircleCardPutsItConnectedWithExactlyOneCircleAndItsLabel() = runTest {
        val wire = CardWireHarness()
        val card = ProfileCard(
            id = kotlin.uuid.Uuid.NIL,
            versionTag = kotlin.uuid.Uuid.NIL,
            audience = CardAudience.Circle("c-friends", "Friends"),
            design = "poster",
            priority = 3,
        )

        assertTrue(wire.cardRepository().saveCircle(card))

        val body = wire.putBodies.single().jsonObject
        assertEquals(ProfileVisibility.CONNECTED.wireValue, body["visibility"]?.jsonPrimitive?.content)
        assertEquals(3, body["priority"]?.jsonPrimitive?.int)
        assertEquals(listOf("c-friends"), body["circleIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(JsonPrimitive("Friends"), body["data"]!!.jsonObject["label"])
        assertFalse("id" in body)
    }
}
