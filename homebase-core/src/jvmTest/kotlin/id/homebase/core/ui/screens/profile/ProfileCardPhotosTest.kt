@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.profile

import id.homebase.api.client.auth.OwnerSession
import id.homebase.api.client.profile.ProfileAttribute
import id.homebase.api.client.profile.ProfileAttributeTypes
import id.homebase.api.client.profile.ProfileVisibility
import id.homebase.api.common.OdinId
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.image.ImageResult
import id.homebase.api.image.ImageSize
import id.homebase.core.ui.screens.card.CardAudience
import id.homebase.core.ui.screens.card.CardCircle
import id.homebase.core.ui.screens.card.CardWireHarness
import id.homebase.core.ui.screens.card.FAMILY_CIRCLE_ID
import id.homebase.core.ui.screens.card.FRIENDS_CIRCLE_ID
import id.homebase.core.ui.screens.card.ProfileCardUiState
import id.homebase.core.ui.screens.card.ProfileCardViewModel
import id.homebase.core.ui.screens.card.ProfileCardViewModelTest
import id.homebase.core.ui.screens.card.WORK_CIRCLE_ID
import id.homebase.core.ui.screens.card.ownPhotoFor
import id.homebase.core.ui.screens.card.photoFor
import id.homebase.imageeditor.ui.CropResultBus
import io.github.vinceglb.filekit.PlatformFile
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The real avatar ViewModel and ProfileRepository over a fake transport that decrypts what the app PUTs. */
class ProfileCardPhotosTest {

