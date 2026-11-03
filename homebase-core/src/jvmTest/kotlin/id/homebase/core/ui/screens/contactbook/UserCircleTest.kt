package id.homebase.core.ui.screens.contactbook

import id.homebase.api.client.ForbiddenException
import id.homebase.api.client.connections.CircleGrantOn
import id.homebase.api.client.connections.CircleWithMembers
import id.homebase.api.client.connections.CreateAppCircleRequest
import id.homebase.api.client.connections.RedactedCircleDefinition
import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.profile.ProfileAttribute
import id.homebase.api.client.profile.ProfileAttributeTypes
import id.homebase.api.client.profile.ProfileVisibility
import id.homebase.chat.conversationlist.ExtendPermissionViewModel
import id.homebase.chat.services.convo.contact.CircleMembershipState
import id.homebase.core.config.AppConfig
import id.homebase.core.config.CONTACTS_APP_ID
import id.homebase.core.ui.screens.card.CardAudience
import id.homebase.core.ui.screens.card.CardCircle
import id.homebase.core.ui.screens.card.CardOverrides
import id.homebase.core.ui.screens.card.CardDesign
import id.homebase.core.ui.screens.card.ProfileCard
import id.homebase.core.ui.screens.card.contactsCircles
import id.homebase.core.ui.screens.card.profileCardAttribute
import id.homebase.core.ui.screens.profile.ProfileAudience
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@OptIn(ExperimentalCoroutinesApi::class)
class UserCircleTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val chat = Uuid.parseHex(AppConfig.APP_ID)
    private val otherApp = Uuid.parse("11111111-2222-4333-8444-555555555555")

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun def(
        name: String,
        appId: Uuid?,
        slug: String? = null,
        id: String = "id-${name.lowercase()}",
        grantOn: CircleGrantOn = CircleGrantOn.None,
    ) = RedactedCircleDefinition(id = id, name = name, appId = appId, slug = slug, grantOn = grantOn)

    @Test
    fun slugsAreCPrefixedLowercaseHexWithinTheServerLimit() {
        repeat(20) {
            val slug = newCircleSlug()
            assertTrue(Regex("^c-[0-9a-f]{12}$").matches(slug), slug)
            assertTrue(slug.length <= 14)
        }
        assertNotEquals(newCircleSlug(), newCircleSlug())
    }

    @Test
    fun onlyChatCirclesWithACSlugAndNoAutoGrantAreUserMade() {
        assertTrue(def("Climbing", chat, "c-0123456789ab").isUserMadeCircle())
        assertFalse(def("Chat", chat, "chat").isUserMadeCircle())
        assertFalse(def("Chat", chat, null).isUserMadeCircle())
        assertFalse(def("Theirs", otherApp, "c-0123456789ab").isUserMadeCircle())
        assertFalse(def("Contacts", Uuid.parse(CONTACTS_APP_ID), "c-0123456789ab").isUserMadeCircle())
        assertFalse(def("Auto", chat, "c-0123456789ab", grantOn = CircleGrantOn.Connect).isUserMadeCircle())
    }

    private val all = listOf(
        CircleWithMembers(def("Friends", Uuid.parse(CONTACTS_APP_ID), id = "ff")),
        CircleWithMembers(def("Climbing", chat, "c-0123456789ab", id = "cc")),
        CircleWithMembers(def("Chat", chat, "chat", id = "ch")),
        CircleWithMembers(def("Vault", otherApp, "vault", id = "va")),
        CircleWithMembers(def("Theirs", otherApp, "c-aaaaaaaaaaaa", id = "th")),
    )

    @Test
    fun cardsAndAudiencesIncludeUserMadeCirclesAndKeepTheOrder() {
        assertEquals(listOf("Climbing", "Friends"), contactsCircles(all).map { it.name })
    }

    @Test
    fun reviewGroupsOfferUserMadeCirclesAndHideOtherChatOnes() {
        val groups = CircleMembershipState(isLoaded = true, circles = all).reviewCircleGroups()
        assertEquals(listOf("Climbing", "Friends"), groups.yours.map { it.name })
    }

    @Test
    fun requestDraftCarriesTheSlugOneReadGrantAndTrimmedText() {
        val request = CircleDraft("c-0123456789ab", "  Climbing  ", "🧗", "  ").toRequest()
        assertEquals("Climbing", request.name)
        assertEquals("c-0123456789ab", request.slug)
        assertEquals(null, request.description)
        assertEquals("🧗", request.emoji)
        assertEquals(1, request.driveGrants.size)
    }

    @Test
    fun requestNameIsCappedWithoutSplittingASurrogatePair() {
        val request = CircleDraft("c-0123456789ab", "😀".repeat(CIRCLE_NAME_MAX + 10)).toRequest()
        assertEquals(CIRCLE_NAME_MAX, request.name.codePointCount(0, request.name.length))
    }

    private class FakeBackend : UserCircleBackend {
        val calls = mutableListOf<String>()
        val requests = mutableListOf<CreateAppCircleRequest>()
        var failures = mutableListOf<Throwable>()
        var deleteSucceeds = true
        var known: List<CardCircle> = emptyList()
        var stored: List<ProfileAttribute> = emptyList()
        val created = Uuid.parse("9e22b429-52f7-4d25-80e1-1250b651d343")

        override suspend fun createCircle(request: CreateAppCircleRequest): Uuid {
            requests += request
            failures.removeFirstOrNull()?.let { throw it }
            return created
        }

        override suspend fun deleteCircle(circleId: Uuid, removeMembers: Boolean) {
            calls += "deleteCircle($removeMembers)"
        }

        override suspend fun circles() = known
        override suspend fun attributes() = stored

        override suspend fun saveAudience(attribute: ProfileAttribute, audience: ProfileAudience): ProfileAttribute {
            calls += "save ${attribute.type} -> $audience"
            return attribute
        }

        override suspend fun deleteAttribute(id: Uuid, versionTag: Uuid): Boolean {
            calls += "delete ${stored.first { it.id == id }.type}"
            return deleteSucceeds
        }
    }

    private class FakeAccess(granted: Boolean) : CircleAccess {
        override val granted = MutableStateFlow(granted)
        val cancels = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        override val cancelled: Flow<Unit> = cancels
        val failures = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        override val checkFailed: Flow<Unit> = failures
        override val permission: ExtendPermissionViewModel? = null
        var rechecks = 0
        override fun recheck() { rechecks++ }
        override fun close() = Unit
    }

    private fun viewModel(backend: FakeBackend, access: FakeAccess = FakeAccess(true)) =
        CreateCircleViewModel(UserCircleService(backend), access)

    @Test
    fun createReturnsTheIdAndClosesTheSheet() = runTest(dispatcher) {
        val backend = FakeBackend()
        val vm = viewModel(backend)
        val ids = mutableListOf<String>()
        backgroundScope.launch { vm.events.collect { ids += (it as CreateCircleEvent.Created).circleId } }

        vm.onNewCircleClicked()
        vm.onNameChanged("Climbing")
        vm.onCreateClicked()
        advanceUntilIdle()

        assertEquals(listOf(backend.created.toHexString()), ids)
        assertFalse(vm.uiState.value.open)
        assertEquals("Climbing", backend.requests.single().name)
    }

    @Test
    fun aFailedCreateKeepsTheSheetAndRetryReusesTheSlug() = runTest(dispatcher) {
        val backend = FakeBackend().apply { failures += RuntimeException("timeout") }
        val vm = viewModel(backend)

        vm.onNewCircleClicked()
        vm.onNameChanged("Climbing")
        vm.onCreateClicked()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.open)
        assertEquals(CreateCircleError.Failed, vm.uiState.value.error)
        assertEquals("Climbing", vm.uiState.value.name)

        vm.onCreateClicked()
        advanceUntilIdle()

        assertEquals(2, backend.requests.size)
        assertEquals(backend.requests[0].slug, backend.requests[1].slug)
        assertFalse(vm.uiState.value.open)
    }

    @Test
    fun twoCirclesMayShareANameButNotASlug() = runTest(dispatcher) {
        val backend = FakeBackend()
        val vm = viewModel(backend)
        repeat(2) {
            vm.onNewCircleClicked()
            vm.onNameChanged("Climbing")
            vm.onCreateClicked()
            advanceUntilIdle()
        }
        assertEquals(2, backend.requests.map { it.slug }.toSet().size)
    }

    @Test
    fun aForbiddenCreateAsksForPermissionInsteadOfShowingARawError() = runTest(dispatcher) {
        val access = FakeAccess(true)
        val backend = FakeBackend().apply { failures += ForbiddenException() }
        val vm = viewModel(backend, access)

        vm.onNewCircleClicked()
        vm.onNameChanged("Climbing")
        vm.onCreateClicked()
        advanceUntilIdle()

        assertEquals(CreateCircleError.NeedsPermission, vm.uiState.value.error)
        assertEquals(1, access.rechecks)
        assertTrue(vm.uiState.value.open)
    }

    @Test
    fun approvingFromTheBannerAsksAgainAndTheGrantClearsIt() = runTest(dispatcher) {
        val access = FakeAccess(false)
        val backend = FakeBackend().apply { failures += ForbiddenException() }
        val vm = viewModel(backend, access)
        vm.onNewCircleClicked()
        access.granted.value = true
        access.granted.value = false
        vm.onNameChanged("Climbing")
        vm.onCreateClicked()
        advanceUntilIdle()
        val asked = access.rechecks

        vm.onApproveClicked()
        assertEquals(asked + 1, access.rechecks)
        assertEquals(CreateCircleError.NeedsPermission, vm.uiState.value.error)

        access.granted.value = true
        assertEquals(null, vm.uiState.value.error)
        assertTrue(vm.uiState.value.open)
    }

    @Test
    fun theSheetWaitsForTheGrantWhenTheAppDoesNotHoldIt() = runTest(dispatcher) {
        val access = FakeAccess(false)
        val vm = viewModel(FakeBackend(), access)

        vm.onNewCircleClicked()
        assertFalse(vm.uiState.value.open)
        assertEquals(1, access.rechecks)

        access.granted.value = true
        assertTrue(vm.uiState.value.open)
    }

    @Test
    fun confirmingThePermissionPromptThenReturningOpensTheSheet() = runTest(dispatcher) {
        val access = FakeAccess(false)
        val vm = viewModel(FakeBackend(), access)
        advanceUntilIdle()

        vm.onNewCircleClicked()
        assertTrue(vm.uiState.value.checkingAccess)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.checkingAccess)

        access.granted.value = true
        assertTrue(vm.uiState.value.open)
    }

    @Test
    fun cancellingThePermissionPromptSettlesTheTap() = runTest(dispatcher) {
        val access = FakeAccess(false)
        val vm = viewModel(FakeBackend(), access)
        advanceUntilIdle()

        vm.onNewCircleClicked()
        access.cancels.tryEmit(Unit)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.checkingAccess)
    }

    @Test
    fun aFailedAccessCheckSettlesTheTapAndTheNextTapRechecks() = runTest(dispatcher) {
        val access = FakeAccess(false)
        val vm = viewModel(FakeBackend(), access)

        advanceUntilIdle()
        vm.onNewCircleClicked()
        assertTrue(vm.uiState.value.checkingAccess)
        access.failures.tryEmit(Unit)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.checkingAccess)
        assertTrue(vm.uiState.value.accessCheckFailed)

        vm.onNewCircleClicked()
        assertEquals(2, access.rechecks)
        assertTrue(vm.uiState.value.checkingAccess)
        assertFalse(vm.uiState.value.accessCheckFailed)
    }

    @Test
    fun theEmojiFieldNeverEndsInALoneSurrogate() = runTest(dispatcher) {
        val vm = viewModel(FakeBackend())
        vm.onNewCircleClicked()
        vm.onEmojiChanged("a".repeat(CIRCLE_EMOJI_MAX_CHARS - 1) + "😀😀")

        val emoji = vm.uiState.value.emoji
        assertEquals(CIRCLE_EMOJI_MAX_CHARS, emoji.codePointCount(0, emoji.length))
        assertFalse(emoji.last().isHighSurrogate())
    }

    @Test
    fun aBlankNameCannotBeCreated() = runTest(dispatcher) {
        val backend = FakeBackend()
        val vm = viewModel(backend)
        vm.onNewCircleClicked()
        vm.onNameChanged("   ")
        vm.onCreateClicked()
        advanceUntilIdle()
        assertTrue(backend.requests.isEmpty())
    }

    private fun attribute(type: String, visibility: ProfileVisibility, circles: List<String>?, data: JsonObject = JsonObject(emptyMap())) =
        ProfileAttribute(
            id = Uuid.random(),
            type = type,
            versionTag = Uuid.random(),
            visibility = visibility,
            data = data,
            acl = AccessControlList(requiredSecurityGroup = visibility.wireValue, circleIdList = circles),
        )

    @Test
    fun deleteRemovesTheCardAndPhotoAndLeavesOtherAclsBeforeDeletingTheCircle() = runTest(dispatcher) {
        val target = "9e22b42952f74d2580e11250b651d343"
        val keep = "11111111111141118111111111111111"
        val card = profileCardAttribute(
            ProfileCard(Uuid.NIL, Uuid.NIL, CardAudience.Circle(target, "Climbing"), CardDesign.LINKS, CardOverrides.EMPTY).toData(),
            ProfileVisibility.CONNECTED,
            listOf(target),
        )
        val photo = attribute(
            ProfileAttributeTypes.PHOTO,
            ProfileVisibility.CONNECTED,
            listOf(target),
            JsonObject(mapOf(ProfileAttributeTypes.KEY_PROFILE_IMAGE to JsonPrimitive("img"))),
        )
        val shared = attribute(ProfileAttributeTypes.EMAIL, ProfileVisibility.CONNECTED, listOf(target, keep))
        val onlyHere = attribute(ProfileAttributeTypes.BIO_SUMMARY, ProfileVisibility.CONNECTED, listOf(target))
        val untouched = attribute(ProfileAttributeTypes.PHONE, ProfileVisibility.CONNECTED, listOf(keep))
        val everyone = attribute(ProfileAttributeTypes.NAME, ProfileVisibility.CONNECTED, null)
        val backend = FakeBackend().apply {
            known = listOf(CardCircle(target, "Climbing"), CardCircle(keep, "Friends"))
            stored = listOf(card, photo, shared, onlyHere, untouched, everyone)
        }

        UserCircleService(backend).delete(target, removeMembers = true)

        assertEquals(
            listOf(
                "delete ${ProfileAttributeTypes.PROFILE_CARD}",
                "delete ${ProfileAttributeTypes.PHOTO}",
                "save ${ProfileAttributeTypes.EMAIL} -> ${ProfileAudience.Circles(setOf(keep))}",
                "save ${ProfileAttributeTypes.BIO_SUMMARY} -> ${ProfileAudience.OnlyMe}",
                "deleteCircle(true)",
            ),
            backend.calls,
        )
    }

    @Test
    fun aFailedCleanupLeavesTheCircleInPlace() = runTest(dispatcher) {
        val target = "9e22b42952f74d2580e11250b651d343"
        val backend = object : UserCircleBackend by FakeBackend() {
            var deleted = false
            override suspend fun attributes(): List<ProfileAttribute> = throw RuntimeException("offline")
            override suspend fun deleteCircle(circleId: Uuid, removeMembers: Boolean) { deleted = true }
        }
        runCatching { UserCircleService(backend).delete(target, removeMembers = false) }
        assertFalse(backend.deleted)
    }

    @Test
    fun anAttributeThatWillNotDeleteStopsBeforeTheCircleIsDeleted() = runTest(dispatcher) {
        val target = "9e22b42952f74d2580e11250b651d343"
        val card = profileCardAttribute(
            ProfileCard(Uuid.NIL, Uuid.NIL, CardAudience.Circle(target, "Climbing"), CardDesign.LINKS, CardOverrides.EMPTY).toData(),
            ProfileVisibility.CONNECTED,
            listOf(target),
        )
        val backend = FakeBackend().apply {
            stored = listOf(card)
            deleteSucceeds = false
        }

        assertFailsWith<IllegalStateException> { UserCircleService(backend).delete(target, removeMembers = true) }
        assertFalse(backend.calls.any { it.startsWith("deleteCircle") })
    }
}
