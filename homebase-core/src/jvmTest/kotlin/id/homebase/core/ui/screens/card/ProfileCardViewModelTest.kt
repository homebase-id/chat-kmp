package id.homebase.core.ui.screens.card

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.profile.ProfileAttribute
import id.homebase.api.client.profile.ProfileAttributeTypes
import id.homebase.api.client.profile.ProfileVisibility
import id.homebase.api.common.OdinId
import id.homebase.api.image.ArgbImage
import id.homebase.api.image.ImageUtils
import id.homebase.api.youauth.MissingPermissionsResult
import id.homebase.core.image.HomebaseImageData
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalEncodingApi::class)
class ProfileCardViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private companion object {
        const val DESIGN_ACCESS_URL = "https://frodo.dotyou.cloud/owner/appupdate?d=home"
    }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class FakeHost : CardHost {
        val emitted = MutableSharedFlow<CardEvent>(extraBufferCapacity = 16)
        override val events: SharedFlow<CardEvent> = emitted
        override val isLoaded = MutableStateFlow(true)
        val rendered = mutableListOf<CardPayload>()
        var onExport: () -> Unit = {}
        var disposed = false

        override fun render(payload: CardPayload) {
            rendered += payload
        }

        override fun exportPng() = onExport()
        var edgeProbes = 0
        override fun probeEdges() {
            edgeProbes++
        }
        var onPaintRequest: () -> Unit = {}
        override fun requestPaint() = onPaintRequest()
        var snapshots = 0
        override suspend fun snapshot(): ImageBitmap {
            snapshots++
            return ImageBitmap(2, 2)
        }
        override fun dispose() {
            disposed = true
        }

        fun send(event: CardEvent) = check(emitted.tryEmit(event))
    }

    private class FakeSource(
        private val attributes: List<ProfileAttribute>,
        private val defaults: suspend () -> CardSiteDefaults = { CardSiteDefaults(design = CardDesign.POSTER) },
        private val posts: suspend () -> List<CardPostEntry> = { emptyList() },
    ) : ProfileCardSource {
        override val accessGranted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val imageRequests = mutableListOf<String>()
        val imageEdges = mutableMapOf<String, Int>()
        val written = mutableListOf<ByteArray>()
        var postLoads = 0

        override suspend fun odinId() = OdinId("frodo.dotyou.cloud")
        override suspend fun attributes() = attributes
        override suspend fun siteDefaults(odinId: OdinId): CardSiteDefaults {
            siteDefaultLoads++
            return defaults()
        }

        override suspend fun posts(odinId: OdinId): List<CardPostEntry> {
            postLoads++
            return posts()
        }

        override suspend fun imageSrc(image: HomebaseImageData, maxEdge: Int): String {
            imageRequests += image.payloadKey
            imageEdges[image.payloadKey] = maxEdge
            return "data:image/jpeg;base64,${image.payloadKey}"
        }

        override suspend fun writeShareImage(png: ByteArray): String {
            written += png
            return "/cache/share_outbound/share_1.png"
        }

        val savedDesigns = mutableListOf<String>()
        var onSaveDesign: suspend (String) -> Unit = {}

        override suspend fun savedDesign(): String? = savedDesigns.lastOrNull()

        override suspend fun saveDesign(design: String) {
            onSaveDesign(design)
            savedDesigns += design
        }

        var designAccessMissing = false
        val publishedDesigns = mutableListOf<String>()
        var onPublishDesign: suspend (String) -> CardDesignPublish = { CardDesignPublish.Published }
        var siteDefaultLoads = 0

        override suspend fun missingDesignAccess(odinId: OdinId): MissingPermissionsResult? {
            if (!designAccessMissing) return null
            return MissingPermissionsResult(
                missingDrives = emptyList(),
                missingPermissions = emptyList(),
                missingAllConnectedCircle = false,
                buildExtendPermissionUrl = { DESIGN_ACCESS_URL },
            )
        }

        override suspend fun publishDesign(design: String): CardDesignPublish {
            publishedDesigns += design
            return onPublishDesign(design)
        }
    }

    private fun photo(visibility: ProfileVisibility, payloadKey: String) = ProfileAttribute(
        id = Uuid.random(),
        type = ProfileAttributeTypes.PHOTO,
        versionTag = Uuid.random(),
        visibility = visibility,
        data = JsonObject(mapOf(ProfileAttributeTypes.KEY_PROFILE_IMAGE to JsonPrimitive(payloadKey))),
        fileId = Uuid.random(),
        driveId = Uuid.random(),
        keyHeader = KeyHeader.empty(),
        payloads = listOf(PayloadDescriptor(key = payloadKey, lastModified = 1L)),
    )

    private fun name(visibility: ProfileVisibility, givenName: String) = ProfileAttribute(
        id = Uuid.random(),
        type = ProfileAttributeTypes.NAME,
        versionTag = Uuid.random(),
        visibility = visibility,
        data = JsonObject(mapOf(ProfileAttributeTypes.KEY_GIVEN_NAME to JsonPrimitive(givenName))),
    )

    private val profile = listOf(
        name(ProfileVisibility.ANONYMOUS, "Frodo"),
        name(ProfileVisibility.CONNECTED, "Mr. Frodo"),
        photo(ProfileVisibility.ANONYMOUS, "pub_photo"),
        photo(ProfileVisibility.CONNECTED, "vet_photo"),
    )

    private fun cardPost(slug: String, imageKey: String? = null) = CardPostEntry(
        post = CardPost(id = slug, href = "https://frodo.dotyou.cloud/posts/public-posts/$slug", date = 1),
        image = imageKey?.let {
            HomebaseImageData(driveId = Uuid.random(), fileId = Uuid.random(), payloadKey = it, keyHeader = KeyHeader.empty())
        },
    )

    private fun viewModel(host: CardHost, source: ProfileCardSource) =
        ProfileCardViewModel(source) { host }.also { it.startHost() }

    private fun pngBase64(): String = Base64.encode(
        ImageUtils.encodeArgbToPng(ArgbImage(IntArray(4 * 6) { 0xFF3366CC.toInt() }, 4, 6)),
    )

    @Test
    fun rendersThePublicCardInTheSiteDesignAsSoonAsDataIsReady() = runTest(dispatcher) {
        val host = FakeHost()
        val source = FakeSource(profile)
        viewModel(host, source)

        val payload = host.rendered.single()
        assertEquals(CardDesign.POSTER, payload.design)
        assertEquals("Frodo", payload.data.firstName)
        assertEquals("data:image/jpeg;base64,pub_photo", payload.data.photo?.src)
        assertEquals(listOf("pub_photo"), source.imageRequests)
    }

    @Test
    fun theHostIsBuiltOnceWhenStartedAndGetsTheCardThatWasAlreadyBuilt() = runTest(dispatcher) {
        val host = FakeHost()
        val builtFor = mutableListOf<String>()
        val vm = ProfileCardViewModel(FakeSource(profile)) { odinId -> builtFor += odinId; host }

        assertTrue(builtFor.isEmpty())
        assertNull(vm.host.value)

        vm.startHost()
        vm.startHost()

        assertEquals(listOf("frodo.dotyou.cloud"), builtFor)
        assertEquals(host, vm.host.value)
        assertEquals(listOf("Frodo"), host.rendered.map { it.data.firstName })
    }

    @Test
    fun showingTheCardScreenStartsTheHost() = runTest(dispatcher) {
        val host = FakeHost()
        val vm = ProfileCardViewModel(FakeSource(profile)) { host }

        vm.onScreenShown()

        assertEquals(host, vm.host.value)
        assertEquals(1, host.rendered.size)
    }

    @Test
    fun clearingBeforeTheHostStartedBuildsNothing() = runTest(dispatcher) {
        var built = 0
        val store = ViewModelStore()
        ViewModelProvider.create(
            store,
            viewModelFactory { initializer { ProfileCardViewModel(FakeSource(profile)) { built++; FakeHost() } } },
        )[ProfileCardViewModel::class]

        store.clear()

        assertEquals(0, built)
    }

    @Test
    fun designSwitchRerendersWithoutReencoding() = runTest(dispatcher) {
        val host = FakeHost()
        val source = FakeSource(profile)
        val vm = viewModel(host, source)
        val requestsAfterLoad = source.imageRequests.toList()

        vm.onDesignSelected(CardDesign.DOSSIER)
        vm.onDesignSelected(CardDesign.DOSSIER)

        assertEquals(listOf(CardDesign.POSTER, CardDesign.DOSSIER), host.rendered.map { it.design })
        assertEquals(requestsAfterLoad, source.imageRequests)
    }

    @Test
    fun aDesignPickedBeforeTheSiteDefaultsArriveIsKept() = runTest(dispatcher) {
        val host = FakeHost()
        val defaults = CompletableDeferred<CardSiteDefaults>()
        val vm = viewModel(host, FakeSource(profile, defaults = { defaults.await() }))

        vm.onDesignSelected(CardDesign.COLLAGE)
        defaults.complete(CardSiteDefaults(design = CardDesign.POSTER))
        advanceUntilIdle()

        assertEquals(CardDesign.COLLAGE, vm.uiState.value.design)
        assertEquals(listOf(CardDesign.COLLAGE), host.rendered.map { it.design })
    }

    @Test
    fun anUnsavedEditorPreviewRevertsWhenDiscarded() = runTest(dispatcher) {
        val host = FakeHost()
        val vm = viewModel(host, FakeSource(profile))

        vm.onDesignSelected(CardDesign.DOSSIER)
        assertEquals(CardDesign.DOSSIER, vm.uiState.value.design)
        assertEquals(CardDesign.POSTER, vm.uiState.value.savedDesign)
        assertTrue(vm.uiState.value.canSaveDesign)

        vm.onPreviewDiscarded()

        assertEquals(CardDesign.POSTER, vm.uiState.value.design)
        assertFalse(vm.uiState.value.canSaveDesign)
        assertEquals(listOf(CardDesign.POSTER, CardDesign.DOSSIER, CardDesign.POSTER), host.rendered.map { it.design })
    }

    @Test
    fun aSavedDesignOutlivesTheSiteDefaultOnTheNextShowing() = runTest(dispatcher) {
        val host = FakeHost()
        val source = FakeSource(profile)
        val vm = viewModel(host, source)
        vm.onDesignSelected(CardDesign.COLLAGE)

        val event = async { vm.events.first() }
        vm.onSaveDesign()

        assertEquals(ProfileCardEvent.DesignSaved, event.await())
        assertEquals(listOf(CardDesign.COLLAGE), source.savedDesigns)
        assertEquals(CardDesign.COLLAGE, vm.uiState.value.savedDesign)
        assertNull(vm.uiState.value.previewDesign)
        assertFalse(vm.uiState.value.isSavingDesign)

        vm.onScreenShown()
        advanceUntilIdle()

        assertEquals(CardDesign.COLLAGE, vm.uiState.value.design)
        assertEquals(CardDesign.COLLAGE, host.rendered.last().design)
    }

    private suspend fun TestScope.saveCollectingEvents(
        vm: ProfileCardViewModel,
        design: String,
    ): List<ProfileCardEvent> {
        val events = mutableListOf<ProfileCardEvent>()
        val collector = backgroundScope.launch { vm.events.collect { events += it } }
        vm.onDesignSelected(design)
        vm.onSaveDesign()
        advanceUntilIdle()
        collector.cancel()
        return events
    }

    @Test
    fun savingPublishesTheDesignOnceWithoutRefetchingTheSiteDefaults() = runTest(dispatcher) {
        val source = FakeSource(profile)
        val vm = viewModel(FakeHost(), source)
        assertEquals(1, source.siteDefaultLoads)

        val events = saveCollectingEvents(vm, CardDesign.COLLAGE)

        assertEquals(listOf<ProfileCardEvent>(ProfileCardEvent.DesignSaved), events)
        assertEquals(listOf(CardDesign.COLLAGE), source.savedDesigns)
        assertEquals(listOf(CardDesign.COLLAGE), source.publishedDesigns)

        vm.onScreenShown()
        advanceUntilIdle()
        assertEquals(1, source.siteDefaultLoads)
        assertEquals(CardDesign.COLLAGE, vm.uiState.value.savedDesign)
    }

    @Test
    fun aFailedPublishKeepsTheLocalDesignAndStillReportsSaved() = runTest(dispatcher) {
        val source = FakeSource(profile).apply { onPublishDesign = { throw IllegalStateException("offline") } }
        val host = FakeHost()
        val vm = viewModel(host, source)

        val events = saveCollectingEvents(vm, CardDesign.DOSSIER)

        assertEquals(listOf<ProfileCardEvent>(ProfileCardEvent.DesignSaved), events)
        assertEquals(listOf(CardDesign.DOSSIER), source.savedDesigns)
        assertEquals(CardDesign.DOSSIER, vm.uiState.value.savedDesign)
        assertEquals(CardDesign.DOSSIER, host.rendered.last().design)
    }

    @Test
    fun withoutAThemeNothingIsReportedAndTheLocalDesignStands() = runTest(dispatcher) {
        val source = FakeSource(profile).apply { onPublishDesign = { CardDesignPublish.NoTheme } }
        val vm = viewModel(FakeHost(), source)

        val events = saveCollectingEvents(vm, CardDesign.BOARD)

        assertEquals(listOf<ProfileCardEvent>(ProfileCardEvent.DesignSaved), events)
        assertEquals(CardDesign.BOARD, vm.uiState.value.savedDesign)
        vm.onScreenShown()
        advanceUntilIdle()
        assertEquals(1, source.siteDefaultLoads)
    }

    @Test
    fun aMissingGrantAsksForItInsteadOfWritingAndWritesOnceGranted() = runTest(dispatcher) {
        val source = FakeSource(profile).apply { designAccessMissing = true }
        val vm = viewModel(FakeHost(), source)

        val events = saveCollectingEvents(vm, CardDesign.COLLAGE)

        assertEquals(listOf(ProfileCardEvent.OpenLink(DESIGN_ACCESS_URL), ProfileCardEvent.DesignSaved), events)
        assertEquals(listOf(CardDesign.COLLAGE), source.savedDesigns)
        assertTrue(source.publishedDesigns.isEmpty())

        source.designAccessMissing = false
        source.accessGranted.emit(Unit)
        advanceUntilIdle()

        assertEquals(listOf(CardDesign.COLLAGE), source.publishedDesigns)
    }

    @Test
    fun aDeclinedGrantLeavesTheLocalDesignAndWritesNothing() = runTest(dispatcher) {
        val source = FakeSource(profile).apply { designAccessMissing = true }
        val vm = viewModel(FakeHost(), source)
        saveCollectingEvents(vm, CardDesign.DOSSIER)

        source.accessGranted.emit(Unit)
        advanceUntilIdle()

        assertTrue(source.publishedDesigns.isEmpty())
        assertEquals(CardDesign.DOSSIER, vm.uiState.value.savedDesign)
    }

    @Test
    fun aDesignSavedEarlierWinsOverTheSiteDesign() = runTest(dispatcher) {
        val host = FakeHost()
        val source = FakeSource(profile).apply { savedDesigns += CardDesign.DOSSIER }
        val vm = viewModel(host, source)

        assertEquals(CardDesign.DOSSIER, vm.uiState.value.savedDesign)
        assertEquals(listOf(CardDesign.DOSSIER), host.rendered.map { it.design })
    }

    @Test
    fun edgesAreProbedOnlyOnceTheRenderHasPaintedAndFollowTheirDesign() = runTest(dispatcher) {
        val host = FakeHost().apply { onPaintRequest = { send(CardEvent.Painted) } }
        val vm = viewModel(host, FakeSource(profile))
        var paints = 0
        backgroundScope.launch { vm.paintWhileAttached { paints++ } }

        assertEquals(0, host.edgeProbes)
        host.send(CardEvent.Ready(layout = CardDesign.POSTER, ms = 1))
        assertEquals(1, host.edgeProbes)
        assertEquals(1, paints)
        host.send(CardEvent.Edges(topArgb = 0xFF111111.toInt(), bottomArgb = 0xFF222222.toInt()))
        assertEquals(0xFF222222.toInt(), vm.uiState.value.cardBottomArgb)

        vm.onDesignSelected(CardDesign.COLLAGE)
        assertNull(vm.uiState.value.cardBottomArgb)
        vm.onDesignSelected(CardDesign.POSTER)
        assertEquals(0xFF111111.toInt(), vm.uiState.value.cardTopArgb)
        assertEquals(0xFF222222.toInt(), vm.uiState.value.cardBottomArgb)
    }

    @Test
    fun aPageThatNeverAnswersThePaintRequestStillGoesLive() = runTest(dispatcher) {
        val host = FakeHost()
        val vm = viewModel(host, FakeSource(profile))
        var paints = 0
        backgroundScope.launch { vm.paintWhileAttached { paints++ } }

        host.send(CardEvent.Ready(layout = CardDesign.POSTER, ms = 1))
        advanceTimeBy(2.seconds)

        assertEquals(1, paints)
        assertEquals(1, host.edgeProbes)
    }

    @Test
    fun theCoverIsCapturedOncePerPaintAndDroppedWhenTheSavedDesignChanges() = runTest(dispatcher) {
        val host = FakeHost().apply { onPaintRequest = { send(CardEvent.Painted) } }
        val vm = viewModel(host, FakeSource(profile))
        backgroundScope.launch { vm.paintWhileAttached {} }

        vm.captureCover()
        assertEquals(0, host.snapshots)

        host.send(CardEvent.Ready(layout = CardDesign.POSTER, ms = 1))
        vm.captureCover()
        vm.captureCover()
        assertEquals(1, host.snapshots)
        assertEquals(CardDesign.POSTER, assertNotNull(vm.cover.value).design)

        vm.onDesignSelected(CardDesign.DOSSIER)
        vm.onSaveDesign()
        assertNull(vm.cover.value)
    }

    @Test
    fun aDesignSwitchHoldsAStillOfTheOutgoingDesignUntilTheNewOnePaints() = runTest(dispatcher) {
        val host = FakeHost().apply { onPaintRequest = { send(CardEvent.Painted) } }
        val vm = viewModel(host, FakeSource(profile))
        backgroundScope.launch { vm.paintWhileAttached {} }
        host.send(CardEvent.Ready(layout = CardDesign.POSTER, ms = 1))
        assertNull(vm.designCover.value)

        vm.onDesignSelected(CardDesign.DOSSIER)

        assertEquals(1, host.snapshots)
        assertNotNull(vm.designCover.value)
        assertTrue(vm.uiState.value.isSwitchingDesign)
        assertEquals(CardDesign.DOSSIER, host.rendered.last().design)

        host.send(CardEvent.Ready(layout = CardDesign.DOSSIER, ms = 1))

        assertFalse(vm.uiState.value.isSwitchingDesign)
    }

    @Test
    fun aDesignSwitchBeforeTheCardIsReadyTakesNoStill() = runTest(dispatcher) {
        val host = FakeHost()
        val vm = viewModel(host, FakeSource(profile))

        vm.onDesignSelected(CardDesign.DOSSIER)

        assertEquals(0, host.snapshots)
        assertNull(vm.designCover.value)
        assertFalse(vm.uiState.value.isSwitchingDesign)
    }

    @Test
    fun aFailedSaveKeepsThePreviewAndTheSavedDesign() = runTest(dispatcher) {
        val host = FakeHost()
        val source = FakeSource(profile).apply { onSaveDesign = { throw IllegalStateException("offline") } }
        val vm = viewModel(host, source)
        vm.onDesignSelected(CardDesign.DOSSIER)

        val event = async { vm.events.first() }
        vm.onSaveDesign()

        assertEquals(ProfileCardEvent.DesignSaveFailed, event.await())
        val state = vm.uiState.value
        assertEquals(CardDesign.DOSSIER, state.design)
        assertEquals(CardDesign.POSTER, state.savedDesign)
        assertFalse(state.isSavingDesign)
        assertTrue(state.canSaveDesign)
    }

    @Test
    fun savingShowsProgressAndIgnoresARepeatTap() = runTest(dispatcher) {
        val host = FakeHost()
        val gate = CompletableDeferred<Unit>()
        val source = FakeSource(profile).apply { onSaveDesign = { gate.await() } }
        val vm = viewModel(host, source)
        vm.onDesignSelected(CardDesign.BOARD)

        vm.onSaveDesign()
        vm.onSaveDesign()
        assertTrue(vm.uiState.value.isSavingDesign)
        assertFalse(vm.uiState.value.canSaveDesign)

        gate.complete(Unit)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isSavingDesign)
        assertEquals(listOf(CardDesign.BOARD), source.savedDesigns)
    }

    @Test
    fun savingTheUnchangedDesignDoesNothing() = runTest(dispatcher) {
        val source = FakeSource(profile)
        val vm = viewModel(FakeHost(), source)

        vm.onSaveDesign()

        assertTrue(source.savedDesigns.isEmpty())
        assertFalse(vm.uiState.value.isSavingDesign)
    }

    @Test
    fun shareDecodesThePngAndHandsTheCachedFileOn() = runTest(dispatcher) {
        val host = FakeHost()
        val source = FakeSource(profile)
        val vm = viewModel(host, source)
        host.send(CardEvent.Ready(layout = CardDesign.POSTER, ms = 12))
        val png = pngBase64()
        host.onExport = { host.send(CardEvent.Png(png, 4, 6)) }

        val event = async { vm.events.first() }
        vm.onShareClicked()

        val share = assertIs<ProfileCardEvent.ShareImage>(event.await())
        assertEquals("/cache/share_outbound/share_1.png", share.path)
        assertEquals("frodo.dotyou.cloud-card.png", share.fileName)
        assertContentEquals(Base64.decode(png), source.written.single())
        vm.uiState.first { !it.isExporting }
    }

    @Test
    fun anUndecodableExportIsNeverShared() = runTest(dispatcher) {
        val host = FakeHost()
        val source = FakeSource(profile)
        val vm = viewModel(host, source)
        host.send(CardEvent.Ready(layout = CardDesign.POSTER, ms = 12))
        host.onExport = { host.send(CardEvent.Png("not base64 !!", 1080, 2338)) }

        val event = async { vm.events.first() }
        vm.onShareClicked()

        assertEquals(ProfileCardEvent.ShareFailed, event.await())
        assertTrue(source.written.isEmpty())
    }

    @Test
    fun anExportThatNeverAnswersFailsAfterTheTimeout() = runTest(dispatcher) {
        val host = FakeHost()
        val vm = viewModel(host, FakeSource(profile))
        host.send(CardEvent.Ready(layout = CardDesign.POSTER, ms = 12))
        val events = mutableListOf<ProfileCardEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }

        vm.onShareClicked()
        assertTrue(vm.uiState.value.isExporting)
        vm.onShareClicked()
        advanceTimeBy(31.seconds)

        assertEquals(listOf<ProfileCardEvent>(ProfileCardEvent.ShareFailed), events)
        assertFalse(vm.uiState.value.isExporting)
    }

    @Test
    fun shareWaitsForTheCardToBeReady() = runTest(dispatcher) {
        val host = FakeHost()
        var exports = 0
        host.onExport = { exports++ }
        val vm = viewModel(host, FakeSource(profile))

        vm.onShareClicked()

        assertEquals(0, exports)
        assertFalse(vm.uiState.value.canShare)
    }

    @Test
    fun aCardErrorBecomesASnackbarEventAndMarksTheUnreadyCardFailed() = runTest(dispatcher) {
        val host = FakeHost()
        val vm = viewModel(host, FakeSource(profile))
        val events = mutableListOf<ProfileCardEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }

        host.send(CardEvent.Error("blocked navigation to https://elsewhere.example"))

        assertEquals(listOf<ProfileCardEvent>(ProfileCardEvent.CardFailed), events)
        assertTrue(vm.uiState.value.cardFailed)

        host.send(CardEvent.Ready(layout = CardDesign.POSTER, ms = 12))
        assertTrue(vm.uiState.value.isCardReady)
        assertFalse(vm.uiState.value.cardFailed)
    }

    @Test
    fun anUnsupportedServerShowsItsOwnMessageWithoutASnackbar() = runTest(dispatcher) {
        val host = FakeHost()
        val vm = viewModel(host, FakeSource(profile))
        val events = mutableListOf<ProfileCardEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }

        host.send(CardEvent.Error("no loaded event", unsupported = true))

        assertTrue(vm.uiState.value.cardUnsupported)
        assertFalse(vm.uiState.value.isCardReady)
        assertTrue(events.isEmpty(), "$events")
    }

    @Test
    fun onlyWebLinksAreOpened() = runTest(dispatcher) {
        val host = FakeHost()
        val vm = viewModel(host, FakeSource(profile))
        val events = mutableListOf<ProfileCardEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }

        host.send(CardEvent.Link("javascript:alert(1)"))
        host.send(CardEvent.Link("https://twitter.com/frodo"))

        assertEquals(listOf<ProfileCardEvent>(ProfileCardEvent.OpenLink("https://twitter.com/frodo")), events)
    }

    @Test
    fun clearingTheOwningStoreDisposesTheHost() = runTest(dispatcher) {
        val host = FakeHost()
        val store = ViewModelStore()
        ViewModelProvider.create(
            store,
            viewModelFactory { initializer { viewModel(host, FakeSource(profile)) } },
        )[ProfileCardViewModel::class]

        assertFalse(host.disposed)
        store.clear()
        assertTrue(host.disposed)
    }

    @Test
    fun postsRenderWithSmallThumbnailsEncodedOnce() = runTest(dispatcher) {
        val host = FakeHost()
        val source = FakeSource(profile, posts = { listOf(cardPost("pictured", imageKey = "post_img"), cardPost("plain")) })
        val vm = viewModel(host, source)

        val posts = host.rendered.last().data.posts
        assertEquals(listOf("pictured", "plain"), posts.map { it.id })
        assertEquals(CardImage("data:image/jpeg;base64,post_img"), posts[0].image)
        assertNull(posts[1].image)

        vm.onDesignSelected(CardDesign.DOSSIER)
        assertEquals(1, source.postLoads)
        assertEquals(1, source.imageRequests.count { it == "post_img" })
        assertEquals(CARD_POST_IMAGE_MAX_EDGE, source.imageEdges["post_img"])
        assertEquals(CARD_IMAGE_MAX_EDGE, source.imageEdges["pub_photo"])
    }

    @Test
    fun thePreWarmPostsServeTheFirstShowingAndALaterOneRefetches() = runTest(dispatcher) {
        val source = FakeSource(profile, posts = { listOf(cardPost("p1")) })
        val vm = viewModel(FakeHost(), source)
        assertEquals(1, source.postLoads)

        vm.onScreenShown()
        assertEquals(1, source.postLoads)

        vm.onScreenShown()
        assertEquals(2, source.postLoads)
    }

    @Test
    fun aFailedPostLoadStillRendersTheCardAndRetriesOnTheNextLoad() = runTest(dispatcher) {
        val host = FakeHost()
        var fail = true
        val source = FakeSource(profile, posts = {
            if (fail) error("channel drives unavailable")
            listOf(cardPost("p1"))
        })
        val vm = viewModel(host, source)
        assertEquals(emptyList(), host.rendered.last().data.posts)
        assertEquals("Frodo", host.rendered.last().data.firstName)

        fail = false
        vm.onRetry()

        assertEquals(2, source.postLoads)
        assertEquals(listOf("p1"), host.rendered.last().data.posts.map { it.id })
    }

    @Test
    fun decodeCardPngAcceptsOnlyAPng() {
        val png = pngBase64()
        assertContentEquals(Base64.decode(png), assertNotNull(decodeCardPng(png)))
        assertNull(decodeCardPng(""))
        assertNull(decodeCardPng("%%%"))
        assertNull(decodeCardPng(Base64.encode("GIF89a not a png at all".encodeToByteArray())))
    }
}