    private val circles = listOf(
        CardCircle(FAMILY_CIRCLE_ID, "Family"),
        CardCircle(FRIENDS_CIRCLE_ID, "Friends"),
        CardCircle(WORK_CIRCLE_ID, "Work"),
    )
    private val family = CardAudience.Circle(FAMILY_CIRCLE_ID, "Family")
    private val friends = CardAudience.Circle(FRIENDS_CIRCLE_ID, "Friends")

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun png(): ByteArray = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB), "png", it)
    }.toByteArray()

    private fun seedPublicPhoto(harness: CardWireHarness): Uuid =
        Uuid.random().also {
            harness.seed(
                it, Uuid.random(), ProfileAttributeTypes.PHOTO, "anonymous",
                buildJsonObject { put(ProfileAttributeTypes.KEY_PROFILE_IMAGE, "public-key") }, 0, null,
            )
        }

    private suspend fun viewModel(harness: CardWireHarness, bus: CropResultBus = CropResultBus(), circlePhotosSupported: Boolean = true): ProfileAvatarEditViewModel {
        val vm = testAvatarViewModel(harness.profileRepository(), circles, circlePhotosSupported, bus)
        withTimeout(5_000) { vm.state.first { !it.isLoading } }
        return vm
    }

    private suspend fun productionDefaultViewModel(harness: CardWireHarness, bus: CropResultBus): ProfileAvatarEditViewModel {
        val vm = testAvatarViewModel(harness.profileRepository(), circles, bus = bus)
        withTimeout(5_000) { vm.state.first { !it.isLoading } }
        return vm
    }

    private suspend fun uploadFamilyPhoto(vm: ProfileAvatarEditViewModel, bus: CropResultBus, awaitStored: Boolean = true, saveOnCrop: Boolean = false, circleId: String = FAMILY_CIRCLE_ID) {
        val source = File.createTempFile("K11-source", ".png").also { it.writeBytes(png()); it.deleteOnExit() }
        val requested = vm.events
        val request = kotlinx.coroutines.CompletableDeferred<Uuid>()
        val collector = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined).let { scope ->
            scope.launchCollect(requested, request)
        }
        vm.onAction(ProfileAvatarEditAction.PhotoPicked(ProfileVisibility.CONNECTED, PlatformFile(source), circleId, saveOnCrop))
        val requestId = withTimeout(5_000) { request.await() }
        collector.cancel()
        bus.postResult(requestId, ImageResult(png(), ImageSize(64, 64), ImageSize(64, 64)))
        if (!saveOnCrop) {
            withTimeout(5_000) { vm.state.first { it.circleSlot(circleId).pendingCroppedAvatar != null } }
            vm.onAction(ProfileAvatarEditAction.SaveClicked(ProfileVisibility.CONNECTED, circleId))
        }
        if (awaitStored) withTimeout(10_000) { vm.state.first { it.circleSlot(circleId).let { s -> s.existing != null && !s.isUploading } } }
    }

    private fun kotlinx.coroutines.CoroutineScope.launchCollect(
        events: Flow<ProfileAvatarEditEvent>,
        into: kotlinx.coroutines.CompletableDeferred<Uuid>,
    ) = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
        events.collect { if (it is ProfileAvatarEditEvent.NavigateToCropper) into.complete(it.requestId) }
    }

    @Test
    fun familyPhotoIsWrittenAsConnectedPlusTheFamilyCircle() = runBlocking {
        val harness = CardWireHarness()
        val bus = CropResultBus()
        val vm = viewModel(harness, bus)

        uploadFamilyPhoto(vm, bus)

        val body = harness.photoPutBodies.single()
        assertEquals("Connected", body["visibility"]!!.jsonPrimitive.content)
        assertEquals(listOf(FAMILY_CIRCLE_ID), body["circleIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        val stored = harness.profileRepository().photoAttributes().single()
        assertEquals(ProfileVisibility.CONNECTED, stored.visibility)
        assertEquals(listOf(FAMILY_CIRCLE_ID), stored.acl.circleIdList)
    }

    @Test
    fun familyCardUsesItsOwnPhotoWhileFriendsKeepsThePublicOne() = runBlocking {
        val harness = CardWireHarness()
        val publicId = seedPublicPhoto(harness)
        val bus = CropResultBus()
        val vm = viewModel(harness, bus)
        assertEquals(publicId, vm.state.value.photos.photoFor(family)?.id)

        uploadFamilyPhoto(vm, bus)

        val state = vm.state.value
        assertEquals(state.circleSlot(FAMILY_CIRCLE_ID).existing?.id, state.photos.photoFor(family)?.id)
        assertEquals(publicId, state.photos.photoFor(friends)?.id)
        assertEquals(publicId, state.photos.photoFor(CardAudience.Public)?.id)
    }

    @Test
    fun removingTheFamilyPhotoFallsBackToTheInheritedOne() = runBlocking {
        val harness = CardWireHarness()
        val publicId = seedPublicPhoto(harness)
        val bus = CropResultBus()
        val vm = viewModel(harness, bus)
        uploadFamilyPhoto(vm, bus)
        val familyPhoto = vm.state.value.circleSlot(FAMILY_CIRCLE_ID).existing!!

        vm.onAction(ProfileAvatarEditAction.RemoveClicked(ProfileVisibility.CONNECTED, FAMILY_CIRCLE_ID))
        vm.onAction(ProfileAvatarEditAction.SaveClicked(ProfileVisibility.CONNECTED, FAMILY_CIRCLE_ID))
        withTimeout(5_000) { vm.state.first { it.circleSlot(FAMILY_CIRCLE_ID).existing == null } }

        assertEquals(listOf(familyPhoto.id.toString()), harness.deletedIds.toList())
        assertEquals(publicId, vm.state.value.photos.photoFor(family)?.id)
        assertNull(vm.state.value.circleSlot(FAMILY_CIRCLE_ID).existing)
    }

    @Test
    fun withTwoPublicPhotosTheSlotHoldsTheOneThePublicCardShows() = runBlocking {
        val harness = CardWireHarness()
        harness.seed(
            Uuid.random(), Uuid.random(), ProfileAttributeTypes.PHOTO, "anonymous",
            buildJsonObject { put(ProfileAttributeTypes.KEY_PROFILE_IMAGE, "later") }, 9000, null,
        )
        val shown = Uuid.random()
        harness.seed(
            shown, Uuid.random(), ProfileAttributeTypes.PHOTO, "anonymous",
            buildJsonObject { put(ProfileAttributeTypes.KEY_PROFILE_IMAGE, "shown") }, 1000, null,
        )
        val vm = viewModel(harness)

        assertEquals(shown, vm.state.value.photos.photoFor(CardAudience.Public)?.id)
        assertEquals(shown, vm.state.value.anonymous.existing?.id)
    }

    @Test
    fun removeAsksFirstAndOnlyDeletesOnceConfirmed() = runBlocking {
        val harness = CardWireHarness()
        val publicId = seedPublicPhoto(harness)
        val vm = viewModel(harness)

        vm.onAction(ProfileAvatarEditAction.RemoveRequested(ProfileVisibility.ANONYMOUS))
        assertEquals(true, vm.state.value.removalToConfirm?.sharedByOtherCards)
        vm.onAction(ProfileAvatarEditAction.RemoveCancelled)

        assertNull(vm.state.value.removalToConfirm)
        assertEquals(0, harness.deletes)
        assertEquals(publicId, vm.state.value.anonymous.existing?.id)

        vm.onAction(ProfileAvatarEditAction.RemoveRequested(ProfileVisibility.ANONYMOUS))
        assertEquals(0, harness.deletes)
        vm.onAction(ProfileAvatarEditAction.RemoveConfirmed)
        withTimeout(5_000) { vm.state.first { it.anonymous.existing == null } }

        assertEquals(listOf(publicId.toString()), harness.deletedIds.toList())
    }

    @Test
    fun aCirclePhotoIsNotTakenForTheLegacyConnectedOne() = runBlocking {
        val harness = CardWireHarness()
        val legacy = Uuid.random()
        harness.seed(
            legacy, Uuid.random(), ProfileAttributeTypes.PHOTO, "connected",
            buildJsonObject { put(ProfileAttributeTypes.KEY_PROFILE_IMAGE, "legacy") }, 0, null,
        )
        val bus = CropResultBus()
        val vm = viewModel(harness, bus)
        assertNull(vm.state.value.circleSlot(FAMILY_CIRCLE_ID).existing)

        uploadFamilyPhoto(vm, bus)

        assertNotNull(vm.state.value.circleSlot(FAMILY_CIRCLE_ID).existing)
        assertEquals(2, harness.profileRepository().photoAttributes().size)
        assertNull(harness.photoPutBodies.single()["id"])
    }

    private fun ProfileAttribute.imageKey() = data[ProfileAttributeTypes.KEY_PROFILE_IMAGE]!!.jsonPrimitive.content

    private suspend fun awaitRendered(host: ProfileCardViewModelTest.FakeHost, src: String) =
        withContext(Dispatchers.Default) {
            try {
                withTimeout(10_000) {
                    while (host.rendered.lastOrNull()?.data?.photo?.src != src) delay(10)
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                throw AssertionError("wanted $src, rendered ${host.rendered.map { it.audience?.kind to it.data.photo?.src }}")
            }
        }

    @Test
    fun theFamilyCardPayloadCarriesTheFamilyPhotoWhileFriendsKeepsThePublicOne() = runBlocking {
        val harness = CardWireHarness()
        seedPublicPhoto(harness)
        val bus = CropResultBus()
        val avatar = viewModel(harness, bus)
        uploadFamilyPhoto(avatar, bus)
        val familyKey = avatar.state.value.circleSlot(FAMILY_CIRCLE_ID).existing!!.imageKey()

        val repository = harness.profileRepository()
        val source = ProfileCardViewModelTest.FakeSource(emptyList()).apply {
            liveCards = { repository.loadAttributes() }
            circleList = circles
        }
        val host = ProfileCardViewModelTest.FakeHost()
        val cardVm = ProfileCardViewModel(source) { host }.also { it.startHost() }

        withContext(Dispatchers.Default) {
            withTimeout(10_000) { while (cardVm.uiState.value.cards.none { it.audience == family }) delay(10) }
        }
        cardVm.onCardOpened(family)
        awaitRendered(host, "data:image/jpeg;base64,$familyKey")
        cardVm.onCardOpened(friends)
        awaitRendered(host, "data:image/jpeg;base64,public-key")
        cardVm.onCardOpened(CardAudience.Public)
        awaitRendered(host, "data:image/jpeg;base64,public-key")
    }

    private suspend fun cardViewModelFollowing(harness: CardWireHarness, avatar: ProfileAvatarEditViewModel): Pair<ProfileCardViewModel, ProfileCardViewModelTest.FakeHost> {
        val repository = harness.profileRepository()
        val source = ProfileCardViewModelTest.FakeSource(emptyList()).apply {
            liveCards = { repository.loadAttributes() }
            circleList = circles
        }
        val host = ProfileCardViewModelTest.FakeHost()
        val cardVm = ProfileCardViewModel(source) { host }.also { it.startHost() }
        withContext(Dispatchers.Default) {
            withTimeout(10_000) { while (cardVm.uiState.value.cards.none { it.audience == friends }) delay(10) }
        }
        kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined).launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            avatar.events.collect { if (it is ProfileAvatarEditEvent.PhotoSaved) cardVm.onPhotoChanged() }
        }
        return cardVm to host
    }

    @Test
    fun changingTheFriendsPhotoFromTheEditorWritesItScopedAndRefreshesOnlyTheFriendsCard() = runBlocking {
        val harness = CardWireHarness()
        seedPublicPhoto(harness)
        val bus = CropResultBus()
        val avatar = viewModel(harness, bus)
        val (cardVm, host) = cardViewModelFollowing(harness, avatar)
        cardVm.onCardOpened(friends)
        awaitRendered(host, "data:image/jpeg;base64,public-key")

        uploadFamilyPhoto(avatar, bus, saveOnCrop = true, circleId = FRIENDS_CIRCLE_ID)

        val body = harness.photoPutBodies.single()
        assertEquals("Connected", body["visibility"]!!.jsonPrimitive.content)
        assertEquals(listOf(FRIENDS_CIRCLE_ID), body["circleIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        val friendsKey = avatar.state.value.circleSlot(FRIENDS_CIRCLE_ID).existing!!.imageKey()
        awaitRendered(host, "data:image/jpeg;base64,$friendsKey")
        cardVm.onCardOpened(CardAudience.Public)
        awaitRendered(host, "data:image/jpeg;base64,public-key")
        cardVm.onCardOpened(family)
        awaitRendered(host, "data:image/jpeg;base64,public-key")
    }

    @Test
    fun removingTheFriendsPhotoFromTheEditorDeletesItAndTheCardFallsBackToThePublicOne() = runBlocking {
        val harness = CardWireHarness()
        seedPublicPhoto(harness)
        val bus = CropResultBus()
        val avatar = viewModel(harness, bus)
        val (cardVm, host) = cardViewModelFollowing(harness, avatar)
        uploadFamilyPhoto(avatar, bus, saveOnCrop = true, circleId = FRIENDS_CIRCLE_ID)
        val friendsPhoto = avatar.state.value.circleSlot(FRIENDS_CIRCLE_ID).existing!!
        cardVm.onCardOpened(friends)
        awaitRendered(host, "data:image/jpeg;base64,${friendsPhoto.imageKey()}")

        avatar.onAction(ProfileAvatarEditAction.RemoveClicked(ProfileVisibility.CONNECTED, FRIENDS_CIRCLE_ID))
        avatar.onAction(ProfileAvatarEditAction.SaveClicked(ProfileVisibility.CONNECTED, FRIENDS_CIRCLE_ID))

        awaitRendered(host, "data:image/jpeg;base64,public-key")
        assertEquals(listOf(friendsPhoto.id.toString()), harness.deletedIds.toList())
        assertNull(avatar.state.value.circleSlot(FRIENDS_CIRCLE_ID).existing)
    }

    @Test
    fun aServerThatDropsCircleIdsFailsTheUploadAndLeavesNoStrayPhotoBehind() = runBlocking {
        val harness = CardWireHarness(photoCircleIds = false)
        val publicId = seedPublicPhoto(harness)
        val bus = CropResultBus()
        val vm = viewModel(harness, bus)
        val failed = kotlinx.coroutines.CompletableDeferred<ProfileAvatarEditEvent>()
        val job = launch(Dispatchers.Unconfined, start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            vm.events.collect { if (it is ProfileAvatarEditEvent.UploadFailed) failed.complete(it) }
        }

        uploadFamilyPhoto(vm, bus, awaitStored = false)
        withTimeout(5_000) { failed.await() }
        job.cancel()

        assertNull(vm.state.value.circleSlot(FAMILY_CIRCLE_ID).existing)
        assertEquals(listOf(publicId.toString()), harness.storedIds)
        assertEquals(publicId, vm.state.value.photos.photoFor(family)?.id)
        assertEquals(1, harness.deletes)
        assertNotNull(harness.photoPutBodies.single())
        assertEquals(1, harness.deletedIds.size)
        assertEquals(false, harness.deletedIds.single() == publicId.toString())
    }

    @Test
    fun anUnverifiableCirclePhotoIsDeletedAndReportedFailed() = runBlocking {
        val harness = CardWireHarness(photoCircleIds = false, queryFailsAfterPhotoPut = true)
        val publicId = seedPublicPhoto(harness)
        val bus = CropResultBus()
        val vm = viewModel(harness, bus)
        val failed = kotlinx.coroutines.CompletableDeferred<ProfileAvatarEditEvent>()
        val job = launch(Dispatchers.Unconfined, start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            vm.events.collect { if (it is ProfileAvatarEditEvent.UploadFailed) failed.complete(it) }
        }

        uploadFamilyPhoto(vm, bus, awaitStored = false)
        withTimeout(5_000) { failed.await() }
        job.cancel()

        assertEquals(1, harness.deletes)
        assertEquals(listOf(publicId.toString()), harness.storedIds)
    }

    @Test
    fun aFailedReadAfterReplacingAnExistingFamilyPhotoKeepsThePhoto() = runBlocking {
        val harness = CardWireHarness(queryFailsAfterPhotoPut = true)
        val familyPhoto = Uuid.random()
        harness.seed(
            familyPhoto, Uuid.random(), ProfileAttributeTypes.PHOTO, "connected",
            buildJsonObject { put(ProfileAttributeTypes.KEY_PROFILE_IMAGE, "family-key") }, 0, listOf(FAMILY_CIRCLE_ID),
        )
        val bus = CropResultBus()
        val vm = viewModel(harness, bus)
        val failed = kotlinx.coroutines.CompletableDeferred<ProfileAvatarEditEvent>()
        val job = launch(Dispatchers.Unconfined, start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            vm.events.collect { if (it is ProfileAvatarEditEvent.UploadFailed) failed.complete(it) }
        }

        uploadFamilyPhoto(vm, bus, awaitStored = false)
        withTimeout(5_000) { failed.await() }
        job.cancel()

        assertEquals(familyPhoto.toString(), harness.photoPutBodies.single()["id"]!!.jsonPrimitive.content)
        assertEquals(0, harness.deletes)
        assertEquals(listOf(familyPhoto.toString()), harness.storedIds)
    }

    @Test
    fun aServerWithoutCirclePhotoSupportNeverReceivesACirclePhoto() = runBlocking {
        val harness = CardWireHarness(photoCircleIds = false)
        val bus = CropResultBus()
        val vm = viewModel(harness, bus, circlePhotosSupported = false)
        val failed = kotlinx.coroutines.CompletableDeferred<ProfileAvatarEditEvent>()
        val job = launch(Dispatchers.Unconfined, start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            vm.events.collect { if (it is ProfileAvatarEditEvent.UploadFailed) failed.complete(it) }
        }

        uploadFamilyPhoto(vm, bus, awaitStored = false)
        withTimeout(5_000) { failed.await() }
        job.cancel()

        assertEquals(emptyList(), harness.photoPutBodies)
        assertEquals(emptyList(), harness.storedIds)
    }

    @Test
    fun withTheFlagAtItsProductionDefaultNoCircleScopedPutIsEverSent() = runBlocking {
        val harness = CardWireHarness(photoCircleIds = false)
        val bus = CropResultBus()
        val vm = productionDefaultViewModel(harness, bus)
        assertEquals(SERVER_STORES_CIRCLE_PHOTOS, vm.state.value.circlePhotosSupported)
        val failed = kotlinx.coroutines.CompletableDeferred<ProfileAvatarEditEvent>()
        val job = launch(Dispatchers.Unconfined, start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            vm.events.collect { if (it is ProfileAvatarEditEvent.UploadFailed) failed.complete(it) }
        }

        uploadFamilyPhoto(vm, bus, awaitStored = false)

        if (SERVER_STORES_CIRCLE_PHOTOS) {
            withTimeout(10_000) { vm.state.first { it.circleSlot(FAMILY_CIRCLE_ID).existing != null || failed.isCompleted } }
        } else {
            withTimeout(5_000) { failed.await() }
            assertEquals(emptyList(), harness.photoPutBodies)
            assertEquals(emptyList(), harness.putBodies)
            assertEquals(0, harness.puts)
            assertEquals(emptyList(), harness.storedIds)
        }
        job.cancel()
    }

    @Test
    fun aSaveOnCropUploadRejectedAsTooLargeLeavesTheSlotFreeToRemoveAndRetry() = runBlocking {
        val harness = CardWireHarness(
            photoReply = CardWireHarness.Reply.Problem(
                400,
                """{"type":"x","title":"x","status":400,"errorCode":"maxContentLengthExceeded"}""",
            ),
        )
        seedPublicPhoto(harness)
        val bus = CropResultBus()
        val vm = viewModel(harness, bus)
        val tooLarge = kotlinx.coroutines.CompletableDeferred<ProfileAvatarEditEvent>()
        val job = launch(Dispatchers.Unconfined, start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            vm.events.collect { if (it is ProfileAvatarEditEvent.UploadTooLarge) tooLarge.complete(it) }
        }

        uploadFamilyPhoto(vm, bus, awaitStored = false, saveOnCrop = true)
        withTimeout(5_000) { tooLarge.await() }
        job.cancel()

        val slot = vm.state.value.circleSlot(FAMILY_CIRCLE_ID)
        assertEquals(false, slot.isUploading)
        assertNull(slot.pendingCroppedAvatar)
        assertEquals(false, slot.canUpload)
    }

    @Test
    fun pickingWithSaveOnCropUploadsWithoutASavePressAndAnnouncesIt() = runBlocking {
        val harness = CardWireHarness()
        val publicId = seedPublicPhoto(harness)
        val bus = CropResultBus()
        val vm = viewModel(harness, bus)
        val saved = kotlinx.coroutines.CompletableDeferred<ProfileAvatarEditEvent.PhotoSaved>()
        val job = launch(Dispatchers.Unconfined, start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            vm.events.collect { if (it is ProfileAvatarEditEvent.PhotoSaved) saved.complete(it) }
        }

        uploadFamilyPhoto(vm, bus, saveOnCrop = true)
        val event = withTimeout(5_000) { saved.await() }
        job.cancel()

        assertEquals(FAMILY_CIRCLE_ID, event.circleId)
        assertEquals(listOf(FAMILY_CIRCLE_ID), harness.photoPutBodies.single()["circleIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(vm.state.value.circleSlot(FAMILY_CIRCLE_ID).existing?.id, vm.state.value.photos.photoFor(family)?.id)
        assertEquals(publicId, vm.state.value.photos.photoFor(friends)?.id)
    }

    @Test
    fun theCardViewModelAndTheAvatarStateResolveAPhotoThroughTheSameHelper() {
        val attributes = listOf(
            photoAttribute(ProfileVisibility.ANONYMOUS, "pub", null),
            photoAttribute(ProfileVisibility.CONNECTED, "fam", listOf(FAMILY_CIRCLE_ID)),
        )
        val state = ProfileAvatarEditUiState(photos = attributes)
        for (card in listOf(CardAudience.Public, family, friends)) {
            assertEquals(attributes.photoFor(card), state.photos.photoFor(card))
        }
        assertEquals("fam", state.photos.photoFor(family)!!.imageKey())
        assertEquals("pub", state.photos.photoFor(friends)!!.imageKey())
    }

    private fun photoAttribute(visibility: ProfileVisibility, key: String, circles: List<String>?) = ProfileAttribute(
        id = Uuid.random(),
        type = ProfileAttributeTypes.PHOTO,
        versionTag = Uuid.random(),
        visibility = visibility,
        data = buildJsonObject { put(ProfileAttributeTypes.KEY_PROFILE_IMAGE, key) },
        acl = id.homebase.api.client.drives.AccessControlList(requiredSecurityGroup = visibility.wireValue, circleIdList = circles),
    )
}
