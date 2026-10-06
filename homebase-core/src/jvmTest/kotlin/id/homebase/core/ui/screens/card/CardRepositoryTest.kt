package id.homebase.core.ui.screens.card

import id.homebase.api.client.ClientException
import id.homebase.api.client.ProblemDetails
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
        val card = ProfileCard.from(profileCardAttribute(data))!!

        assertEquals(CardAudience.Public, card.audience)
        assertEquals("poster", card.design)
        assertEquals(CardOverrides(socials = "bar"), card.overrides)
        assertEquals(data, card.toData())
    }

    @Test
    fun circleCardRoundTripsWithItsLabelAndPriority() {
        val data = buildJsonObject { put("design", "dossier"); put("label", "Friends") }
        val card = ProfileCard.from(profileCardAttribute(data, ProfileVisibility.CONNECTED, listOf("c1"), priority = 2))!!

        assertEquals(CardAudience.Circle("c1", "Friends"), card.audience)
        assertEquals(2, card.priority)
        assertEquals(data, card.toData())
    }

    @Test
    fun anUnknownDesignIsKeptVerbatim() {
        val data = buildJsonObject { put("design", "hologram") }
        assertEquals(data, ProfileCard.from(profileCardAttribute(data))!!.toData())
    }

    @Test
    fun aCardWithoutADesignOrOfAnotherTypeIsNotACard() {
        assertNull(ProfileCard.from(profileCardAttribute(JsonObject(emptyMap()))))
        assertNull(ProfileCard.from(profileCardAttribute(buildJsonObject { put("design", "poster") }).copy(type = ProfileAttributeTypes.NAME)))
    }

    @Test
    fun aFirstSaveCreatesTheAnonymousCardAtPublicPriority() = runTest {
        val store = FakeCardStore()
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
        val existing = profileCardAttribute(
            Json.parseToJsonElement("""{"design":"board","overrides":{"socials":"bar"},"future":1}""") as JsonObject,
        )
        val store = FakeCardStore(listOf(existing))
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
        val existing = profileCardAttribute(
            Json.parseToJsonElement(
                """{"design":"dossier","overrides":{"palette":{"accent":"#ABCDEF"},"socials":"bar","portraits":[{"shape":"circle"}]}}""",
            ) as JsonObject,
        )
        val store = FakeCardStore(listOf(existing))
        CardRepository(store).savePublic(CardDesign.POSTER)

        assertEquals(
            Json.parseToJsonElement("""{"design":"poster","overrides":{"socials":"bar"}}"""),
            store.writes.single().data,
        )
    }

    @Test
    fun cardsListsPublicAndCircleCards() = runTest {
        val store = FakeCardStore(
            listOf(
                profileCardAttribute(buildJsonObject { put("design", "board") }),
                profileCardAttribute(buildJsonObject { put("design", "poster"); put("label", "Fam") }, ProfileVisibility.CONNECTED, listOf("c"), 0),
            ),
        )
        val cards = CardRepository(store).cards()

        assertEquals(2, cards.size)
        assertIs<CardAudience.Circle>(cards.single { it.priority == 0 }.audience)
        assertEquals("board", cards.publicCard()?.design)
    }

    @Test
    fun anUnsupportedServerIsRememberedAndNeverSurfacesAnError() = runTest {
        val store = FakeCardStore().apply { failWith = unknownType() }
        val repo = CardRepository(store)

        assertFalse(repo.savePublic(CardDesign.POSTER))

        store.failWith = IllegalStateException("must not be called again")
        assertFalse(repo.savePublic(CardDesign.BOARD))
        assertTrue(store.writes.isEmpty())
    }

    @Test
    fun anyOtherFailureStillSurfaces() = runTest {
        val store = FakeCardStore().apply {
            failWith = ClientException(status = 400, message = "Bad", correlationId = null, problem = ProblemDetails(title = "Bad"))
        }
        var thrown: Exception? = null
        try { CardRepository(store).savePublic(CardDesign.POSTER) } catch (e: ClientException) { thrown = e }
        assertNotNull(thrown)
    }

    @Test
    fun aConnectedCardWithNoCirclesOrSeveralIsNotACard() {
        val data = buildJsonObject { put("design", "board") }
        val none = profileCardAttribute(data, ProfileVisibility.CONNECTED, circles = emptyList())
        val two = profileCardAttribute(data, ProfileVisibility.CONNECTED, circles = listOf("a", "b"))

        assertTrue(listOf(none, two).profileCards().isEmpty())
        assertNull(listOf(none, two).profileCards().publicCard())
    }

    @Test
    fun anAuthenticatedCardIsNotThePublicCard() {
        val data = buildJsonObject { put("design", "board") }
        val cards = listOf(profileCardAttribute(data, ProfileVisibility.AUTHENTICATED)).profileCards()

        assertNull(cards.publicCard())
    }

    private val friends = CardAudience.Circle("c-friends", "Friends")

    @Test
    fun savingAnUnsavedCircleCardWritesItConnectedWithItsCirclePriorityAndLabel() = runTest {
        val store = FakeCardStore().apply { keepWrites = true }
        val card = ProfileCard(Uuid.NIL, Uuid.NIL, friends, CardDesign.DOSSIER, CardOverrides(socials = "bar"), priority = 20)

        val saved = assertNotNull(CardRepository(store).saveCircle(card))

        val write = store.writes.single()
        assertEquals(ProfileVisibility.CONNECTED, write.visibility)
        assertEquals(listOf("c-friends"), write.circleIds)
        assertEquals(20, write.priority)
        assertNull(write.id)
        assertEquals(JsonPrimitive("Friends"), write.data["label"])
        assertEquals(JsonPrimitive("dossier"), write.data["design"])
        assertEquals(friends, saved.audience)
        assertEquals(store.attributes.single().id, saved.id)
    }

    @Test
    fun editingACircleCardRewritesItInPlaceWithItsCircle() = runTest {
        val existing = profileCardAttribute(buildJsonObject { put("design", "poster"); put("label", "Friends") }, ProfileVisibility.CONNECTED, listOf("c-friends"), 2)
        val store = FakeCardStore(listOf(existing))
        val card = ProfileCard.from(existing)!!.copy(design = CardDesign.COLLAGE, priority = 20)

        assertNotNull(CardRepository(store).saveCircle(card))

        val write = store.writes.single()
        assertEquals(existing.id, write.id)
        assertEquals(existing.versionTag, write.versionTag)
        assertEquals(20, write.priority)
        assertEquals(listOf("c-friends"), write.circleIds)
        assertEquals(JsonPrimitive("collage"), write.data["design"])
        assertEquals(JsonPrimitive("Friends"), write.data["label"])
    }

    @Test
    fun aServerWithoutTheCardTypeMakesCircleCardsReadOnlyWithoutFurtherWrites() = runTest {
        val unknownType = "Unknown profile attribute type ${ProfileAttributeTypes.PROFILE_CARD}"
        val store = FakeCardStore().apply { failWith = ClientException(status = 400, message = unknownType, correlationId = null, problem = ProblemDetails(status = 400, title = unknownType)) }
        val repo = CardRepository(store)
        assertTrue(repo.supportsCircleCards)

        assertNull(repo.saveCircle(ProfileCard(Uuid.NIL, Uuid.NIL, friends, CardDesign.BOARD, priority = 20)))
        assertFalse(repo.supportsCircleCards)
        assertNull(repo.saveCircle(ProfileCard(Uuid.NIL, Uuid.NIL, friends, CardDesign.BOARD, priority = 20)))

        assertEquals(1, store.saveCalls)
        assertTrue(CardRepository(store).supportsCircleCards)
    }

    @Test
    fun anUnrelatedClientErrorOnACircleSaveStillThrows() = runTest {
        val store = FakeCardStore().apply { failWith = ClientException(status = 400, message = "nope", correlationId = null, problem = ProblemDetails(status = 400, title = "nope")) }
        val repo = CardRepository(store)

        var thrown = false
        try { repo.saveCircle(ProfileCard(Uuid.NIL, Uuid.NIL, friends, CardDesign.BOARD, priority = 20)) } catch (e: ClientException) { thrown = true }
        assertTrue(thrown)
        assertTrue(repo.supportsCircleCards)
    }

    @Test
    fun resettingACircleCardRemovesItsAttribute() = runTest {
        val circle = profileCardAttribute(buildJsonObject { put("design", "poster") }, ProfileVisibility.CONNECTED, listOf("c-friends"), 20)
        val public = profileCardAttribute(buildJsonObject { put("design", "board") })
        val store = FakeCardStore(listOf(public, circle))

        CardRepository(store).resetCircle(ProfileCard.from(circle)!!)

        assertEquals(listOf(circle.id), store.deleted)
        assertEquals(listOf(public.id), store.attributes.map { it.id })
    }

    @Test
    fun resettingAnUnsavedCircleCardSendsNothing() = runTest {
        val store = FakeCardStore()
        CardRepository(store).resetCircle(ProfileCard(Uuid.NIL, Uuid.NIL, friends, CardDesign.BOARD, priority = 20))
        assertTrue(store.deleted.isEmpty())
    }

    @Test
    fun resettingThePublicCardRemovesItAndLeavesCircleCards() = runTest {
        val circle = profileCardAttribute(buildJsonObject { put("design", "poster") }, ProfileVisibility.CONNECTED, listOf("c-friends"), 20)
        val public = profileCardAttribute(buildJsonObject { put("design", "board") })
        val store = FakeCardStore(listOf(public, circle))

        CardRepository(store).resetPublic()

        assertEquals(listOf(public.id), store.deleted)
        assertEquals(listOf(circle.id), store.attributes.map { it.id })
    }

    @Test
    fun fixedPrioritiesPutFamilyFriendsWorkFirstThenOthersByName() {
        val circles = listOf(
            CardCircle(WORK_CIRCLE_ID, "Work"), CardCircle("z", "Zed"), CardCircle("a", "Acquaintances"),
            CardCircle(FRIENDS_CIRCLE_ID, "friends"), CardCircle(FAMILY_CIRCLE_ID, "Family"),
        )
        val p = fixedCirclePriorities(circles)
        assertEquals(10, p[FAMILY_CIRCLE_ID])
        assertEquals(20, p[FRIENDS_CIRCLE_ID])
        assertEquals(30, p[WORK_CIRCLE_ID])
        assertEquals(40, p["a"])
        assertEquals(41, p["z"])
    }

    @Test
    fun aRenamedFamilyCircleStillGetsPriorityTenAndDashedIdsMatch() {
        val dashed = "cefc4f7c-bc8c-3476-2e0f-76703e7e174e"
        val p = fixedCirclePriorities(listOf(CardCircle(dashed, "Familie"), CardCircle("a", "Aunts")))
        assertEquals(10, p[dashed])
        assertEquals(40, p["a"])
    }
}
