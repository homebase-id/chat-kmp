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

    private val session = object : AvatarOwnerSession {
        override val user: Flow<OwnerSession?> = MutableStateFlow(null)
        override suspend fun reloadAvatar(odinId: OdinId) = Unit
    }

    private val files = object : FileOperationsProvider {
        override fun getCacheDirectory() = System.getProperty("java.io.tmpdir")
        override fun openFileInput(path: String) = error("not used")
        override suspend fun readFileBytes(path: String) = File(path).readBytes()
        override fun deleteTempFile(path: String) = false
        override fun getFileSize(path: String) = File(path).length()
        override suspend fun writeBytesToTempFile(bytes: ByteArray, prefix: String, suffix: String): String =
            File.createTempFile("K11-$prefix", suffix).also { it.writeBytes(bytes); it.deleteOnExit() }.path
        override suspend fun writeBytesToShareOutboundFile(bytes: ByteArray, suffix: String) = error("not used")
        override suspend fun writeStream(path: String, data: Flow<ByteArray>) = error("not used")
    }

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
        val vm = ProfileAvatarEditViewModel(session, harness.profileRepository(), bus, files, circlePhotosSupported) { circles }
        withTimeout(5_000) { vm.state.first { !it.isLoading } }
        return vm
    }

    private suspend fun productionDefaultViewModel(harness: CardWireHarness, bus: CropResultBus): ProfileAvatarEditViewModel {
        val vm = ProfileAvatarEditViewModel(session, harness.profileRepository(), bus, files) { circles }
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
        if (awaitStored) withTimeout(10_000) { vm.state.first { it.circleSlot(circleId).existing != null } }
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
    fun aCirclePhotoIsNotTakenForTheLegacyConnectedOne() = runBlocking {
        val harness = CardWireHarness()
        val legacy = Uuid.random()
        harness.seed(
            legacy, Uuid.random(), ProfileAttributeTypes.PHOTO, "connected",
            buildJsonObject { put(ProfileAttributeTypes.KEY_PROFILE_IMAGE, "legacy") }, 0, null,
        )
        val bus = CropResultBus()
        val vm = viewModel(harness, bus)
        assertEquals(legacy, vm.state.value.connected.existing?.id)
        assertNull(vm.state.value.circleSlot(FAMILY_CIRCLE_ID).existing)

        uploadFamilyPhoto(vm, bus)

        assertEquals(legacy, vm.state.value.connected.existing?.id)
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
    fun copyingFamilyToFriendsLeavesFamilyOwningItsPhotoAndALaterFamilyUploadReplacesIt() = runBlocking {
        val harness = CardWireHarness()
        val familyPhoto = Uuid.random()
        harness.seed(
            familyPhoto, Uuid.random(), ProfileAttributeTypes.PHOTO, "connected",
            buildJsonObject { put(ProfileAttributeTypes.KEY_PROFILE_IMAGE, "family-key") }, 0, listOf(FAMILY_CIRCLE_ID),
        )
        harness.seed(
            Uuid.random(), Uuid.random(), ProfileAttributeTypes.PHONE, "connected",
            buildJsonObject { put(ProfileAttributeTypes.KEY_PHONE, "+14155550123") }, 0, listOf(FAMILY_CIRCLE_ID),
        )
        val repository = harness.profileRepository()
        val source = ProfileCardViewModelTest.FakeSource(emptyList()).apply {
            profileRepository = repository
            liveCards = { repository.loadAttributes() }
            circleList = circles
        }
        val cardVm = ProfileCardViewModel(source) { ProfileCardViewModelTest.FakeHost() }.also { it.startHost() }
        suspend fun awaitCard(condition: (ProfileCardUiState) -> Boolean) =
            withContext(Dispatchers.Default) { withTimeout(10_000) { cardVm.uiState.first(condition) } }
        awaitCard { it.cards.any { c -> c.audience == friends } && it.attributes.isNotEmpty() }
        cardVm.onCardOpened(friends)
        awaitCard { it.selectedAudience == friends && it.importSources.any { s -> s.audience == family } }

        cardVm.onImportRequested(family)
        withContext(Dispatchers.Default) { withTimeout(10_000) { while (harness.puts < 1) delay(10) } }
        awaitCard { !it.isContentBusy && it.importSources.none { s -> s.audience == family } }

        val after = repository.loadAttributes()
        assertEquals(familyPhoto, after.ownPhotoFor(family)?.id)
        assertNull(after.ownPhotoFor(friends))
        assertEquals(listOf(FAMILY_CIRCLE_ID), after.single { it.id == familyPhoto }.acl.circleIdList)
        assertEquals(0, harness.photoPutBodies.size)

        val bus = CropResultBus()
        val avatar = viewModel(harness, bus)
        assertEquals(familyPhoto, avatar.state.value.circleSlot(FAMILY_CIRCLE_ID).existing?.id)
        assertNull(avatar.state.value.circleSlot(FRIENDS_CIRCLE_ID).existing)
        assertNull(avatar.state.value.connected.existing)

        uploadFamilyPhoto(avatar, bus, awaitStored = false)
        withContext(Dispatchers.Default) { withTimeout(10_000) { while (harness.photoPutBodies.isEmpty()) delay(10) } }

        assertEquals(familyPhoto.toString(), harness.photoPutBodies.single()["id"]!!.jsonPrimitive.content)
        assertEquals(1, repository.photoAttributes().size)
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
    fun aLegacyConnectedPhotoScopedToSeveralCirclesShowsInTheLegacySlotAndCanBeRemoved() = runBlocking {
        val harness = CardWireHarness()
        val multi = Uuid.random()
        harness.seed(
            multi, Uuid.random(), ProfileAttributeTypes.PHOTO, "connected",
            buildJsonObject { put(ProfileAttributeTypes.KEY_PROFILE_IMAGE, "multi") }, 0, listOf(FAMILY_CIRCLE_ID, FRIENDS_CIRCLE_ID),
        )
        val everyone = Uuid.random()
        harness.seed(
            everyone, Uuid.random(), ProfileAttributeTypes.PHOTO, "connected",
            buildJsonObject { put(ProfileAttributeTypes.KEY_PROFILE_IMAGE, "everyone") }, 0, null,
        )
        val vm = viewModel(harness)

        assertNotNull(vm.state.value.connected.existing)
        assertNull(vm.state.value.circleSlot(FAMILY_CIRCLE_ID).existing)
        assertNull(vm.state.value.circleSlot(FRIENDS_CIRCLE_ID).existing)
        val legacy = vm.state.value.connected.existing!!

        vm.onAction(ProfileAvatarEditAction.RemoveClicked(ProfileVisibility.CONNECTED))
        vm.onAction(ProfileAvatarEditAction.SaveClicked(ProfileVisibility.CONNECTED))
        withTimeout(5_000) { vm.state.first { it.connected.existing == null } }

        assertEquals(listOf(legacy.id.toString()), harness.deletedIds.toList())
    }

    @Test
    fun aSingleCirclePhotoOfACircleWithNoSlotFallsToTheLegacySlotToo() = runBlocking {
        val harness = CardWireHarness()
        val stray = Uuid.random()
        harness.seed(
            stray, Uuid.random(), ProfileAttributeTypes.PHOTO, "connected",
            buildJsonObject { put(ProfileAttributeTypes.KEY_PROFILE_IMAGE, "stray") }, 0, listOf("11111111-2222-4333-8444-555555555555"),
        )

        assertEquals(stray, viewModel(harness).state.value.connected.existing?.id)
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
