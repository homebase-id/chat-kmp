package id.homebase.core.ui.screens.card

import id.homebase.api.client.ClientException
import id.homebase.api.client.ProblemDetails
import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.profile.ProfileAttribute
import id.homebase.api.client.profile.ProfileAttributeTypes
import id.homebase.api.client.profile.ProfileVisibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class CardRepositoryTest {

    private fun attribute(
        data: JsonObject,
        visibility: ProfileVisibility = ProfileVisibility.ANONYMOUS,
        circles: List<String>? = null,
        priority: Int = PUBLIC_CARD_PRIORITY,
    ) = ProfileAttribute(
        id = Uuid.random(),
        type = ProfileAttributeTypes.PROFILE_CARD,
        versionTag = Uuid.random(),
        visibility = visibility,
        data = data,
        acl = AccessControlList(requiredSecurityGroup = visibility.wireValue, circleIdList = circles),
        priority = priority,
    )

    private class FakeStore(var attributes: List<ProfileAttribute> = emptyList()) : CardAttributeStore {
        class Write(val data: JsonObject, val visibility: ProfileVisibility, val id: Uuid?, val versionTag: Uuid?, val priority: Int)

        val writes = mutableListOf<Write>()
        var failWith: Exception? = null

        override suspend fun load() = attributes

        override suspend fun save(data: JsonObject, visibility: ProfileVisibility, id: Uuid?, versionTag: Uuid?, priority: Int) {
            failWith?.let { throw it }
            writes += Write(data, visibility, id, versionTag, priority)
        }
    }

    private fun unknownType() = ClientException(
        status = 400,
        message = "Unknown profile attribute type 9832dc5dd4ba12dd60acb853e7588f49",
        correlationId = null,
        problem = ProblemDetails(status = 400, title = "Unknown profile attribute type 9832dc5dd4ba12dd60acb853e7588f49"),
    )

    @Test
    fun publicCardRoundTripsWithUnknownExtraKeys() {
        val data = Json.parseToJsonElement(
            """{"design":"poster","overrides":{"socials":"bar"},"future":{"a":1}}""",
        ) as JsonObject
        val card = ProfileCard.from(attribute(data))!!

        assertEquals(CardAudience.Public, card.audience)
        assertEquals("poster", card.design)
        assertEquals(CardOverrides(socials = "bar"), card.overrides)
        assertEquals(data, card.toData())
    }

    @Test
    fun circleCardRoundTripsWithItsLabelAndPriority() {
        val data = buildJsonObject { put("design", "dossier"); put("label", "Friends") }
        val card = ProfileCard.from(attribute(data, ProfileVisibility.CONNECTED, listOf("c1"), priority = 2))!!

        assertEquals(CardAudience.Circle("c1", "Friends"), card.audience)
        assertEquals(2, card.priority)
        assertEquals(data, card.toData())
    }

    @Test
    fun anUnknownDesignIsKeptVerbatim() {
        val data = buildJsonObject { put("design", "hologram") }
        assertEquals(data, ProfileCard.from(attribute(data))!!.toData())
    }

    @Test
    fun aCardWithoutADesignOrOfAnotherTypeIsNotACard() {
        assertNull(ProfileCard.from(attribute(JsonObject(emptyMap()))))
        assertNull(ProfileCard.from(attribute(buildJsonObject { put("design", "poster") }).copy(type = ProfileAttributeTypes.NAME)))
    }

    @Test
    fun aFirstSaveCreatesTheAnonymousCardAtPublicPriority() = runTest {
        val store = FakeStore()
        assertTrue(CardRepository(store).savePublic(CardDesign.POSTER))

        val write = store.writes.single()
        assertEquals(ProfileVisibility.ANONYMOUS, write.visibility)
        assertEquals(PUBLIC_CARD_PRIORITY, write.priority)
        assertNull(write.id)
        assertEquals(JsonPrimitive("poster"), write.data["design"])
        assertFalse("label" in write.data)
    }

    @Test
    fun aLaterSaveEditsTheExistingCardKeepingItsOverridesAndExtras() = runTest {
        val existing = attribute(
            Json.parseToJsonElement("""{"design":"board","overrides":{"socials":"bar"},"future":1}""") as JsonObject,
        )
        val store = FakeStore(listOf(existing))
        CardRepository(store).savePublic(CardDesign.COLLAGE)

        val write = store.writes.single()
        assertEquals(existing.id, write.id)
        assertEquals(existing.versionTag, write.versionTag)
        assertEquals(
            Json.parseToJsonElement("""{"future":1,"design":"collage","overrides":{"socials":"bar"}}"""),
            write.data,
        )
    }

    @Test
    fun switchingTheDesignDropsOverridesTheNewDesignDoesNotExpose() = runTest {
        val existing = attribute(
            Json.parseToJsonElement(
                """{"design":"dossier","overrides":{"palette":{"accent":"#ABCDEF"},"socials":"bar","portraits":[{"shape":"circle"}]}}""",
            ) as JsonObject,
        )
        val store = FakeStore(listOf(existing))
        CardRepository(store).savePublic(CardDesign.POSTER)

        assertEquals(
            Json.parseToJsonElement("""{"design":"poster","overrides":{"socials":"bar"}}"""),
            store.writes.single().data,
        )
    }

    @Test
    fun cardsListsPublicAndCircleCards() = runTest {
        val store = FakeStore(
            listOf(
                attribute(buildJsonObject { put("design", "board") }),
                attribute(buildJsonObject { put("design", "poster"); put("label", "Fam") }, ProfileVisibility.CONNECTED, listOf("c"), 0),
            ),
        )
        val cards = CardRepository(store).cards()

        assertEquals(2, cards.size)
        assertIs<CardAudience.Circle>(cards.single { it.priority == 0 }.audience)
        assertEquals("board", cards.publicCard()?.design)
    }

    @Test
    fun anUnsupportedServerIsRememberedAndNeverSurfacesAnError() = runTest {
        val store = FakeStore().apply { failWith = unknownType() }
        val repo = CardRepository(store)

        assertFalse(repo.savePublic(CardDesign.POSTER))

        store.failWith = IllegalStateException("must not be called again")
        assertFalse(repo.savePublic(CardDesign.BOARD))
        assertTrue(store.writes.isEmpty())
    }

    @Test
    fun anyOtherFailureStillSurfaces() = runTest {
        val store = FakeStore().apply {
            failWith = ClientException(status = 400, message = "Bad", correlationId = null, problem = ProblemDetails(title = "Bad"))
        }
        var thrown: Exception? = null
        try { CardRepository(store).savePublic(CardDesign.POSTER) } catch (e: ClientException) { thrown = e }
        assertNotNull(thrown)
    }

    @Test
    fun aConnectedCardWithNoCirclesOrSeveralIsNotACard() {
        val data = buildJsonObject { put("design", "board") }
        val none = attribute(data, ProfileVisibility.CONNECTED, circles = emptyList())
        val two = attribute(data, ProfileVisibility.CONNECTED, circles = listOf("a", "b"))

        assertTrue(listOf(none, two).profileCards().isEmpty())
        assertNull(listOf(none, two).profileCards().publicCard())
    }

    @Test
    fun anAuthenticatedCardIsNotThePublicCard() {
        val data = buildJsonObject { put("design", "board") }
        val cards = listOf(attribute(data, ProfileVisibility.AUTHENTICATED)).profileCards()

        assertNull(cards.publicCard())
    }
}
