package id.homebase.core.ui.screens.card

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.profile.ProfileAttribute
import id.homebase.api.client.profile.ProfileAttributeTypes
import id.homebase.api.client.profile.ProfileCard
import id.homebase.api.client.profile.ProfileVisibility
import id.homebase.api.common.OdinId
import id.homebase.api.image.ArgbImage
import id.homebase.api.image.ImageUtils
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.ui.screens.profile.ProfileEditUiState
import id.homebase.core.ui.screens.profile.ProfileField
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
        override fun dispose() {
            disposed = true
        }

        fun send(event: CardEvent) = check(emitted.tryEmit(event))
    }

    private class FakeSource(
        private val profile: ProfileEditUiState,
        private val defaults: suspend () -> CardSiteDefaults = { CardSiteDefaults(design = CardDesign.POSTER) },
    ) : ProfileCardSource {
        override val reviewEnabled = false
        val imageRequests = mutableListOf<String>()
        val written = mutableListOf<ByteArray>()

        override suspend fun odinId() = OdinId("frodo.dotyou.cloud")
        override suspend fun profile() = profile
        override suspend fun publicProfile(odinId: OdinId): ProfileCard? = null
        override suspend fun siteDefaults(odinId: OdinId) = defaults()

        override suspend fun imageSrc(image: HomebaseImageData): String {
            imageRequests += image.payloadKey
            return "data:image/jpeg;base64,${image.payloadKey}"
        }

        override suspend fun writeShareImage(png: ByteArray): String {
            written += png
            return "/cache/share_outbound/share_1.png"
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

    private val profile = ProfileEditUiState(
        isLoading = false,
        anonymousValues = mapOf(ProfileField.GIVEN_NAME to "Frodo"),
        connectedValues = mapOf(ProfileField.GIVEN_NAME to "Mr. Frodo"),
        anonymousPhoto = photo(ProfileVisibility.ANONYMOUS, "pub_photo"),
        connectedPhoto = photo(ProfileVisibility.CONNECTED, "vet_photo"),
    )

    private fun viewModel(host: CardHost, source: ProfileCardSource) =
        ProfileCardViewModel(source) { host }.also { it.startHost() }

    private fun pngBase64(): String = Base64.encode(
        ImageUtils.encodeArgbToPng(ArgbImage(IntArray(4 * 6) { 0xFF3366CC.toInt() }, 4, 6)),
    )

    @Test
    fun rendersThePublicCardInTheSiteDesignAsSoonAsDataIsReady() = runTest(dispatcher) {
        val host = FakeHost()
        viewModel(host, FakeSource(profile))

        val payload = host.rendered.single()
        assertEquals(CardDesign.POSTER, payload.design)
        assertEquals("Frodo", payload.data.firstName)
        assertEquals("data:image/jpeg;base64,pub_photo", payload.data.photo?.src)
    }

    @Test
    fun theHostIsBuiltOnceWhenStartedAndGetsTheCardThatWasAlreadyBuilt() = runTest(dispatcher) {
        val host = FakeHost()
        var built = 0
        val vm = ProfileCardViewModel(FakeSource(profile)) { built++; host }

        assertEquals(0, built)
        assertNull(vm.host.value)

        vm.startHost()
        vm.startHost()

        assertEquals(1, built)
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
    fun tierSwitchRerendersOnTheSameHostWithoutReencodingEitherPhoto() = runTest(dispatcher) {
        val host = FakeHost()
        val source = FakeSource(profile)
        val vm = viewModel(host, source)

        vm.onTierSelected(ProfileVisibility.CONNECTED)
        vm.onTierSelected(ProfileVisibility.ANONYMOUS)

        assertEquals(listOf("Frodo", "Mr. Frodo", "Frodo"), host.rendered.map { it.data.firstName })
        assertEquals("data:image/jpeg;base64,vet_photo", host.rendered[1].data.photo?.src)
        assertEquals(listOf("pub_photo", "vet_photo"), source.imageRequests.sorted())
        assertEquals(ProfileVisibility.ANONYMOUS, vm.uiState.value.tier)
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

        host.send(CardEvent.Error("files/card/card.html is not bundled"))

        assertEquals(listOf<ProfileCardEvent>(ProfileCardEvent.CardFailed), events)
        assertTrue(vm.uiState.value.cardFailed)

        host.send(CardEvent.Ready(layout = CardDesign.POSTER, ms = 12))
        assertTrue(vm.uiState.value.isCardReady)
        assertFalse(vm.uiState.value.cardFailed)
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
    fun decodeCardPngAcceptsOnlyAPng() {
        val png = pngBase64()
        assertContentEquals(Base64.decode(png), assertNotNull(decodeCardPng(png)))
        assertNull(decodeCardPng(""))
        assertNull(decodeCardPng("%%%"))
        assertNull(decodeCardPng(Base64.encode("GIF89a not a png at all".encodeToByteArray())))
    }
}
