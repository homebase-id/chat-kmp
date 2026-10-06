@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.profile

import id.homebase.api.client.profile.ProfileAttributeTypes
import id.homebase.core.ui.screens.card.CardAudience
import id.homebase.core.ui.screens.card.CardCircle
import id.homebase.core.ui.screens.card.CardWireHarness
import id.homebase.core.ui.screens.card.FAMILY_CIRCLE_ID
import id.homebase.core.ui.screens.card.FRIENDS_CIRCLE_ID
import id.homebase.core.ui.screens.card.WORK_CIRCLE_ID
import id.homebase.core.ui.screens.card.aclFilter
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The real ViewModel and ProfileRepository over a fake transport that decrypts what the app PUTs. */
class ProfileEditAudienceWireTest {

    private val circles = listOf(
        CardCircle(FAMILY_CIRCLE_ID, "Family"),
        CardCircle(FRIENDS_CIRCLE_ID, "Friends"),
        CardCircle(WORK_CIRCLE_ID, "Work"),
    )
    private val phone = "+14155550123"

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun phoneData() = JsonObject(mapOf(ProfileAttributeTypes.KEY_PHONE to JsonPrimitive(phone)))

    private suspend fun viewModel(harness: CardWireHarness): ProfileEditViewModel {
        val vm = ProfileEditViewModel(harness.profileRepository(), reviewEnabled = true) { circles }
        withTimeout(5_000) { vm.state.first { !it.isLoading } }
        return vm
    }

