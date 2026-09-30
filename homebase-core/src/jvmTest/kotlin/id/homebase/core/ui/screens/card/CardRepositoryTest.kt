package id.homebase.core.ui.screens.card

import id.homebase.api.client.ClientException
import id.homebase.api.client.ProblemDetails
import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.profile.ProfileAttribute
import id.homebase.api.client.profile.ProfileAttributeTypes
import id.homebase.api.client.profile.ProfileVisibility
import id.homebase.api.client.profile.ProfileWriteResponse
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

private fun circlesNeedConnected() = ClientException(
    status = 400,
    message = "CircleIds can only be set when visibility is Connected",
    correlationId = null,
    problem = ProblemDetails(status = 400, title = "CircleIds can only be set when visibility is Connected"),
)

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
        class Write(
            val data: JsonObject,
            val visibility: ProfileVisibility,
            val id: Uuid?,
            val versionTag: Uuid?,
            val priority: Int,
            val circleIds: List<String>,
        )

        val writes = mutableListOf<Write>()
        val deleted = mutableListOf<Uuid>()
        var failWith: Exception? = null

        // Mimics a server that keeps what it was sent: a write lands as a stored attribute.
        var keepWrites = false
        var dropCircleIds = false
        var circlesOnAnyVisibility = false
        var deleteResult: Boolean? = null
        var deleteThrows: Exception? = null

        override suspend fun load() = attributes

        override suspend fun save(data: JsonObject, visibility: ProfileVisibility, id: Uuid?, versionTag: Uuid?, priority: Int, circleIds: List<String>): ProfileWriteResponse {
            failWith?.let { throw it }
            if (!dropCircleIds && !circlesOnAnyVisibility && circleIds.isNotEmpty() && visibility != ProfileVisibility.CONNECTED) throw circlesNeedConnected()
            writes += Write(data, visibility, id, versionTag, priority, circleIds)
            val written = ProfileWriteResponse(id ?: Uuid.random(), Uuid.random())
            if (keepWrites) {
                val stored = circleIds.takeUnless { dropCircleIds }
                attributes = attributes.filter { it.id != id } + ProfileAttribute(
                    id = written.id,
                    type = ProfileAttributeTypes.PROFILE_CARD,
                    versionTag = written.versionTag,
                    visibility = visibility,
                    data = data,
                    acl = AccessControlList(requiredSecurityGroup = visibility.wireValue, circleIdList = stored?.takeIf { it.isNotEmpty() }),
                    priority = priority,
                )
            }
            return written
        }

        override suspend fun delete(id: Uuid, versionTag: Uuid): Boolean {
            deleteThrows?.let { throw it }
            deleteResult?.let { return it }
            deleted += id
            attributes = attributes.filter { it.id != id }
            return true
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
        assertTrue(CardRepository(store, inMemoryCardPreferences()).savePublic(CardDesign.POSTER))

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
        CardRepository(store, inMemoryCardPreferences()).savePublic(CardDesign.COLLAGE)

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
        CardRepository(store, inMemoryCardPreferences()).savePublic(CardDesign.POSTER)

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
        val cards = CardRepository(store, inMemoryCardPreferences()).cards()

        assertEquals(2, cards.size)
        assertIs<CardAudience.Circle>(cards.single { it.priority == 0 }.audience)
        assertEquals("board", cards.publicCard()?.design)
    }

    @Test
    fun anUnsupportedServerIsRememberedAndNeverSurfacesAnError() = runTest {
        val store = FakeStore().apply { failWith = unknownType() }
        val repo = CardRepository(store, inMemoryCardPreferences())

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
        try { CardRepository(store, inMemoryCardPreferences()).savePublic(CardDesign.POSTER) } catch (e: ClientException) { thrown = e }
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

    private val friends = CardAudience.Circle("c-friends", "Friends")

    @Test
    fun addingACircleCardWritesItConnectedWithItsCircleLabelAndNextFreePriority() = runTest {
        val store = FakeStore(
            listOf(
                attribute(buildJsonObject { put("design", "board") }),
                attribute(buildJsonObject { put("design", "poster"); put("label", "Fam") }, ProfileVisibility.CONNECTED, listOf("c-fam"), 3),
            ),
        ).apply { keepWrites = true }

        val result = CardRepository(store, inMemoryCardPreferences()).addCircle(friends, CardDesign.DOSSIER, CardOverrides(socials = "bar"))

        val write = store.writes.single()
        assertEquals(ProfileVisibility.CONNECTED, write.visibility)
        assertEquals(listOf("c-friends"), write.circleIds)
        assertEquals(4, write.priority)
        assertNull(write.id)
        assertEquals(JsonPrimitive("Friends"), write.data["label"])
        assertEquals(JsonPrimitive("dossier"), write.data["design"])
        val added = assertIs<AddCircleCardResult.Added>(result).card
        assertEquals(friends, added.audience)
        assertEquals(4, added.priority)
    }

    @Test
    fun aFirstCircleCardTakesPriorityZero() = runTest {
        val store = FakeStore(listOf(attribute(buildJsonObject { put("design", "board") }))).apply { keepWrites = true }
        CardRepository(store, inMemoryCardPreferences()).addCircle(friends, CardDesign.BOARD, CardOverrides.EMPTY)
        assertEquals(0, store.writes.single().priority)
    }

    @Test
    fun aServerThatDropsCircleIdsOnlyEverSeesAnOwnerOnlyCardWhichIsRemovedAndRemembered() = runTest {
        val store = FakeStore(listOf(attribute(buildJsonObject { put("design", "board") })))
            .apply { keepWrites = true; dropCircleIds = true }
        val repo = CardRepository(store, inMemoryCardPreferences())
        assertTrue(repo.supportsCircleCards)

        assertEquals(AddCircleCardResult.Unsupported, repo.addCircle(friends, CardDesign.BOARD, CardOverrides.EMPTY))

        assertEquals(listOf(ProfileVisibility.OWNER), store.writes.map { it.visibility })
        assertEquals(1, store.deleted.size)
        assertEquals(1, store.attributes.size)
        assertEquals(ProfileVisibility.ANONYMOUS, store.attributes.single().visibility)
        assertFalse(repo.supportsCircleCards)
        assertEquals(AddCircleCardResult.Unsupported, repo.addCircle(friends, CardDesign.BOARD, CardOverrides.EMPTY))
        assertEquals(1, store.writes.size)
    }

    @Test
    fun aThrowingCleanupOfTheOwnerOnlyCardIsNotAFailure() = runTest {
        val store = FakeStore(listOf(attribute(buildJsonObject { put("design", "board") })))
            .apply { keepWrites = true; dropCircleIds = true; deleteThrows = IllegalStateException("offline") }
        val repo = CardRepository(store, inMemoryCardPreferences())

        assertEquals(AddCircleCardResult.Unsupported, repo.addCircle(friends, CardDesign.BOARD, CardOverrides.EMPTY))

        assertFalse(repo.supportsCircleCards)
        assertEquals(ProfileVisibility.OWNER, store.attributes.single { it.visibility != ProfileVisibility.ANONYMOUS }.visibility)
        assertTrue(repo.cards().none { it.audience is CardAudience.Circle })
    }

    @Test
    fun theUnsupportedAnswerOutlivesTheRepository() = runTest {
        val driver = id.homebase.core.feed.newInMemoryJdbcDriver()
        val store = FakeStore(listOf(attribute(buildJsonObject { put("design", "board") })))
            .apply { keepWrites = true; dropCircleIds = true }
        CardRepository(store, inMemoryCardPreferences(driver)).addCircle(friends, CardDesign.BOARD, CardOverrides.EMPTY)

        val relaunched = CardRepository(store, inMemoryCardPreferences(driver))

        assertFalse(relaunched.supportsCircleCards)
        assertEquals(AddCircleCardResult.Unsupported, relaunched.addCircle(friends, CardDesign.BOARD, CardOverrides.EMPTY))
        assertEquals(1, store.writes.size)
    }

    @Test
    fun aServerThatKeepsCirclesOnAnOwnerCardGetsItPromotedInPlace() = runTest {
        val store = FakeStore(listOf(attribute(buildJsonObject { put("design", "board") })))
            .apply { keepWrites = true; circlesOnAnyVisibility = true }

        val added = assertIs<AddCircleCardResult.Added>(CardRepository(store, inMemoryCardPreferences()).addCircle(friends, CardDesign.BOARD, CardOverrides.EMPTY)).card

        assertEquals(listOf(ProfileVisibility.OWNER, ProfileVisibility.CONNECTED), store.writes.map { it.visibility })
        assertNull(store.writes[0].id)
        assertEquals(added.id, store.writes[1].id)
        assertEquals(listOf("c-friends"), store.writes[1].circleIds)
        assertEquals(listOf(added), store.attributes.profileCards().filter { it.audience is CardAudience.Circle })
    }

    @Test
    fun aStrayCardAlreadyGoneOnTheServerCountsAsRemoved() = runTest {
        val store = FakeStore(listOf(attribute(buildJsonObject { put("design", "board") })))
            .apply { keepWrites = true; dropCircleIds = true; deleteResult = false }
        val repo = CardRepository(store, inMemoryCardPreferences())

        assertEquals(AddCircleCardResult.Unsupported, repo.addCircle(friends, CardDesign.BOARD, CardOverrides.EMPTY))
        assertFalse(repo.supportsCircleCards)
    }

    @Test
    fun anAddOnAServerWithoutTheCardTypeIsUnsupportedAndQuiet() = runTest {
        val store = FakeStore().apply { failWith = unknownType() }
        val repo = CardRepository(store, inMemoryCardPreferences())
        assertEquals(AddCircleCardResult.Unsupported, repo.addCircle(friends, CardDesign.BOARD, CardOverrides.EMPTY))
        assertFalse(repo.supportsCircleCards)
    }

    @Test
    fun editingACircleCardRewritesItInPlaceWithItsCircle() = runTest {
        val existing = attribute(buildJsonObject { put("design", "poster"); put("label", "Friends") }, ProfileVisibility.CONNECTED, listOf("c-friends"), 2)
        val store = FakeStore(listOf(existing))
        val card = ProfileCard.from(existing)!!.copy(design = CardDesign.COLLAGE)

        assertTrue(CardRepository(store, inMemoryCardPreferences()).saveCircle(card))

        val write = store.writes.single()
        assertEquals(existing.id, write.id)
        assertEquals(existing.versionTag, write.versionTag)
        assertEquals(2, write.priority)
        assertEquals(listOf("c-friends"), write.circleIds)
        assertEquals(JsonPrimitive("collage"), write.data["design"])
        assertEquals(JsonPrimitive("Friends"), write.data["label"])
    }

    @Test
    fun deletingACircleCardRemovesItAndThePublicCardCannotBeDeleted() = runTest {
        val circle = attribute(buildJsonObject { put("design", "poster") }, ProfileVisibility.CONNECTED, listOf("c-friends"), 0)
        val public = attribute(buildJsonObject { put("design", "board") })
        val store = FakeStore(listOf(public, circle))
        val repo = CardRepository(store, inMemoryCardPreferences())

        assertTrue(repo.delete(ProfileCard.from(circle)!!))
        assertEquals(listOf(circle.id), store.deleted)

        var refused = false
        try { repo.delete(ProfileCard.from(public)!!) } catch (e: IllegalArgumentException) { refused = true }
        assertTrue(refused)
        assertEquals(1, store.deleted.size)
    }
}
