@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.card

import id.homebase.api.client.profile.ProfileAttributeTypes
import id.homebase.core.ui.screens.card.ProfileCardViewModelTest.FakeHost
import id.homebase.core.ui.screens.card.ProfileCardViewModelTest.FakeSource
import id.homebase.core.ui.screens.profile.ProfileAudience
import id.homebase.core.ui.screens.profile.ProfileField
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

    private class Rig(val wire: CardWireHarness, val host: FakeHost, val vm: ProfileCardViewModel)

    private fun JsonObject.str(vararg path: String): String? {
        var node: JsonElement = this
        for (key in path) node = (node as? JsonObject)?.get(key) ?: return null
        return node.jsonPrimitive.content
    }

    private fun data(key: String, value: String) = JsonObject(mapOf(key to JsonPrimitive(value)))

    private fun CardWireHarness.seed(type: String, visibility: String, data: JsonObject, circles: List<String>? = null): Uuid =
        Uuid.random().also { seed(it, Uuid.random(), type, visibility, data, 0, circles) }

    private suspend fun rig(wire: CardWireHarness): Rig {
        wire.seed(ProfileAttributeTypes.NAME, "anonymous", data(ProfileAttributeTypes.KEY_GIVEN_NAME, "Frodo"))
        val repository = wire.profileRepository()
        val source = FakeSource(emptyList()).apply {
            profileRepository = repository
            liveCards = { repository.loadAttributes() }
            circleList = listOf(CardCircle(FAMILY_CIRCLE_ID, "Family"), CardCircle(FRIENDS_CIRCLE_ID, "Friends"), CardCircle(WORK_CIRCLE_ID, "Work"))
        }
        val host = FakeHost()
        val vm = ProfileCardViewModel(source) { host }.also { it.startHost() }
        vm.await { it.attributes.isNotEmpty() && it.cards.size == 4 }
        withContext(Dispatchers.Default) { withTimeout(10.seconds) { while (host.rendered.isEmpty()) delay(10) } }
        return Rig(wire, host, vm)
    }

    private suspend fun ProfileCardViewModel.await(condition: (ProfileCardUiState) -> Boolean) =
        withContext(Dispatchers.Default) { withTimeout(10.seconds) { uiState.first(condition) } }

    private suspend fun Rig.open(card: CardAudience) {
        vm.onCardOpened(card)
        vm.await { it.selectedAudience == card }
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