    private suspend fun ProfileEditViewModel.act(action: ProfileEditAction): ProfileEditEvent = coroutineScope {
        val event = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { events.first() } }
        onAction(action)
        event.await()
    }

    private fun CardWireHarness.lastPut() = putBodies.last().jsonObject

    @Test
    fun phoneForFamilyAndFriendsIsSavedAsConnectedWithBothCircleIds() = runBlocking {
        val harness = CardWireHarness()
        val vm = viewModel(harness)

        vm.onAction(ProfileEditAction.FieldChanged(ProfileField.PHONE, phone))
        vm.onAction(ProfileEditAction.AudienceChanged(ProfileAttributeTypes.PHONE, ProfileAudience.Circles(setOf(FAMILY_CIRCLE_ID, FRIENDS_CIRCLE_ID))))
        assertIs<ProfileEditEvent.AttributeSaved>(vm.act(ProfileEditAction.SaveAttribute(ProfileAttributeTypes.PHONE)))

        val put = harness.lastPut()
        assertEquals(ProfileAttributeTypes.PHONE, put["type"]!!.jsonPrimitive.content)
        assertEquals("connected", put["visibility"]!!.jsonPrimitive.content)
        assertEquals(setOf(FAMILY_CIRCLE_ID, FRIENDS_CIRCLE_ID), put["circleIds"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertEquals(phone, put["data"]!!.jsonObject[ProfileAttributeTypes.KEY_PHONE]!!.jsonPrimitive.content)
    }

    @Test
    fun theSavedPhoneShowsOnTheFamilyCardOnlyNotWorkNotPublic() = runBlocking {
        val harness = CardWireHarness()
        val vm = viewModel(harness)
        vm.onAction(ProfileEditAction.FieldChanged(ProfileField.PHONE, phone))
        vm.onAction(ProfileEditAction.AudienceChanged(ProfileAttributeTypes.PHONE, ProfileAudience.Circles(setOf(FAMILY_CIRCLE_ID, FRIENDS_CIRCLE_ID))))
        vm.act(ProfileEditAction.SaveAttribute(ProfileAttributeTypes.PHONE))

        val stored = harness.profileRepository().loadAttributes()
        fun phoneOn(audience: CardAudience) = stored.visibleValues(audience.aclFilter())[ProfileField.PHONE]

        assertEquals(phone, phoneOn(CardAudience.Circle(FAMILY_CIRCLE_ID, "Family")))
        assertEquals(phone, phoneOn(CardAudience.Circle(FRIENDS_CIRCLE_ID, "Friends")))
        assertEquals("", phoneOn(CardAudience.Circle(WORK_CIRCLE_ID, "Work")))
        assertEquals("", phoneOn(CardAudience.Public))
    }

    @Test
    fun publicShowsOnEveryCardAndOnlyMeOnNone() = runBlocking {
        val harness = CardWireHarness()
        val vm = viewModel(harness)
        vm.onAction(ProfileEditAction.FieldChanged(ProfileField.PHONE, phone))
        vm.act(ProfileEditAction.SaveAttribute(ProfileAttributeTypes.PHONE))
        assertEquals("anonymous", harness.lastPut()["visibility"]!!.jsonPrimitive.content)
        assertNull(harness.lastPut()["circleIds"])

        var stored = harness.profileRepository().loadAttributes()
        for (audience in listOf(CardAudience.Public) + circles.map { CardAudience.Circle(it.id, it.name) }) {
            assertEquals(phone, stored.visibleValues(audience.aclFilter())[ProfileField.PHONE])
        }

        vm.onAction(ProfileEditAction.AudienceChanged(ProfileAttributeTypes.PHONE, ProfileAudience.OnlyMe))
        vm.act(ProfileEditAction.SaveAttribute(ProfileAttributeTypes.PHONE))
        assertEquals("owner", harness.lastPut()["visibility"]!!.jsonPrimitive.content)
        assertNull(harness.lastPut()["circleIds"])
        stored = harness.profileRepository().loadAttributes()
        for (audience in listOf(CardAudience.Public) + circles.map { CardAudience.Circle(it.id, it.name) }) {
            assertEquals("", stored.visibleValues(audience.aclFilter())[ProfileField.PHONE])
        }
    }

    @Test
    fun legacyConnectedWithNoCirclesShowsAsEveryCircleAndRoundTrips() = runBlocking {
        val harness = CardWireHarness()
        harness.seed(Uuid.random(), Uuid.random(), ProfileAttributeTypes.PHONE, "connected", phoneData(), 0, null)
        val vm = viewModel(harness)

        assertEquals(ProfileAudience.Circles(circles.map { it.id }.toSet()), vm.state.value.audience(ProfileAttributeTypes.PHONE))
        assertEquals(phone, vm.state.value.value(ProfileField.PHONE))

        assertIs<ProfileEditEvent.AttributeSaved>(vm.act(ProfileEditAction.SaveAttribute(ProfileAttributeTypes.PHONE)))
        assertEquals(0, harness.puts)

        vm.onAction(ProfileEditAction.AudienceChanged(ProfileAttributeTypes.PHONE, ProfileAudience.Circles(setOf(FAMILY_CIRCLE_ID, FRIENDS_CIRCLE_ID, WORK_CIRCLE_ID))))
        vm.act(ProfileEditAction.SaveAttribute(ProfileAttributeTypes.PHONE))
        assertEquals(0, harness.puts)

        vm.onAction(ProfileEditAction.FieldChanged(ProfileField.PHONE, "+14155550199"))
        vm.act(ProfileEditAction.SaveAttribute(ProfileAttributeTypes.PHONE))
        val put = harness.lastPut()
        assertEquals("connected", put["visibility"]!!.jsonPrimitive.content)
        assertEquals(circles.map { it.id }.sorted(), put["circleIds"]!!.jsonArray.map { it.jsonPrimitive.content }.sorted())

        val reloaded = viewModel(harness)
        assertEquals(ProfileAudience.Circles(circles.map { it.id }.toSet()), reloaded.state.value.audience(ProfileAttributeTypes.PHONE))
    }

    @Test
    fun circlesWithNothingSelectedIsRefusedBeforeAnyRequest() = runBlocking {
        val harness = CardWireHarness()
        val vm = viewModel(harness)
        vm.onAction(ProfileEditAction.FieldChanged(ProfileField.PHONE, phone))
        vm.onAction(ProfileEditAction.AudienceChanged(ProfileAttributeTypes.PHONE, ProfileAudience.Circles(emptySet())))
        assertIs<ProfileEditEvent.Error>(vm.act(ProfileEditAction.SaveAttribute(ProfileAttributeTypes.PHONE)))
        assertEquals(0, harness.puts)
    }

    @Test
    fun leftoverRecordFromTheTwoTierEditorIsRemovedSoTheEditedAudienceIsTheOnlyOne() = runBlocking {
        val harness = CardWireHarness()
        val publicId = Uuid.random()
        harness.seed(publicId, Uuid.random(), ProfileAttributeTypes.PHONE, "anonymous", phoneData(), 0, null)
        val otherPhone = "+14155550777"
        harness.seed(
            Uuid.random(), Uuid.random(), ProfileAttributeTypes.PHONE, "connected",
            JsonObject(mapOf(ProfileAttributeTypes.KEY_PHONE to JsonPrimitive(otherPhone))), 0, null,
        )
        val vm = viewModel(harness)

        vm.onAction(ProfileEditAction.AudienceChanged(ProfileAttributeTypes.PHONE, ProfileAudience.Circles(setOf(FAMILY_CIRCLE_ID))))
        assertIs<ProfileEditEvent.AttributeSaved>(vm.act(ProfileEditAction.SaveAttribute(ProfileAttributeTypes.PHONE)))

        assertEquals(1, harness.deletes)
        assertEquals(listOf(publicId.toString()), harness.storedIds)
        val stored = harness.profileRepository().loadAttributes()
        fun phoneOn(audience: CardAudience) = stored.visibleValues(audience.aclFilter())[ProfileField.PHONE]
        assertEquals(phone, phoneOn(CardAudience.Circle(FAMILY_CIRCLE_ID, "Family")))
        assertEquals("", phoneOn(CardAudience.Circle(WORK_CIRCLE_ID, "Work")))
        assertEquals("", phoneOn(CardAudience.Circle(FRIENDS_CIRCLE_ID, "Friends")))
        assertEquals("", phoneOn(CardAudience.Public))
        assertEquals(1, vm.state.value.attributes.count { it.type == ProfileAttributeTypes.PHONE })
    }

    @Test
    fun editingOnlyTheValueKeepsStoredCirclesTheEditorHasNoChipFor() = runBlocking {
        val harness = CardWireHarness()
        val userCircle = Uuid.random().toString()
        harness.seed(Uuid.random(), Uuid.random(), ProfileAttributeTypes.PHONE, "connected", phoneData(), 0, listOf(FAMILY_CIRCLE_ID, userCircle))
        val vm = viewModel(harness)

        vm.onAction(ProfileEditAction.FieldChanged(ProfileField.PHONE, "+14155550199"))
        assertIs<ProfileEditEvent.AttributeSaved>(vm.act(ProfileEditAction.SaveAttribute(ProfileAttributeTypes.PHONE)))

        val put = harness.lastPut()
        assertEquals("connected", put["visibility"]!!.jsonPrimitive.content)
        assertEquals(setOf(FAMILY_CIRCLE_ID, userCircle), put["circleIds"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
    }

    @Test
    fun recordSharedOnlyWithNonContactsCirclesStaysSavableAndKeepsThem() = runBlocking {
        val harness = CardWireHarness()
        val userCircle = Uuid.random().toString()
        harness.seed(Uuid.random(), Uuid.random(), ProfileAttributeTypes.PHONE, "connected", phoneData(), 0, listOf(userCircle))
        val vm = viewModel(harness)

        vm.onAction(ProfileEditAction.FieldChanged(ProfileField.PHONE, "+14155550199"))
        assertIs<ProfileEditEvent.AttributeSaved>(vm.act(ProfileEditAction.SaveAttribute(ProfileAttributeTypes.PHONE)))
        assertEquals(listOf(userCircle), harness.lastPut()["circleIds"]!!.jsonArray.map { it.jsonPrimitive.content })
    }
}
