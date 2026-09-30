package id.homebase.core.ui.screens.card

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.profile.ProfileAttribute
import id.homebase.api.client.drives.AccessControlList
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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

        var onRender: (CardPayload) -> Unit = {}

        override fun render(payload: CardPayload) {
            rendered += payload
            onRender(payload)
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
        var attributes: List<ProfileAttribute>,
        private val defaults: suspend () -> CardSiteDefaults = { CardSiteDefaults(design = CardDesign.POSTER) },
        private val posts: suspend () -> List<CardPostEntry> = { emptyList() },
        private val cardRepository: CardRepository? = null,
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

        val cardWrites = MutableStateFlow(0)

        override suspend fun savePublicCard(design: String) {
            try {
                cardRepository?.savePublic(design)
            } finally {
                cardWrites.update { it + 1 }
            }
        }

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

    private class CardStore(var attributes: List<ProfileAttribute> = emptyList()) : CardAttributeStore {
        val writes = mutableListOf<JsonObject>()
        var saveCalls = 0
        var failWith: Exception? = null
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun load() = attributes
        override suspend fun save(data: JsonObject, visibility: ProfileVisibility, id: Uuid?, versionTag: Uuid?, priority: Int) {
            saveCalls++
            gate?.await()
            failWith?.let { throw it }
            writes += data
        }
    }

    private fun cardAttribute(design: String) = ProfileAttribute(
        id = Uuid.random(),
        type = ProfileAttributeTypes.PROFILE_CARD,
        versionTag = Uuid.random(),
        visibility = ProfileVisibility.ANONYMOUS,
        data = JsonObject(mapOf("design" to JsonPrimitive(design))),
    )

    @Test
    fun aStoredPublicCardBeatsTheLocalAndSiteDesign() = runTest(dispatcher) {
        val host = FakeHost()
        val vm = viewModel(host, FakeSource(profile + cardAttribute(CardDesign.DOSSIER)))

        assertEquals(CardDesign.DOSSIER, vm.uiState.value.savedDesign)
        assertEquals(CardDesign.DOSSIER, host.rendered.single().design)
    }

    @Test
    fun anUnknownStoredDesignFallsBackToTheSiteDesign() = runTest(dispatcher) {
        val vm = viewModel(FakeHost(), FakeSource(profile + cardAttribute("hologram")))

        assertEquals(CardDesign.POSTER, vm.uiState.value.savedDesign)
    }

    private suspend fun CardWireHarness.awaitPuts(n: Int) = withContext(Dispatchers.Default) {
        withTimeout(10.seconds) { while (puts < n) delay(10) }
    }

    private suspend fun FakeSource.awaitCardWrites(n: Int) = withContext(Dispatchers.Default) {
        withTimeout(10.seconds) { cardWrites.first { it >= n } }
    }

    @Test
    fun savingOnASupportingServerWritesTheCardAndPublishesTheHomePageDesign() = runTest(dispatcher) {
        val wire = CardWireHarness()
        val source = FakeSource(profile, cardRepository = wire.cardRepository())
        val vm = viewModel(FakeHost(), source)
        vm.onDesignSelected(CardDesign.COLLAGE)

        val event = async { vm.events.first() }
        vm.onSaveDesign()
        event.await()
        wire.awaitPuts(1)

        val body = wire.putBodies.single().jsonObject
        assertEquals(ProfileAttributeTypes.PROFILE_CARD, body["type"]?.jsonPrimitive?.content)
        assertEquals("anonymous", body["visibility"]?.jsonPrimitive?.content)
        assertEquals(1000, body["priority"]?.jsonPrimitive?.int)
        assertEquals(JsonPrimitive("collage"), body["data"]!!.jsonObject["design"])
        assertEquals(listOf(CardDesign.COLLAGE), source.savedDesigns)
        assertEquals(listOf(CardDesign.COLLAGE), source.publishedDesigns)
    }

    @Test
    fun onAnUnsupportedServerOnlyTheOldPathRunsAndNoErrorShows() = runTest(dispatcher) {
        val wire = CardWireHarness { CardWireHarness.Reply.Problem(400, CardWireHarness.UNKNOWN_CARD_TYPE_400) }
        val source = FakeSource(profile, cardRepository = wire.cardRepository())
        val vm = viewModel(FakeHost(), source)
        vm.onDesignSelected(CardDesign.COLLAGE)

        val event = async { vm.events.first() }
        vm.onSaveDesign()

        assertEquals(ProfileCardEvent.DesignSaved, event.await())
        assertEquals(listOf(CardDesign.COLLAGE), source.publishedDesigns)
        assertFalse(vm.uiState.value.loadFailed)
        assertEquals(CardDesign.COLLAGE, vm.uiState.value.savedDesign)
        source.awaitCardWrites(1)
        assertEquals(1, wire.puts)

        vm.onDesignSelected(CardDesign.POSTER)
        val second = async { vm.events.first() }
        vm.onSaveDesign()
        assertEquals(ProfileCardEvent.DesignSaved, second.await())
        source.awaitCardWrites(2)
        assertEquals(1, wire.puts, "the unsupported answer must be remembered, not retried")
        assertEquals(listOf(CardDesign.COLLAGE, CardDesign.POSTER), source.publishedDesigns)
    }

    @Test
    fun aFailedCardWriteDoesNotRevertTheLocallySavedDesignOnReload() = runTest(dispatcher) {
        val existing = cardAttribute(CardDesign.BOARD)
        val store = CardStore(listOf(existing)).apply { failWith = RuntimeException("offline") }
        val source = FakeSource(profile + existing, cardRepository = CardRepository(store))
        val vm = viewModel(FakeHost(), source)
        vm.onDesignSelected(CardDesign.COLLAGE)

        val event = async { vm.events.first() }
        vm.onSaveDesign()
        assertEquals(ProfileCardEvent.DesignSaved, event.await())
        vm.onRetry()

        assertEquals(CardDesign.COLLAGE, vm.uiState.value.savedDesign)
        store.failWith = null
        vm.onRetry()
        assertEquals(CardDesign.COLLAGE, vm.uiState.value.savedDesign)
        assertEquals(JsonPrimitive("collage"), store.writes.single()["design"])
    }

    @Test
    fun theCardWriteDoesNotBlockTheLocalSave() = runTest(dispatcher) {
        val store = CardStore().apply { gate = CompletableDeferred() }
        val source = FakeSource(profile, cardRepository = CardRepository(store))
        val vm = viewModel(FakeHost(), source)
        vm.onDesignSelected(CardDesign.COLLAGE)

        val event = async { vm.events.first() }
        vm.onSaveDesign()

        assertEquals(ProfileCardEvent.DesignSaved, event.await())
        assertFalse(vm.uiState.value.isSavingDesign)
        assertEquals(CardDesign.COLLAGE, vm.uiState.value.savedDesign)
        assertEquals(listOf(CardDesign.COLLAGE), source.publishedDesigns)
        assertTrue(store.writes.isEmpty())
        store.gate!!.complete(Unit)
        assertEquals(1, store.writes.size)
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

    private fun cardAttribute(card: ProfileCard, visibility: ProfileVisibility, circleId: String? = null) = ProfileAttribute(
        id = Uuid.random(),
        type = ProfileAttributeTypes.PROFILE_CARD,
        versionTag = Uuid.random(),
        visibility = visibility,
        data = card.toData(),
        acl = AccessControlList(
            requiredSecurityGroup = visibility.wireValue,
            circleIdList = circleId?.let { listOf(it) },
        ),
        priority = card.priority,
    )

    private val publicCardAttribute = cardAttribute(
        ProfileCard(Uuid.NIL, Uuid.NIL, CardAudience.Public, CardDesign.BOARD),
        ProfileVisibility.ANONYMOUS,
    )

    private fun circleCardAttribute(id: String, label: String, design: String, priority: Int, overrides: JsonObject = JsonObject(emptyMap())) =
        cardAttribute(
            ProfileCard(Uuid.NIL, Uuid.NIL, CardAudience.Circle(id, label), design, overrides, priority),
            ProfileVisibility.CONNECTED,
            id,
        )

    @Test
    fun aSinglePublicCardShowsPublicAndIsNotSwitchable() = runTest(dispatcher) {
        val host = FakeHost()
        val vm = viewModel(host, FakeSource(profile + publicCardAttribute))

        val state = vm.uiState.value
        assertEquals(CardAudience.Public, state.selectedAudience)
        assertEquals(1, state.cards.size)
        assertFalse(state.canSwitchCard)
        assertEquals("public", host.rendered.last().audience?.kind)
        assertNull(host.rendered.last().audience?.label)
    }

    @Test
    fun noStoredCardStillShowsPublic() = runTest(dispatcher) {
        val vm = viewModel(FakeHost(), FakeSource(profile))

        assertEquals(listOf<CardAudience>(CardAudience.Public), vm.uiState.value.cards.map { it.audience })
        assertFalse(vm.uiState.value.canSwitchCard)
    }

    @Test
    fun switchingCardChangesAudienceDesignAndOverrides() = runTest(dispatcher) {
        val host = FakeHost()
        val friends = CardAudience.Circle("c1", "Friends")
        val overrides = JsonObject(mapOf("accent" to JsonPrimitive("#ff0000")))
        val vm = viewModel(
            host,
            FakeSource(profile + publicCardAttribute + circleCardAttribute("c1", "Friends", CardDesign.DOSSIER, 0, overrides)),
        )
        assertTrue(vm.uiState.value.canSwitchCard)
        assertEquals(CardDesign.BOARD, vm.uiState.value.design)
        assertTrue(vm.uiState.value.overrides.isEmpty())

        vm.onCardSelected(friends)

        assertEquals(friends, vm.uiState.value.selectedAudience)
        assertEquals(CardDesign.DOSSIER, vm.uiState.value.design)
        assertEquals(overrides, vm.uiState.value.overrides)
        assertEquals(CardDesign.BOARD, vm.uiState.value.savedDesign)
        val payload = host.rendered.last()
        assertEquals(CardDesign.DOSSIER, payload.design)
        assertEquals("circle", payload.audience?.kind)
        assertEquals("Friends", payload.audience?.label)
        assertTrue(payload.toJson().contains(""""audience":{"kind":"circle","label":"Friends"}"""))

        vm.onCardSelected(CardAudience.Public)

        assertEquals(CardDesign.BOARD, vm.uiState.value.design)
        assertTrue(vm.uiState.value.overrides.isEmpty())
        assertEquals("public", host.rendered.last().audience?.kind)
    }

    @Test
    fun selectingAnUnknownAudienceIsIgnored() = runTest(dispatcher) {
        val host = FakeHost()
        val vm = viewModel(host, FakeSource(profile + publicCardAttribute))
        val renders = host.rendered.size

        vm.onCardSelected(CardAudience.Circle("nope", "Nope"))

        assertEquals(CardAudience.Public, vm.uiState.value.selectedAudience)
        assertEquals(renders, host.rendered.size)
    }

    private val friends = CardAudience.Circle("c1", "Friends")

    @Test
    fun circleCardsListAfterPublicInPriorityOrderWhateverTheAttributeOrder() = runTest(dispatcher) {
        val vm = viewModel(
            FakeHost(),
            FakeSource(
                profile + circleCardAttribute("c3", "Third", CardDesign.POSTER, 2) + publicCardAttribute +
                    circleCardAttribute("c1", "First", CardDesign.DOSSIER, 0) + circleCardAttribute("c2", "Second", CardDesign.COLLAGE, 1),
            ),
        )

        assertEquals(
            listOf<CardAudience>(CardAudience.Public, CardAudience.Circle("c1", "First"), CardAudience.Circle("c2", "Second"), CardAudience.Circle("c3", "Third")),
            vm.uiState.value.cards.map { it.audience },
        )
    }

    @Test
    fun aCircleCardWithAnUnknownDesignIsLeftOut() = runTest(dispatcher) {
        val vm = viewModel(
            FakeHost(),
            FakeSource(profile + publicCardAttribute + circleCardAttribute("c1", "Friends", "hologram", 0) + circleCardAttribute("c2", "Family", CardDesign.POSTER, 1)),
        )

        assertEquals(listOf<CardAudience>(CardAudience.Public, CardAudience.Circle("c2", "Family")), vm.uiState.value.cards.map { it.audience })
    }

    @Test
    fun aReloadKeepsTheSelectedCircleCardWhileItStillExists() = runTest(dispatcher) {
        val host = FakeHost()
        val source = FakeSource(profile + publicCardAttribute + circleCardAttribute("c1", "Friends", CardDesign.DOSSIER, 0))
        val vm = viewModel(host, source)
        vm.onCardSelected(friends)

        vm.onScreenShown()
        advanceUntilIdle()

        assertEquals(friends, vm.uiState.value.selectedAudience)
        assertEquals(CardDesign.DOSSIER, vm.uiState.value.design)
        assertEquals("circle", host.rendered.last().audience?.kind)
    }

    @Test
    fun aReloadAfterTheSelectedCircleCardVanishedFallsBackToPublic() = runTest(dispatcher) {
        val host = FakeHost()
        val source = FakeSource(profile + publicCardAttribute + circleCardAttribute("c1", "Friends", CardDesign.DOSSIER, 0))
        val vm = viewModel(host, source)
        vm.onCardSelected(friends)
        assertEquals("circle", host.rendered.last().audience?.kind)

        source.attributes = profile + publicCardAttribute
        vm.onScreenShown()
        advanceUntilIdle()

        assertEquals(CardAudience.Public, vm.uiState.value.selectedAudience)
        assertEquals(CardDesign.BOARD, vm.uiState.value.design)
        assertEquals(CardDesign.BOARD, host.rendered.last().design)
        assertEquals("public", host.rendered.last().audience?.kind)
    }

    @Test
    fun editingWithACircleCardSelectedNeverWritesThePublicCard() = runTest(dispatcher) {
        val host = FakeHost()
        val source = FakeSource(profile + publicCardAttribute + circleCardAttribute("c1", "Friends", CardDesign.DOSSIER, 0))
        val vm = viewModel(host, source)
        vm.onCardSelected(friends)

        vm.onDesignSelected(CardDesign.POSTER)
        assertNull(vm.uiState.value.previewDesign)
        assertFalse(vm.uiState.value.canSaveDesign)
        vm.onSaveDesign()
        advanceUntilIdle()

        assertTrue(source.savedDesigns.isEmpty())
        assertTrue(source.publishedDesigns.isEmpty())
        assertEquals(0, source.cardWrites.value)
        assertEquals(CardDesign.BOARD, vm.uiState.value.savedDesign)
        assertEquals(CardDesign.DOSSIER, vm.uiState.value.design)
    }

    @Test
    fun enteringTheEditorSwitchesToThePublicCardSoASaveWritesIt() = runTest(dispatcher) {
        val host = FakeHost()
        val source = FakeSource(profile + publicCardAttribute + circleCardAttribute("c1", "Friends", CardDesign.DOSSIER, 0))
        val vm = viewModel(host, source)
        vm.onCardSelected(friends)

        vm.onEditClicked()
        assertEquals(CardAudience.Public, vm.uiState.value.selectedAudience)
        assertEquals(CardDesign.BOARD, vm.uiState.value.design)
        vm.onDesignSelected(CardDesign.POSTER)
        vm.onSaveDesign()
        advanceUntilIdle()

        assertEquals(listOf(CardDesign.POSTER), source.savedDesigns)
        assertEquals(listOf(CardDesign.POSTER), source.publishedDesigns)
    }

    @Test
    fun exportingWithACircleCardSelectedSharesThePublicRenderAndRestoresTheSelection() = runTest(dispatcher) {
        val host = FakeHost()
        val source = FakeSource(profile + publicCardAttribute + circleCardAttribute("c1", "Friends", CardDesign.DOSSIER, 0))
        val vm = viewModel(host, source)
        host.send(CardEvent.Ready(layout = CardDesign.BOARD, ms = 1))
        vm.onCardSelected(friends)
        host.send(CardEvent.Ready(layout = CardDesign.DOSSIER, ms = 1))
        assertEquals("circle", host.rendered.last().audience?.kind)

        val atExport = mutableListOf<CardPayload>()
        host.onRender = { host.send(CardEvent.Ready(layout = it.design, ms = 1)) }
        host.onExport = {
            atExport += host.rendered.last()
            host.send(CardEvent.Png(pngBase64(), 4, 6))
        }
        val event = async { vm.events.first() }
        vm.onShareClicked()
        advanceUntilIdle()

        assertIs<ProfileCardEvent.ShareImage>(event.await())
        val exported = atExport.single()
        assertEquals(CardDesign.BOARD, exported.design)
        assertEquals("public", exported.audience?.kind)
        assertEquals("circle", host.rendered.last().audience?.kind)
        assertEquals(CardDesign.DOSSIER, host.rendered.last().design)
        assertEquals(friends, vm.uiState.value.selectedAudience)
    }

    @Test
    fun pickingAnotherCardMidExportNeverRendersItIntoTheSharedImage() = runTest(dispatcher) {
        val host = FakeHost()
        val source = FakeSource(
            profile + publicCardAttribute + circleCardAttribute("c1", "Friends", CardDesign.DOSSIER, 0) +
                circleCardAttribute("c2", "Family", CardDesign.COLLAGE, 1),
        )
        val vm = viewModel(host, source)
        host.send(CardEvent.Ready(layout = CardDesign.BOARD, ms = 1))
        vm.onCardSelected(friends)
        host.send(CardEvent.Ready(layout = CardDesign.DOSSIER, ms = 1))

        val atExport = mutableListOf<CardPayload>()
        host.onExport = {
            atExport += host.rendered.last()
            host.send(CardEvent.Png(pngBase64(), 4, 6))
        }
        val renderedBefore = host.rendered.size
        val event = async { vm.events.first() }
        vm.onShareClicked()
        assertTrue(vm.uiState.value.isExporting)

        vm.onCardSelected(CardAudience.Circle("c2", "Family"))
        assertEquals(friends, vm.uiState.value.selectedAudience)

        host.send(CardEvent.Ready(layout = CardDesign.BOARD, ms = 1))
        advanceUntilIdle()

        assertIs<ProfileCardEvent.ShareImage>(event.await())
        assertEquals("public", atExport.single().audience?.kind)
        val duringExport = host.rendered.drop(renderedBefore)
        assertEquals("public", duringExport.first().audience?.kind)
        assertEquals(listOf("public", "circle"), duringExport.map { it.audience?.kind })
        assertEquals(CardDesign.DOSSIER, duringExport.last().design)
        assertEquals(friends, vm.uiState.value.selectedAudience)
    }

    @Test
    fun aPostsLoadLandingMidExportNeverRendersTheCircleCardIntoTheSharedImage() = runTest(dispatcher) {
        val host = FakeHost()
        val gate = CompletableDeferred<Unit>()
        var loads = 0
        val source = FakeSource(
            profile + publicCardAttribute + circleCardAttribute("c1", "Friends", CardDesign.DOSSIER, 0),
            posts = {
                if (++loads == 1) gate.await()
                listOf(cardPost("p1"))
            },
        )
        val vm = viewModel(host, source)
        host.send(CardEvent.Ready(layout = CardDesign.BOARD, ms = 1))
        vm.onCardSelected(friends)
        host.send(CardEvent.Ready(layout = CardDesign.DOSSIER, ms = 1))

        val atExport = mutableListOf<CardPayload>()
        host.onExport = {
            atExport += host.rendered.last()
            host.send(CardEvent.Png(pngBase64(), 4, 6))
        }
        val renderedBefore = host.rendered.size
        val event = async { vm.events.first() }
        vm.onShareClicked()
        assertTrue(vm.uiState.value.isExporting)

        gate.complete(Unit)
        host.send(CardEvent.Ready(layout = CardDesign.BOARD, ms = 1))
        advanceUntilIdle()

        assertIs<ProfileCardEvent.ShareImage>(event.await())
        assertEquals("public", atExport.single().audience?.kind)
        assertEquals(listOf("public", "circle"), host.rendered.drop(renderedBefore).map { it.audience?.kind })
        assertEquals(listOf("p1"), host.rendered.last().data.posts.map { it.id })
        assertEquals(friends, vm.uiState.value.selectedAudience)
    }

    @Test
    fun editTappedMidExportEndsOnThePublicCardSoTheEditorIsUsable() = runTest(dispatcher) {
        val host = FakeHost()
        val source = FakeSource(profile + publicCardAttribute + circleCardAttribute("c1", "Friends", CardDesign.DOSSIER, 0))
        val vm = viewModel(host, source)
        host.send(CardEvent.Ready(layout = CardDesign.BOARD, ms = 1))
        vm.onCardSelected(friends)
        host.send(CardEvent.Ready(layout = CardDesign.DOSSIER, ms = 1))

        val atExport = mutableListOf<CardPayload>()
        host.onExport = {
            atExport += host.rendered.last()
            host.send(CardEvent.Png(pngBase64(), 4, 6))
        }
        val event = async { vm.events.first() }
        vm.onShareClicked()
        vm.onEditClicked()
        host.send(CardEvent.Ready(layout = CardDesign.BOARD, ms = 1))
        advanceUntilIdle()

        assertIs<ProfileCardEvent.ShareImage>(event.await())
        assertEquals("public", atExport.single().audience?.kind)
        assertEquals(CardAudience.Public, vm.uiState.value.selectedAudience)
        assertEquals("public", host.rendered.last().audience?.kind)

        vm.onDesignSelected(CardDesign.DOSSIER)
        assertEquals(CardDesign.DOSSIER, vm.uiState.value.previewDesign)
    }
}
