@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.card

import id.homebase.api.client.profile.ProfileAttributeTypes
import id.homebase.core.ui.screens.card.ProfileCardViewModelTest.FakeHost
import id.homebase.core.ui.screens.card.ProfileCardViewModelTest.FakeSource
import id.homebase.core.ui.screens.profile.ProfileAudience
import id.homebase.core.ui.screens.profile.ProfileField
import id.homebase.core.ui.screens.profile.keepingCircles
import id.homebase.core.ui.screens.profile.keepingOtherCircles
import id.homebase.core.ui.screens.profile.visibleValues
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class CardContentTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val family = CardAudience.Circle(FAMILY_CIRCLE_ID, "Family")
    private val friends = CardAudience.Circle(FRIENDS_CIRCLE_ID, "Friends")
    private val work = CardAudience.Circle(WORK_CIRCLE_ID, "Work")
    private val phone = "+14155550123"

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class Rig(val wire: CardWireHarness, val host: FakeHost, val vm: ProfileCardViewModel, val source: FakeSource)

    private fun JsonObject.str(vararg path: String): String? {
        var node: JsonElement = this
        for (key in path) node = (node as? JsonObject)?.get(key) ?: return null
        return node.jsonPrimitive.content
    }

    private fun data(key: String, value: String) = JsonObject(mapOf(key to JsonPrimitive(value)))

    private fun CardWireHarness.seed(type: String, visibility: String, data: JsonObject, circles: List<String>? = null): Uuid =
        Uuid.random().also { seed(it, Uuid.random(), type, visibility, data, 0, circles) }

    private suspend fun rig(wire: CardWireHarness, withCards: Boolean = false, serverCircles: Set<String>? = null): Rig {
        wire.seed(ProfileAttributeTypes.NAME, "anonymous", data(ProfileAttributeTypes.KEY_GIVEN_NAME, "Frodo"))
        val repository = wire.profileRepository()
        val source = FakeSource(emptyList(), cardRepository = if (withCards) CardRepository(ProfileRepositoryCardStore(repository)) else null).apply {
            profileRepository = repository
            liveCards = { repository.loadAttributes() }
            circleList = listOf(CardCircle(FAMILY_CIRCLE_ID, "Family"), CardCircle(FRIENDS_CIRCLE_ID, "Friends"), CardCircle(WORK_CIRCLE_ID, "Work"))
            this.serverCircles = serverCircles
        }
        val host = FakeHost()
        val vm = ProfileCardViewModel(source) { host }.also { it.startHost() }
        vm.await { it.attributes.isNotEmpty() && it.cards.size == 4 }
        withContext(Dispatchers.Default) { withTimeout(10.seconds) { while (host.rendered.isEmpty()) delay(10) } }
        return Rig(wire, host, vm, source)
    }

    private suspend fun ProfileCardViewModel.await(condition: (ProfileCardUiState) -> Boolean) =
        withContext(Dispatchers.Default) { withTimeout(10.seconds) { uiState.first(condition) } }

    private suspend fun Rig.open(card: CardAudience, stored: Boolean = false) {
        vm.onCardOpened(card)
        vm.await { it.selectedAudience == card && (!stored || it.selectedCard?.isDefault == false) }
        val expected = if (card is CardAudience.Circle) CardAudiencePayload("circle", card.label) else CardAudiencePayload("public")
        withContext(Dispatchers.Default) {
            withTimeout(10.seconds) { while (host.rendered.lastOrNull()?.audience != expected) delay(10) }
        }
    }

    private suspend fun Rig.write(block: () -> Unit) {
        vm.await { !it.isContentBusy }
        val before = wire.puts
        block()
        withContext(Dispatchers.Default) { withTimeout(10.seconds) { while (wire.puts == before) delay(10) } }
        vm.await { !it.isContentBusy }
    }

    private fun Rig.item(type: String) = vm.uiState.value.contentItems.single { it.type == type }

    private fun Rig.circleIds() = wire.putBodies.last().jsonObject["circleIds"]?.jsonArray?.map { it.jsonPrimitive.content }

    @Test
    fun phoneTurnedOnInFriendsIsOffInFamilyAndPublicWithNothingRetyped() = runTest(dispatcher) {
        val wire = CardWireHarness()
        wire.seed(ProfileAttributeTypes.PHONE, "owner", data(ProfileAttributeTypes.KEY_PHONE, phone))
        val rig = rig(wire)

        rig.open(friends)
        assertFalse(rig.item(ProfileAttributeTypes.PHONE).shown)
        rig.write { rig.vm.onContentToggled(rig.item(ProfileAttributeTypes.PHONE).id, true) }

        val put = wire.putBodies.single().jsonObject
        assertEquals("connected", put.str("visibility"))
        assertEquals(listOf(FRIENDS_CIRCLE_ID), rig.circleIds())
        assertEquals(phone, put.str("data", ProfileAttributeTypes.KEY_PHONE))
        assertTrue(rig.item(ProfileAttributeTypes.PHONE).shown)

        rig.open(family)
        assertFalse(rig.item(ProfileAttributeTypes.PHONE).shown)
        rig.open(CardAudience.Public)
        assertFalse(rig.item(ProfileAttributeTypes.PHONE).shown)
        rig.open(friends)
        assertTrue(rig.item(ProfileAttributeTypes.PHONE).shown)
        assertEquals(1, wire.puts)
    }

    @Test
    fun nameCannotBeSwitchedOffThePublicCard() = runTest(dispatcher) {
        val wire = CardWireHarness()
        val rig = rig(wire)
        rig.open(CardAudience.Public)

        val name = rig.item(ProfileAttributeTypes.NAME)
        assertTrue(name.required && name.shown)
        rig.vm.onContentToggled(name.id, false)

        assertFalse(rig.vm.uiState.value.isContentBusy)
        assertEquals(0, wire.puts)
    }

    @Test
    fun emailAddedFromTheWorkCardIsOneAttributeVisibleToWorkOnly() = runTest(dispatcher) {
        val wire = CardWireHarness()
        val rig = rig(wire)
        rig.open(work)

        rig.write { rig.vm.onContentAdded(ProfileAttributeTypes.EMAIL, mapOf(ProfileAttributeTypes.KEY_EMAIL to "frodo@shire.me")) }

        assertEquals(1, wire.puts)
        val put = wire.putBodies.single().jsonObject
        assertEquals(ProfileAttributeTypes.EMAIL, put.str("type"))
        assertEquals("connected", put.str("visibility"))
        assertEquals(listOf(WORK_CIRCLE_ID), rig.circleIds())
        assertEquals("frodo@shire.me", put.str("data", ProfileAttributeTypes.KEY_EMAIL))
        assertEquals(1, wire.storedBodies.count { it.str("type") == ProfileAttributeTypes.EMAIL })

        val stored = wire.profileRepository().loadAttributes()
        fun emailOn(card: CardAudience) = stored.visibleValues(card.aclFilter())[ProfileField.EMAIL]
        assertEquals("frodo@shire.me", emailOn(work))
        assertEquals("", emailOn(friends))
        assertEquals("", emailOn(family))
        assertEquals("", emailOn(CardAudience.Public))
        assertTrue(rig.item(ProfileAttributeTypes.EMAIL).shown)
        rig.open(friends)
        assertFalse(rig.item(ProfileAttributeTypes.EMAIL).shown)
    }

    @Test
    fun aSecondEmailIsItsOwnItemAndEachGoesOnACardOnItsOwn() = runTest(dispatcher) {
        val wire = CardWireHarness()
        val home = wire.seed(ProfileAttributeTypes.EMAIL, "owner", data(ProfileAttributeTypes.KEY_EMAIL, "frodo@shire.me"))
        val rig = rig(wire)
        rig.open(work)

        rig.write { rig.vm.onContentAdded(ProfileAttributeTypes.EMAIL, mapOf(ProfileAttributeTypes.KEY_EMAIL to "baggins@bagend.me")) }

        assertEquals(2, wire.storedBodies.count { it.str("type") == ProfileAttributeTypes.EMAIL })
        fun emails() = rig.vm.uiState.value.contentItems.filter { it.type == ProfileAttributeTypes.EMAIL }
        assertEquals(setOf("frodo@shire.me", "baggins@bagend.me"), emails().map { it.text }.toSet())
        val work2 = emails().single { it.id != home }
        assertTrue(work2.shown)
        assertFalse(emails().single { it.id == home }.shown)

        rig.open(friends)
        rig.write { rig.vm.onContentToggled(home, true) }
        assertTrue(emails().single { it.id == home }.shown)
        assertFalse(emails().single { it.id == work2.id }.shown)

        rig.open(work)
        assertTrue(emails().single { it.id == work2.id }.shown)
        assertFalse(emails().single { it.id == home }.shown)
        rig.write { rig.vm.onContentToggled(work2.id, false) }
        assertTrue(emails().none { it.shown })
        rig.open(friends)
        assertTrue(emails().single { it.id == home }.shown)
        assertEquals(2, wire.storedBodies.count { it.str("type") == ProfileAttributeTypes.EMAIL })
    }

    @Test
    fun aPublicItemIsLockedOnACircleCardAndCannotBeSwitchedOffSilently() = runTest(dispatcher) {
        val wire = CardWireHarness()
        val bio = wire.seed(ProfileAttributeTypes.BIO_SUMMARY, "anonymous", data(ProfileAttributeTypes.KEY_SHORT_BIO, "Second breakfast"))
        val rig = rig(wire)
        rig.open(friends)

        val item = rig.item(ProfileAttributeTypes.BIO_SUMMARY)
        assertTrue(item.shown && item.locked)
        rig.vm.onContentToggled(bio, false)
        assertEquals(0, wire.puts)
        assertTrue(rig.item(ProfileAttributeTypes.BIO_SUMMARY).shown)

        rig.write { rig.vm.onContentAudienceChanged(bio, ProfileAudience.Circles(setOf(FAMILY_CIRCLE_ID))) }
        assertEquals(listOf(FAMILY_CIRCLE_ID), rig.circleIds())
        assertFalse(rig.item(ProfileAttributeTypes.BIO_SUMMARY).shown)
        assertFalse(rig.item(ProfileAttributeTypes.BIO_SUMMARY).locked)
    }

    @Test
    fun publicCardTurnsAnItemOnLiveAndOffBackToItsPreviousCircles() = runTest(dispatcher) {
        val wire = CardWireHarness()
        val bio = wire.seed(
            ProfileAttributeTypes.BIO_SUMMARY, "connected", data(ProfileAttributeTypes.KEY_SHORT_BIO, "Second breakfast"),
            listOf(FAMILY_CIRCLE_ID, FRIENDS_CIRCLE_ID),
        )
        val rig = rig(wire)
        assertNull(rig.host.rendered.last().data.bio)
        assertFalse(rig.item(ProfileAttributeTypes.BIO_SUMMARY).shown)

        rig.write { rig.vm.onContentToggled(bio, true) }
        assertEquals("anonymous", wire.putBodies.last().jsonObject.str("visibility"))
        assertNull(rig.circleIds())
        assertEquals("Second breakfast", rig.host.rendered.last().data.bio)

        rig.write { rig.vm.onContentToggled(bio, false) }
        assertEquals("connected", wire.putBodies.last().jsonObject.str("visibility"))
        assertEquals(setOf(FAMILY_CIRCLE_ID, FRIENDS_CIRCLE_ID), rig.circleIds()!!.toSet())
        assertNull(rig.host.rendered.last().data.bio)
    }

    @Test
    fun publicCardOffWithNoPreviousCirclesFallsBackToOnlyMe() = runTest(dispatcher) {
        val wire = CardWireHarness()
        val bio = wire.seed(ProfileAttributeTypes.BIO_SUMMARY, "anonymous", data(ProfileAttributeTypes.KEY_SHORT_BIO, "Second breakfast"))
        val rig = rig(wire)

        rig.write { rig.vm.onContentToggled(bio, false) }

        assertEquals("owner", wire.putBodies.last().jsonObject.str("visibility"))
        assertNull(rig.circleIds())
    }

    @Test
    fun legacyConnectedWithNoCirclesShowsOnEveryCircleAndBecomesAnExplicitListOnFirstChange() = runTest(dispatcher) {
        val wire = CardWireHarness()
        val id = wire.seed(ProfileAttributeTypes.PHONE, "connected", data(ProfileAttributeTypes.KEY_PHONE, phone))
        val rig = rig(wire)
        for (card in listOf(family, friends, work)) {
            rig.open(card)
            assertTrue(rig.item(ProfileAttributeTypes.PHONE).shown, "$card")
        }
        assertEquals(0, wire.puts)

        rig.open(friends)
        rig.write { rig.vm.onContentToggled(id, false) }

        assertEquals("connected", wire.putBodies.single().jsonObject.str("visibility"))
        assertEquals(setOf(FAMILY_CIRCLE_ID, WORK_CIRCLE_ID), rig.circleIds()!!.toSet())
    }

    @Test
    fun anExistingTypeIsFilledNotDuplicatedWhenAddedAgain() = runTest(dispatcher) {
        val wire = CardWireHarness()
        wire.seed(ProfileAttributeTypes.PHONE, "owner", JsonObject(emptyMap()))
        val rig = rig(wire)
        rig.open(work)

        rig.write { rig.vm.onContentAdded(ProfileAttributeTypes.PHONE, mapOf(ProfileAttributeTypes.KEY_PHONE to phone)) }

        assertEquals(1, wire.storedBodies.count { it.str("type") == ProfileAttributeTypes.PHONE })
        assertEquals(listOf(WORK_CIRCLE_ID), rig.circleIds())
        assertTrue(rig.item(ProfileAttributeTypes.PHONE).shown)
    }

    private fun CardWireHarness.acls(): Map<String, Pair<String?, List<String>?>> =
        storedBodies.filter { it.str("type") != ProfileAttributeTypes.PROFILE_CARD }.associate {
            "${it.str("type")}:${it.str("data", ProfileAttributeTypes.KEY_LINK_TARGET) ?: ""}" to
                (it.str("visibility") to it["circleIds"]?.jsonArray?.map { id -> id.jsonPrimitive.content })
        }

    private fun CardWireHarness.seedFriendsAndFamilyContent() {
        seed(ProfileAttributeTypes.PHONE, "connected", data(ProfileAttributeTypes.KEY_PHONE, phone), listOf(FRIENDS_CIRCLE_ID))
        // Unsorted on purpose: Undo must put back the order the server held, not a sorted copy.
        seed(ProfileAttributeTypes.EMAIL, "connected", data(ProfileAttributeTypes.KEY_EMAIL, "frodo@shire.me"), listOf(FRIENDS_CIRCLE_ID, FAMILY_CIRCLE_ID))
        seed(ProfileAttributeTypes.LINK, "connected", data(ProfileAttributeTypes.KEY_LINK_TARGET, "https://shire.example/f"), listOf(FRIENDS_CIRCLE_ID))
        seed(ProfileAttributeTypes.TWITTER, "connected", data(ProfileAttributeTypes.KEY_TWITTER, "frodo"), listOf(FAMILY_CIRCLE_ID))
        seed(ProfileAttributeTypes.INSTAGRAM, "owner", data(ProfileAttributeTypes.KEY_INSTAGRAM, "frodo"))
        seed(ProfileAttributeTypes.BIO_SUMMARY, "anonymous", data(ProfileAttributeTypes.KEY_SHORT_BIO, "Second breakfast"))
    }

    private suspend fun Rig.import(from: CardAudience, expectedPuts: Int) {
        vm.await { !it.isContentBusy }
        val before = wire.puts
        vm.onImportRequested(from)
        withContext(Dispatchers.Default) { withTimeout(10.seconds) { while (wire.puts < before + expectedPuts) delay(10) } }
        vm.await { !it.isContentBusy }
    }

    @Test
    fun copyingFriendsToWorkWidensEveryFriendsItemToWorkAndChangesNothingElse() = runTest(dispatcher) {
        val wire = CardWireHarness()
        wire.seedFriendsAndFamilyContent()
        val rig = rig(wire)
        val before = wire.acls()
        rig.open(work)
        assertTrue(rig.vm.uiState.value.importSources.any { it.audience == friends })

        rig.import(friends, expectedPuts = 3)

        val after = wire.acls()
        assertEquals(3, wire.puts)
        assertEquals("connected" to listOf(WORK_CIRCLE_ID, FRIENDS_CIRCLE_ID), after["${ProfileAttributeTypes.PHONE}:"])
        assertEquals("connected" to listOf(WORK_CIRCLE_ID, FRIENDS_CIRCLE_ID, FAMILY_CIRCLE_ID), after["${ProfileAttributeTypes.EMAIL}:"])
        assertEquals("connected" to listOf(WORK_CIRCLE_ID, FRIENDS_CIRCLE_ID), after["${ProfileAttributeTypes.LINK}:https://shire.example/f"])
        val untouched = before.keys - setOf("${ProfileAttributeTypes.PHONE}:", "${ProfileAttributeTypes.EMAIL}:", "${ProfileAttributeTypes.LINK}:https://shire.example/f")
        assertTrue(untouched.isNotEmpty())
        for (key in untouched) assertEquals(before[key], after[key], key)
        assertTrue(rig.item(ProfileAttributeTypes.PHONE).shown)
        assertTrue(rig.item(ProfileAttributeTypes.EMAIL).shown)
        assertFalse(rig.item(ProfileAttributeTypes.TWITTER).shown)
        assertEquals(listOf("https://shire.example/f"), rig.host.rendered.last().data.links.map { it.target })
        assertTrue(rig.vm.uiState.value.importSources.none { it.audience == friends })
    }

    @Test
    fun undoPutsTheExactPreviousAclsBack() = runTest(dispatcher) {
        val wire = CardWireHarness()
        wire.seedFriendsAndFamilyContent()
        val rig = rig(wire)
        val before = wire.acls()
        rig.open(work)
        rig.import(friends, expectedPuts = 3)
        assertTrue(before != wire.acls())

        rig.vm.onImportUndone()
        withContext(Dispatchers.Default) { withTimeout(10.seconds) { while (wire.puts < 6) delay(10) } }
        rig.vm.await { !it.isContentBusy }

        assertEquals(before, wire.acls())
        assertEquals(listOf(FRIENDS_CIRCLE_ID, FAMILY_CIRCLE_ID), wire.acls()["${ProfileAttributeTypes.EMAIL}:"]!!.second)
        assertFalse(rig.item(ProfileAttributeTypes.PHONE).shown)
        assertTrue(rig.vm.uiState.value.importSources.any { it.audience == friends })
        rig.vm.onImportUndone()
        assertEquals(6, wire.puts)
    }

    @Test
    fun importingOntoPublicWaitsForTheConfirmationAndThenMakesTheItemsAnonymous() = runTest(dispatcher) {
        val wire = CardWireHarness()
        wire.seedFriendsAndFamilyContent()
        val rig = rig(wire)
        rig.open(CardAudience.Public)

        rig.vm.onImportRequested(friends)
        assertEquals(friends, rig.vm.uiState.value.pendingImport)
        assertEquals(0, wire.puts)
        rig.vm.onImportCancelled()
        assertNull(rig.vm.uiState.value.pendingImport)
        assertEquals(0, wire.puts)

        rig.vm.onImportRequested(friends)
        rig.vm.onImportConfirmed()
        withContext(Dispatchers.Default) { withTimeout(10.seconds) { while (wire.puts < 3) delay(10) } }
        rig.vm.await { !it.isContentBusy }

        assertNull(rig.vm.uiState.value.pendingImport)
        val after = wire.acls()
        assertEquals("anonymous" to null, after["${ProfileAttributeTypes.PHONE}:"])
        assertEquals("anonymous" to null, after["${ProfileAttributeTypes.EMAIL}:"])
        assertEquals("anonymous" to null, after["${ProfileAttributeTypes.LINK}:https://shire.example/f"])
        assertEquals("connected" to listOf(FAMILY_CIRCLE_ID), after["${ProfileAttributeTypes.TWITTER}:"])
    }

    @Test
    fun editingAnImportedItemRetiresTheUndo() = runTest(dispatcher) {
        val wire = CardWireHarness()
        wire.seedFriendsAndFamilyContent()
        val rig = rig(wire)
        rig.open(work)
        rig.import(friends, expectedPuts = 3)
        val phoneId = rig.vm.uiState.value.attributes.single { it.type == ProfileAttributeTypes.PHONE }.id

        rig.write { rig.vm.onContentAudienceChanged(phoneId, ProfileAudience.Public) }
        val putsAfterEdit = wire.puts
        rig.vm.onImportUndone()
        rig.vm.await { !it.isContentBusy }

        assertEquals(putsAfterEdit, wire.puts)
    }

    @Test
    fun leavingTheCardRetiresTheUndo() = runTest(dispatcher) {
        val wire = CardWireHarness()
        wire.seedFriendsAndFamilyContent()
        val rig = rig(wire)
        rig.open(work)
        rig.import(friends, expectedPuts = 3)

        rig.vm.onEditorClosed()
        val puts = wire.puts
        rig.vm.onImportUndone()
        rig.vm.await { !it.isContentBusy }

        assertEquals(puts, wire.puts)
    }

    @Test
    fun anUndoneImportOntoPublicLeavesNoStaleAudienceForALaterRemoval() = runTest(dispatcher) {
        val wire = CardWireHarness()
        wire.seedFriendsAndFamilyContent()
        val rig = rig(wire)
        rig.open(CardAudience.Public)
        rig.vm.onImportRequested(friends)
        rig.vm.onImportConfirmed()
        withContext(Dispatchers.Default) { withTimeout(10.seconds) { while (wire.puts < 3) delay(10) } }
        rig.vm.await { !it.isContentBusy }
        rig.vm.onImportUndone()
        withContext(Dispatchers.Default) { withTimeout(10.seconds) { while (wire.puts < 6) delay(10) } }
        rig.vm.await { !it.isContentBusy }
        val phoneId = rig.vm.uiState.value.attributes.single { it.type == ProfileAttributeTypes.PHONE }.id

        rig.write { rig.vm.onContentAudienceChanged(phoneId, ProfileAudience.Public) }
        rig.write { rig.vm.onContentToggled(phoneId, false) }

        assertEquals("owner", wire.putBodies.last().jsonObject.str("visibility"))
        assertNull(rig.circleIds())
    }

    private fun CardWireHarness.seedPhoto(visibility: String, circles: List<String>?): Uuid =
        Uuid.random().also {
            seed(it, Uuid.random(), ProfileAttributeTypes.PHOTO, visibility, data(ProfileAttributeTypes.KEY_PROFILE_IMAGE, "photo-$visibility"), 0, circles)
        }

    @Test
    fun copyingFromFamilyLeavesFamilysOwnPhotoScopedToFamilyAlone() = runTest(dispatcher) {
        val wire = CardWireHarness()
        val photo = wire.seedPhoto("connected", listOf(FAMILY_CIRCLE_ID))
        wire.seed(ProfileAttributeTypes.PHONE, "connected", data(ProfileAttributeTypes.KEY_PHONE, phone), listOf(FAMILY_CIRCLE_ID))
        val rig = rig(wire)
        rig.open(friends)
        assertEquals(1, rig.vm.uiState.value.importSources.single { it.audience == family }.newItems)

        rig.import(family, expectedPuts = 1)

        val put = wire.putBodies.single().jsonObject
        assertEquals(ProfileAttributeTypes.PHONE, put.str("type"))
        val attributes = rig.vm.uiState.value.attributes
        assertEquals(photo, attributes.ownPhotoFor(family)?.id)
        assertNull(attributes.ownPhotoFor(friends))
        assertEquals(listOf(FAMILY_CIRCLE_ID), attributes.single { it.id == photo }.acl.circleIdList)
    }

    @Test
    fun aCardWithOnlyAPhotoHasNothingToCopyFromIt() = runTest(dispatcher) {
        val wire = CardWireHarness()
        wire.seedPhoto("connected", listOf(FAMILY_CIRCLE_ID))
        val rig = rig(wire)

        rig.open(friends)

        assertTrue(rig.vm.uiState.value.importSources.none { it.audience == family })
    }

    @Test
    fun aCardWithItsOwnPhotoKeepsItWhenCopyingFromAnotherCard() = runTest(dispatcher) {
        val wire = CardWireHarness()
        wire.seedPhoto("connected", listOf(FAMILY_CIRCLE_ID))
        wire.seedPhoto("connected", listOf(FRIENDS_CIRCLE_ID))
        val rig = rig(wire)

        rig.open(friends)

        assertTrue(rig.vm.uiState.value.importSources.none { it.audience == family })
    }

    @Test
    fun aSourceWithNothingNewIsNotOffered() = runTest(dispatcher) {
        val wire = CardWireHarness()
        wire.seedFriendsAndFamilyContent()
        val rig = rig(wire)

        rig.open(friends)
        assertEquals(listOf<CardAudience>(family), rig.vm.uiState.value.importSources.map { it.audience })
        assertEquals(1, rig.vm.uiState.value.importSources.single().newItems)

        rig.open(family)
        assertEquals(listOf(friends), rig.vm.uiState.value.importSources.map { it.audience })

        rig.open(CardAudience.Public)
        assertEquals(setOf<CardAudience>(family, friends), rig.vm.uiState.value.importSources.map { it.audience }.toSet())
        assertTrue(rig.vm.uiState.value.importSources.none { it.audience == work })

        val empty = rig(CardWireHarness())
        empty.open(work)
        assertTrue(empty.vm.uiState.value.importSources.isEmpty())
        empty.vm.onImportRequested(friends)
        assertEquals(0, empty.wire.puts)
    }

    @Test
    fun aFailurePartWayThroughAnImportPutsTheEarlierItemsBack() = runTest(dispatcher) {
        val wire = CardWireHarness(putReply = { n -> if (n == 2) CardWireHarness.Reply.Problem(500, "{}") else CardWireHarness.Reply.Ok })
        wire.seedFriendsAndFamilyContent()
        val rig = rig(wire)
        val before = wire.acls()
        rig.open(work)

        rig.vm.onImportRequested(friends)
        withContext(Dispatchers.Default) { withTimeout(10.seconds) { while (wire.puts < 3) delay(10) } }
        rig.vm.await { !it.isContentBusy }

        assertEquals(before, wire.acls())
        assertEquals(1, rig.vm.uiState.value.contentFailures)
        rig.vm.onImportUndone()
        assertEquals(3, wire.puts)
    }

    @Test
    fun aThirdPutFailingAfterTwoSucceededRollsBothEarlierAclsBackToTheOriginals() = runTest(dispatcher) {
        val wire = CardWireHarness(putReply = { n -> if (n == 3) CardWireHarness.Reply.Problem(500, "{}") else CardWireHarness.Reply.Ok })
        wire.seedFriendsAndFamilyContent()
        val rig = rig(wire)
        val before = wire.acls()
        rig.open(work)

        rig.import(friends, expectedPuts = 5)

        assertEquals(5, wire.puts)
        assertEquals(before, wire.acls())
        assertEquals(1, rig.vm.uiState.value.contentFailures)
    }

    @Test
    fun aRollbackThatFailsTooReportsTheFailureAndKeepsTheListAtWhatTheServerHolds() = runTest(dispatcher) {
        val wire = CardWireHarness(putReply = { n -> if (n == 3 || n == 4) CardWireHarness.Reply.Problem(500, "{}") else CardWireHarness.Reply.Ok })
        wire.seedFriendsAndFamilyContent()
        val rig = rig(wire)
        rig.open(work)
        val events = mutableListOf<ProfileCardEvent>()
        val collecting = backgroundScope.launch(Dispatchers.Unconfined) { rig.vm.events.collect { events += it } }

        rig.import(friends, expectedPuts = 5)

        assertEquals(1, rig.vm.uiState.value.contentFailures)
        assertTrue(ProfileCardEvent.ContentSaveFailed in events)
        val widened = wire.acls()["${ProfileAttributeTypes.PHONE}:"]
        assertEquals(widened?.second?.contains(WORK_CIRCLE_ID), rig.item(ProfileAttributeTypes.PHONE).shown)
        collecting.cancel()
    }

    @Test
    fun anUndoWhoseRestoreFailsReportsItAndCanBeRetried() = runTest(dispatcher) {
        val wire = CardWireHarness(putReply = { n -> if (n == 5) CardWireHarness.Reply.Problem(500, "{}") else CardWireHarness.Reply.Ok })
        wire.seedFriendsAndFamilyContent()
        val rig = rig(wire)
        val before = wire.acls()
        rig.open(work)
        rig.import(friends, expectedPuts = 3)

        rig.vm.onImportUndone()
        withContext(Dispatchers.Default) { withTimeout(10.seconds) { while (wire.puts < 6) delay(10) } }
        rig.vm.await { !it.isContentBusy }

        assertEquals(1, rig.vm.uiState.value.contentFailures)
        assertTrue(before != wire.acls())
        rig.vm.onImportUndone()
        withContext(Dispatchers.Default) { withTimeout(10.seconds) { while (wire.puts < 7) delay(10) } }
        rig.vm.await { !it.isContentBusy }
        assertEquals(before, wire.acls())
    }

    // The demo identity's Twitter ACL still named its deleted Work circle, and Undo wrote it back verbatim: 400.
    private val deletedCircle = "7a1c3e5f9b2d4f6a8c0e1b3d5f7a9c2e"
    private val hiddenCircle = "5d3e7b2c1a0f4e6d8c9b0a1f2e3d4c5b"
    private val onServer = setOf(FAMILY_CIRCLE_ID, FRIENDS_CIRCLE_ID, WORK_CIRCLE_ID, hiddenCircle)

    @Test
    fun undoingACopyOntoPublicDropsADeletedCircleButKeepsOnesTheAppHides() = runTest(dispatcher) {
        val wire = CardWireHarness(circlesOnServer = onServer)
        wire.seed(ProfileAttributeTypes.TWITTER, "connected", data(ProfileAttributeTypes.KEY_TWITTER, "frodo"), listOf(FAMILY_CIRCLE_ID, hiddenCircle, deletedCircle))
        val rig = rig(wire, serverCircles = onServer)
        rig.open(CardAudience.Public)
        rig.vm.onImportRequested(family)
        rig.vm.onImportConfirmed()
        withContext(Dispatchers.Default) { withTimeout(10.seconds) { while (wire.puts < 1) delay(10) } }
        rig.vm.await { !it.isContentBusy }
        assertEquals("anonymous" to null, wire.acls()["${ProfileAttributeTypes.TWITTER}:"])

        rig.vm.onImportUndone()
        withContext(Dispatchers.Default) { withTimeout(10.seconds) { while (wire.puts < 2) delay(10) } }
        rig.vm.await { !it.isContentBusy }

        assertEquals(0, rig.vm.uiState.value.contentFailures)
        assertEquals("connected" to listOf(FAMILY_CIRCLE_ID, hiddenCircle), wire.acls()["${ProfileAttributeTypes.TWITTER}:"])
        assertTrue(rig.item(ProfileAttributeTypes.TWITTER).let { !it.shown })
    }

    @Test
    fun copyingOntoACircleCardDropsADeletedCircleFromTheWrite() = runTest(dispatcher) {
        val wire = CardWireHarness(circlesOnServer = onServer)
        wire.seed(ProfileAttributeTypes.TWITTER, "connected", data(ProfileAttributeTypes.KEY_TWITTER, "frodo"), listOf(FAMILY_CIRCLE_ID, deletedCircle))
        val rig = rig(wire, serverCircles = onServer)
        rig.open(work)

        rig.import(family, expectedPuts = 1)

        assertEquals(0, rig.vm.uiState.value.contentFailures)
        val written = wire.acls()["${ProfileAttributeTypes.TWITTER}:"]!!
        assertEquals("connected", written.first)
        assertEquals(setOf(FAMILY_CIRCLE_ID, WORK_CIRCLE_ID), written.second!!.toSet())
    }

    @Test
    fun writesNamingOnlyKnownCirclesNeverAskTheServerForItsCircles() = runTest(dispatcher) {
        val wire = CardWireHarness()
        wire.seedFriendsAndFamilyContent()
        val rig = rig(wire)
        rig.open(work)
        rig.import(friends, expectedPuts = 3)
        rig.vm.onImportUndone()
        withContext(Dispatchers.Default) { withTimeout(10.seconds) { while (wire.puts < 6) delay(10) } }
        rig.vm.await { !it.isContentBusy }
        assertEquals(0, rig.source.serverCircleReads)
    }

    @Test
    fun anAclWhoseEveryCircleIsGoneRestoresAsOnlyMeRatherThanEveryConnection() {
        val attribute = id.homebase.api.client.profile.ProfileAttribute(
            id = Uuid.random(),
            type = ProfileAttributeTypes.TWITTER,
            versionTag = Uuid.random(),
            visibility = id.homebase.api.client.profile.ProfileVisibility.CONNECTED,
            data = data(ProfileAttributeTypes.KEY_TWITTER, "frodo"),
            acl = id.homebase.api.client.drives.AccessControlList(requiredSecurityGroup = "connected", circleIdList = listOf(deletedCircle)),
        )
        val kept = attribute.keepingCircles { it != deletedCircle }
        assertEquals(id.homebase.api.client.profile.ProfileVisibility.OWNER, kept.visibility)
        assertNull(kept.acl.circleIdList)
        assertEquals(ProfileAudience.OnlyMe, ProfileAudience.Circles(emptySet(), setOf(deletedCircle)).keepingOtherCircles { it != deletedCircle })
        assertEquals(ProfileAudience.Circles(setOf(FAMILY_CIRCLE_ID)), ProfileAudience.Circles(setOf(FAMILY_CIRCLE_ID), setOf(deletedCircle)).keepingOtherCircles { it != deletedCircle })
    }

    @Test
    fun removingAFriendsItemDropsItFromFriendsAndLeavesThePublicCardUnchanged() = runTest(dispatcher) {
        val wire = CardWireHarness()
        wire.seed(ProfileAttributeTypes.LINK, "connected", data(ProfileAttributeTypes.KEY_LINK_TARGET, "https://shire.example/f"), listOf(FRIENDS_CIRCLE_ID))
        wire.seed(ProfileAttributeTypes.LINK, "anonymous", data(ProfileAttributeTypes.KEY_LINK_TARGET, "https://shire.example/p"))
        val rig = rig(wire, withCards = true)
        rig.open(CardAudience.Public)
        val publicBefore = rig.host.rendered.last()
        rig.open(friends)
        assertEquals(0, wire.puts)
        assertEquals(setOf("https://shire.example/f", "https://shire.example/p"), rig.host.rendered.last().data.links.map { it.target }.toSet())
        val friendsLink = rig.vm.uiState.value.contentItems.single { it.text == "https://shire.example/f" }

        rig.write { rig.vm.onContentToggled(friendsLink.id, false) }

        assertEquals(2, wire.puts)
        val bodies = wire.putBodies.map { it.jsonObject }
        assertEquals(ProfileAttributeTypes.PROFILE_CARD, bodies[0].str("type"))
        assertEquals(listOf(FRIENDS_CIRCLE_ID), bodies[0]["circleIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(ProfileAttributeTypes.LINK, bodies[1].str("type"))
        assertEquals("owner", bodies[1].str("visibility"))
        assertEquals(listOf("https://shire.example/p"), rig.host.rendered.last().data.links.map { it.target })
        rig.open(CardAudience.Public)
        assertEquals(publicBefore.data, rig.host.rendered.last().data)
        assertEquals(publicBefore.design, rig.host.rendered.last().design)
    }

    @Test
    fun addingAnExistingHiddenItemShowsItOnFriendsOnly() = runTest(dispatcher) {
        val wire = CardWireHarness()
        wire.seed(ProfileAttributeTypes.LINK, "connected", data(ProfileAttributeTypes.KEY_LINK_TARGET, "https://shire.example/w"), listOf(WORK_CIRCLE_ID))
        val rig = rig(wire)
        rig.open(friends)
        val link = rig.vm.uiState.value.contentItems.single { it.text == "https://shire.example/w" }
        assertFalse(link.shown)

        rig.write { rig.vm.onContentToggled(link.id, true) }

        assertEquals(setOf(FRIENDS_CIRCLE_ID, WORK_CIRCLE_ID), rig.circleIds()!!.toSet())
        assertEquals(listOf("https://shire.example/w"), rig.host.rendered.last().data.links.map { it.target })
        rig.open(family)
        assertTrue(rig.host.rendered.last().data.links.isEmpty())
        rig.open(CardAudience.Public)
        assertTrue(rig.host.rendered.last().data.links.isEmpty())
    }

    @Test
    fun theFirstEditOfAnUnsavedCircleCardSavesItsTemplateSoTheWebServesTheSameCard() = runTest(dispatcher) {
        val wire = CardWireHarness()
        wire.seed(ProfileAttributeTypes.PHONE, "owner", data(ProfileAttributeTypes.KEY_PHONE, phone))
        val rig = rig(wire, withCards = true)
        rig.open(work)
        assertEquals(CardDesign.LINKS, rig.vm.uiState.value.design)
        assertEquals(0, wire.puts)

        rig.write { rig.vm.onContentToggled(rig.item(ProfileAttributeTypes.PHONE).id, true) }
        withContext(Dispatchers.Default) { withTimeout(10.seconds) { while (wire.puts < 2) delay(10) } }

        val card = wire.putBodies.map { it.jsonObject }.single { it.str("type") == ProfileAttributeTypes.PROFILE_CARD }
        assertEquals(CardDesign.LINKS, card.str("data", "design"))
        assertEquals("#F1F3F6", card.str("data", "overrides", "palette", "ground"))
        assertEquals(listOf(WORK_CIRCLE_ID), card["circleIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse(rig.vm.uiState.value.selectedCard!!.isDefault)

        rig.write { rig.vm.onContentToggled(rig.item(ProfileAttributeTypes.PHONE).id, false) }
        assertEquals(1, wire.putBodies.count { it.jsonObject.str("type") == ProfileAttributeTypes.PROFILE_CARD })
    }

    @Test
    fun shownOnTable() {
        val all = setOf(FAMILY_CIRCLE_ID, FRIENDS_CIRCLE_ID)
        assertEquals(ProfileAudience.Circles(setOf(FRIENDS_CIRCLE_ID)), ProfileAudience.OnlyMe.shownOn(friends, true, null))
        assertNull(ProfileAudience.OnlyMe.shownOn(friends, false, null))
        assertNull(ProfileAudience.Public.shownOn(friends, false, null))
        assertEquals(ProfileAudience.Circles(all), ProfileAudience.Circles(setOf(FAMILY_CIRCLE_ID)).shownOn(friends, true, null))
        assertEquals(ProfileAudience.OnlyMe, ProfileAudience.Circles(setOf(FRIENDS_CIRCLE_ID)).shownOn(friends, false, null))
        assertEquals(
            ProfileAudience.Circles(emptySet(), setOf("x")),
            ProfileAudience.Circles(setOf(FRIENDS_CIRCLE_ID), setOf("x")).shownOn(friends, false, null),
        )
        assertNull(ProfileAudience.Circles(all).shownOn(friends, true, null))
        assertEquals(ProfileAudience.Public, ProfileAudience.OnlyMe.shownOn(CardAudience.Public, true, null))
        assertEquals(ProfileAudience.Public, ProfileAudience.Circles(all).shownOn(CardAudience.Public, true, null))
        assertNull(ProfileAudience.Circles(all).shownOn(CardAudience.Public, false, null))
        assertEquals(ProfileAudience.Circles(all), ProfileAudience.Public.shownOn(CardAudience.Public, false, ProfileAudience.Circles(all)))
        assertEquals(ProfileAudience.OnlyMe, ProfileAudience.Public.shownOn(CardAudience.Public, false, null))
        assertEquals(ProfileAudience.OnlyMe, ProfileAudience.Public.shownOn(CardAudience.Public, false, ProfileAudience.Circles(emptySet())))
    }
}
