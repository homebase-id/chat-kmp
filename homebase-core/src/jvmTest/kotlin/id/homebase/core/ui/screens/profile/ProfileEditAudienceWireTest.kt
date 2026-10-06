@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.profile

import id.homebase.api.client.drives.CONFIRMED_CONNECTIONS_SYSTEM_CIRCLE
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
import kotlin.test.assertTrue
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
        vm.onAction(ProfileEditAction.AudienceChanged(ProfileAttributeTypes.PHONE, ProfileAudience.Public))
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

    private fun seedPublicAndConnectedPhone(harness: CardWireHarness, connectedPhone: String): Uuid {
        val publicId = Uuid.random()
        harness.seed(publicId, Uuid.random(), ProfileAttributeTypes.PHONE, "anonymous", phoneData(), 0, null)
        harness.seed(
            Uuid.random(), Uuid.random(), ProfileAttributeTypes.PHONE, "connected",
            JsonObject(mapOf(ProfileAttributeTypes.KEY_PHONE to JsonPrimitive(connectedPhone))), 0, null,
        )
        return publicId
    }

    @Test
    fun leftoverWithTheSameDataIsRemovedSoTheEditedAudienceIsTheOnlyOne() = runBlocking {
        val harness = CardWireHarness()
        val publicId = seedPublicAndConnectedPhone(harness, phone)
        val vm = viewModel(harness)
        assertNull(vm.state.value.conflicts[ProfileAttributeTypes.PHONE])

        vm.onAction(ProfileEditAction.AudienceChanged(ProfileAttributeTypes.PHONE, ProfileAudience.Circles(setOf(FAMILY_CIRCLE_ID))))
        assertIs<ProfileEditEvent.AttributeSaved>(vm.act(ProfileEditAction.SaveAttribute(ProfileAttributeTypes.PHONE)))

        assertEquals(1, harness.deletes)
        assertEquals(listOf(publicId.toString()), harness.storedIds)
        val stored = harness.profileRepository().loadAttributes()
        fun phoneOn(audience: CardAudience) = stored.visibleValues(audience.aclFilter())[ProfileField.PHONE]
        assertEquals(phone, phoneOn(CardAudience.Circle(FAMILY_CIRCLE_ID, "Family")))
        assertEquals("", phoneOn(CardAudience.Circle(WORK_CIRCLE_ID, "Work")))
        assertEquals("", phoneOn(CardAudience.Public))
        assertEquals(1, vm.state.value.attributes.count { it.type == ProfileAttributeTypes.PHONE })
    }

    @Test
    fun leftoverWithADifferentValueIsShownAndSurvivesTheSaveUntilTheUserRemovesIt() = runBlocking {
        val harness = CardWireHarness()
        val otherPhone = "+14155550777"
        seedPublicAndConnectedPhone(harness, otherPhone)
        val vm = viewModel(harness)

        val conflict = vm.state.value.conflicts[ProfileAttributeTypes.PHONE].orEmpty().single()
        assertEquals(otherPhone, conflict.string(ProfileAttributeTypes.KEY_PHONE))

        vm.onAction(ProfileEditAction.AudienceChanged(ProfileAttributeTypes.PHONE, ProfileAudience.Circles(setOf(FAMILY_CIRCLE_ID))))
        assertIs<ProfileEditEvent.AttributeSaved>(vm.act(ProfileEditAction.SaveAttribute(ProfileAttributeTypes.PHONE)))

        assertEquals(0, harness.deletes)
        assertEquals(2, harness.storedIds.size)
        assertEquals(otherPhone, vm.state.value.conflicts[ProfileAttributeTypes.PHONE].orEmpty().single().string(ProfileAttributeTypes.KEY_PHONE))

        vm.onAction(ProfileEditAction.DiscardConflict(ProfileAttributeTypes.PHONE, conflict.id))
        withTimeout(5_000) { vm.state.first { it.conflicts.isEmpty() } }
        assertEquals(1, harness.deletes)
        assertEquals(1, harness.storedIds.size)
        assertEquals(1, vm.state.value.attributes.count { it.type == ProfileAttributeTypes.PHONE })
    }

    @Test
    fun confirmedConnectionsSystemCircleStillKeepsTheUserCirclesOnSave() = runBlocking {
        val harness = CardWireHarness()
        val userCircle = Uuid.random().toString()
        harness.seed(
            Uuid.random(), Uuid.random(), ProfileAttributeTypes.PHONE, "connected", phoneData(), 0,
            listOf(CONFIRMED_CONNECTIONS_SYSTEM_CIRCLE, userCircle),
        )
        val vm = viewModel(harness)
        assertEquals(
            ProfileAudience.Circles(circles.map { it.id }.toSet(), setOf(userCircle)),
            vm.state.value.audience(ProfileAttributeTypes.PHONE),
        )

        vm.onAction(ProfileEditAction.FieldChanged(ProfileField.PHONE, "+14155550199"))
        assertIs<ProfileEditEvent.AttributeSaved>(vm.act(ProfileEditAction.SaveAttribute(ProfileAttributeTypes.PHONE)))
        val ids = harness.lastPut()["circleIds"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertEquals(circles.map { it.id }.toSet() + userCircle, ids)
    }

    @Test
    fun bioIsSavedThroughTheSameEditorForWorkOnly() = runBlocking {
        val harness = CardWireHarness()
        val vm = viewModel(harness)
        vm.onAction(ProfileEditAction.FieldChanged(ProfileField.BIO, "Builds things"))
        vm.onAction(ProfileEditAction.AudienceChanged(ProfileAttributeTypes.BIO_SUMMARY, ProfileAudience.Circles(setOf(WORK_CIRCLE_ID))))
        assertIs<ProfileEditEvent.AttributeSaved>(vm.act(ProfileEditAction.SaveAttribute(ProfileAttributeTypes.BIO_SUMMARY)))

        val put = harness.lastPut()
        assertEquals(ProfileAttributeTypes.BIO_SUMMARY, put["type"]!!.jsonPrimitive.content)
        assertEquals("connected", put["visibility"]!!.jsonPrimitive.content)
        assertEquals(listOf(WORK_CIRCLE_ID), put["circleIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("Builds things", put["data"]!!.jsonObject[ProfileAttributeTypes.KEY_SHORT_BIO]!!.jsonPrimitive.content)
    }

    @Test
    fun aLinkSavedForWorkOnlyShowsOnTheWorkCardAndEachLinkIsItsOwnRecord() = runBlocking {
        val harness = CardWireHarness()
        val vm = viewModel(harness)

        vm.onAction(ProfileEditAction.AddLink)
        val first = vm.state.value.links.single().key
        vm.onAction(ProfileEditAction.LinkChanged(first, "Docs", "https://example.com/docs"))
        vm.onAction(ProfileEditAction.LinkAudienceChanged(first, ProfileAudience.Circles(setOf(WORK_CIRCLE_ID))))
        assertIs<ProfileEditEvent.AttributeSaved>(vm.act(ProfileEditAction.SaveLink(first)))

        val put = harness.lastPut()
        assertEquals(ProfileAttributeTypes.LINK, put["type"]!!.jsonPrimitive.content)
        assertEquals("connected", put["visibility"]!!.jsonPrimitive.content)
        assertEquals(listOf(WORK_CIRCLE_ID), put["circleIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("Docs", put["data"]!!.jsonObject[ProfileAttributeTypes.KEY_LINK_TEXT]!!.jsonPrimitive.content)
        assertEquals("https://example.com/docs", put["data"]!!.jsonObject[ProfileAttributeTypes.KEY_LINK_TARGET]!!.jsonPrimitive.content)

        vm.onAction(ProfileEditAction.AddLink)
        val second = vm.state.value.links.last().key
        vm.onAction(ProfileEditAction.LinkChanged(second, "Blog", "https://example.com/blog"))
        assertIs<ProfileEditEvent.AttributeSaved>(vm.act(ProfileEditAction.SaveLink(second)))
        assertEquals("anonymous", harness.lastPut()["visibility"]!!.jsonPrimitive.content)
        assertEquals(2, harness.storedIds.size)

        val stored = harness.profileRepository().loadAttributes()
        fun linksOn(audience: CardAudience) = stored.visibleLinks(audience.aclFilter()).map { it.string(ProfileAttributeTypes.KEY_LINK_TEXT) }.toSet()
        assertEquals(setOf("Docs", "Blog"), linksOn(CardAudience.Circle(WORK_CIRCLE_ID, "Work")))
        assertEquals(setOf("Blog"), linksOn(CardAudience.Circle(FAMILY_CIRCLE_ID, "Family")))
        assertEquals(setOf("Blog"), linksOn(CardAudience.Public))

        val reloaded = viewModel(harness)
        assertEquals(setOf("Docs", "Blog"), reloaded.state.value.links.map { it.text }.toSet())
        assertEquals(
            ProfileAudience.Circles(setOf(WORK_CIRCLE_ID)),
            reloaded.state.value.links.first { it.text == "Docs" }.audience,
        )
    }

    @Test
    fun aNewPhoneSavesToTheContactsCirclesUnlessPickedOtherwise() = runBlocking {
        val harness = CardWireHarness()
        val vm = viewModel(harness)
        vm.onAction(ProfileEditAction.FieldChanged(ProfileField.PHONE, phone))
        vm.act(ProfileEditAction.SaveAttribute(ProfileAttributeTypes.PHONE))
        assertEquals("connected", harness.lastPut()["visibility"]!!.jsonPrimitive.content)
        assertEquals(circles.map { it.id }.sorted(), harness.lastPut()["circleIds"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun removingASavedLinkDeletesItsRecord() = runBlocking {
        val harness = CardWireHarness()
        val vm = viewModel(harness)
        vm.onAction(ProfileEditAction.AddLink)
        val draft = vm.state.value.links.single().key
        vm.onAction(ProfileEditAction.LinkChanged(draft, "Recipes", "https://sam.kitchen"))
        vm.act(ProfileEditAction.SaveLink(draft))
        val saved = vm.state.value.links.single().key

        assertIs<ProfileEditEvent.AttributeSaved>(vm.act(ProfileEditAction.RemoveLink(saved)))
        assertEquals(1, harness.deletes)
        assertTrue(vm.state.value.links.isEmpty())
        assertTrue(viewModel(harness).state.value.links.isEmpty())
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

    @Test
    fun anEditedDetailKeepsItsStoredPriority() = runBlocking {
        val harness = CardWireHarness()
        harness.seed(Uuid.random(), Uuid.random(), ProfileAttributeTypes.PHONE, "anonymous", phoneData(), 7, null)
        val vm = viewModel(harness)

        vm.onAction(ProfileEditAction.FieldChanged(ProfileField.PHONE, "+14155550199"))
        assertIs<ProfileEditEvent.AttributeSaved>(vm.act(ProfileEditAction.SaveAttribute(ProfileAttributeTypes.PHONE)))

        assertEquals(7, harness.lastPut()["priority"]?.jsonPrimitive?.content?.toInt())
        assertEquals(7, harness.profileRepository().loadAttributes().single { it.type == ProfileAttributeTypes.PHONE }.priority)
    }

    @Test
    fun anEditedLinkKeepsItsStoredPriority() = runBlocking {
        val harness = CardWireHarness()
        val link = JsonObject(
            mapOf(
                ProfileAttributeTypes.KEY_LINK_TEXT to JsonPrimitive("Garden"),
                ProfileAttributeTypes.KEY_LINK_TARGET to JsonPrimitive("https://bagend.me"),
            ),
        )
        harness.seed(Uuid.random(), Uuid.random(), ProfileAttributeTypes.LINK, "anonymous", link, 3, null)
        val vm = viewModel(harness)
        val key = vm.state.value.links.single().key

        vm.onAction(ProfileEditAction.LinkChanged(key, "Vegetables", "https://bagend.me"))
        assertIs<ProfileEditEvent.AttributeSaved>(vm.act(ProfileEditAction.SaveLink(key)))

        assertEquals(3, harness.lastPut()["priority"]?.jsonPrimitive?.content?.toInt())
    }
}
