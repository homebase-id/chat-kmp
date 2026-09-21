package id.homebase.core.ui.screens.card

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.OwnerSessionRepository
import id.homebase.api.client.contacts.ContactInfoGateway
import id.homebase.api.client.identity.PublicIdentityRepository
import id.homebase.api.client.profile.ProfileCard
import id.homebase.api.client.profile.ProfileRepository
import id.homebase.api.client.profile.ProfileVisibility
import id.homebase.api.common.OdinId
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.lib.image.ImageFormatDetector
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.image.HomebaseImageLoader
import id.homebase.core.settings.DeveloperPreferences
import id.homebase.core.ui.screens.profile.LoadedProfileAttributes
import id.homebase.core.ui.screens.profile.ProfileEditUiState
import id.homebase.core.ui.screens.profile.photoImageData
import id.homebase.core.ui.screens.profile.withLoaded
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "ProfileCard"

private val EXPORT_TIMEOUT = 30.seconds

data class ProfileCardUiState(
    val tier: ProfileVisibility = ProfileVisibility.ANONYMOUS,
    val design: String = CardDesign.BOARD,
    val reviewEnabled: Boolean = false,
    val loadFailed: Boolean = false,
    val isCardReady: Boolean = false,
    val cardFailed: Boolean = false,
    val isExporting: Boolean = false,
) {
    val canShare: Boolean get() = isCardReady && !isExporting
}

sealed interface ProfileCardEvent {
    data class ShareImage(val path: String, val fileName: String) : ProfileCardEvent
    data class OpenLink(val url: String) : ProfileCardEvent
    data object CardFailed : ProfileCardEvent
    data object ShareFailed : ProfileCardEvent
}

interface ProfileCardSource {
    val reviewEnabled: Boolean
    suspend fun odinId(): OdinId
    suspend fun profile(): ProfileEditUiState
    suspend fun publicProfile(odinId: OdinId): ProfileCard?
    suspend fun siteDefaults(odinId: OdinId): CardSiteDefaults
    suspend fun imageSrc(image: HomebaseImageData): String?
    suspend fun writeShareImage(png: ByteArray): String
}

class DefaultProfileCardSource(
    private val ownerSessionRepository: OwnerSessionRepository,
    private val profileRepository: ProfileRepository,
    private val contactInfo: ContactInfoGateway,
    private val publicIdentityRepository: PublicIdentityRepository,
    private val imageLoader: HomebaseImageLoader,
    private val fileOperations: FileOperationsProvider,
    private val developerPreferences: DeveloperPreferences,
) : ProfileCardSource {
    override val reviewEnabled: Boolean get() = developerPreferences.connectionReviewEnabled.value

    override suspend fun odinId(): OdinId = ownerSessionRepository.user.filterNotNull().first().odinId

    override suspend fun profile(): ProfileEditUiState =
        ProfileEditUiState(reviewEnabled = reviewEnabled)
            .withLoaded(LoadedProfileAttributes.from(profileRepository.loadAttributes()))

    override suspend fun publicProfile(odinId: OdinId): ProfileCard? = contactInfo.profileCard(odinId)

    override suspend fun siteDefaults(odinId: OdinId): CardSiteDefaults =
        publicIdentityRepository.loadCardSiteDefaults(odinId)

    override suspend fun imageSrc(image: HomebaseImageData): String? = loadCardImageSrc(image, imageLoader)

    override suspend fun writeShareImage(png: ByteArray): String =
        fileOperations.writeBytesToShareOutboundFile(png, ".png")
}

private class CardContent(
    val odinId: OdinId,
    val profile: ProfileEditUiState,
    val publicProfile: ProfileCard?,
    val siteDefaults: CardSiteDefaults,
    val photos: Map<ProfileVisibility, HomebaseImageData?>,
)

private data class ImageKey(val driveId: Uuid, val fileId: Uuid, val payloadKey: String, val lastModified: Long?)

/**
 * Owns the pre-warmed [host] for the part of the graph that can open the card (Settings and
 * ProfileEdit), so the card screen only attaches it; [onCleared] disposes it. The host is built by
 * [startHost], not here, so the owning screen decides when its cost lands.
 */
class ProfileCardViewModel(
    private val source: ProfileCardSource,
    private val hostFactory: () -> CardHost,
) : ViewModel() {

    private val _host = MutableStateFlow<CardHost?>(null)
    val host: StateFlow<CardHost?> = _host.asStateFlow()

    private val _uiState = MutableStateFlow(ProfileCardUiState(reviewEnabled = source.reviewEnabled))
    val uiState: StateFlow<ProfileCardUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ProfileCardEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<ProfileCardEvent> = _events.asSharedFlow()

    private var content: CardContent? = null
    private var siteDefaults: CardSiteDefaults? = null
    private var designPicked = false
    private var lastRendered: CardPayload? = null
    private val imageSrcs = mutableMapOf<ImageKey, Deferred<String?>>()
    private val failedImages = mutableSetOf<ImageKey>()
    private var loadJob: Job? = null
    private var renderJob: Job? = null

    init {
        load()
    }

    fun startHost() {
        if (_host.value != null) return
        val host = hostFactory()
        _host.value = host
        observeHost(host)
        lastRendered?.let(host::render)
    }

    fun onTierSelected(tier: ProfileVisibility) {
        if (tier == _uiState.value.tier) return
        _uiState.update { it.copy(tier = tier) }
        render()
    }

    fun onDesignSelected(design: String) {
        designPicked = true
        if (design == _uiState.value.design) return
        _uiState.update { it.copy(design = design) }
        render()
    }

    /** Picks up profile edits made since the pre-warm; the warm card shows until they land. */
    fun onScreenShown() {
        startHost()
        failedImages.forEach(imageSrcs::remove)
        failedImages.clear()
        load()
    }

    fun onRetry() = load()

    fun onShareClicked() {
        val host = _host.value
        if (!_uiState.value.canShare || host == null) return
        _uiState.update { it.copy(isExporting = true) }
        viewModelScope.launch {
            try {
                val result = withTimeoutOrNull(EXPORT_TIMEOUT) {
                    host.events
                        .onSubscription { host.exportPng() }
                        .first { it is CardEvent.Png || it is CardEvent.Error }
                }
                when (result) {
                    is CardEvent.Png -> share(result)
                    null -> {
                        Logger.w(tag = TAG) { "card export timed out after $EXPORT_TIMEOUT" }
                        _events.tryEmit(ProfileCardEvent.ShareFailed)
                    }
                    // observeHost already reported it.
                    else -> Unit
                }
            } finally {
                _uiState.update { it.copy(isExporting = false) }
            }
        }
    }

    override fun onCleared() {
        _host.value?.dispose()
    }

    private fun observeHost(host: CardHost) {
        viewModelScope.launch {
            host.events.collect { event ->
                when (event) {
                    is CardEvent.Ready -> _uiState.update { it.copy(isCardReady = true, cardFailed = false) }
                    is CardEvent.Link -> openLink(event.href)
                    is CardEvent.Error -> onCardError(event.message)
                    CardEvent.Loaded, is CardEvent.Png -> Unit
                }
            }
        }
        viewModelScope.launch {
            host.isLoaded.collect { loaded ->
                if (!loaded) _uiState.update { it.copy(isCardReady = false) }
            }
        }
    }

    private fun onCardError(message: String) {
        val state = _uiState.value
        Logger.w(tag = TAG) { "card error design=${state.design} tier=${state.tier}: $message" }
        _uiState.update { if (it.isCardReady) it else it.copy(cardFailed = true) }
        _events.tryEmit(ProfileCardEvent.CardFailed)
    }

    private fun openLink(href: String) {
        if (isWebUrl(href)) {
            _events.tryEmit(ProfileCardEvent.OpenLink(href))
        } else {
            Logger.w(tag = TAG) { "ignored non-web card link $href" }
        }
    }

    private fun load() {
        if (loadJob?.isActive == true) return
        _uiState.update { it.copy(loadFailed = false) }
        loadJob = viewModelScope.launch {
            val odinId = source.odinId()
            coroutineScope {
                val defaults = async {
                    siteDefaults ?: attempt("site defaults") { source.siteDefaults(odinId) }?.also { siteDefaults = it }
                }
                val publicProfile = async { attempt("public profile") { source.publicProfile(odinId) } }
                val profile = attempt("profile attributes") { source.profile() }
                if (profile == null) {
                    _uiState.update { it.copy(loadFailed = content == null) }
                } else {
                    onLoaded(odinId, profile, publicProfile.await(), defaults.await() ?: CardSiteDefaults())
                }
            }
        }
    }

    private fun onLoaded(
        odinId: OdinId,
        profile: ProfileEditUiState,
        publicProfile: ProfileCard?,
        defaults: CardSiteDefaults,
    ) {
        val photos = listOf(ProfileVisibility.ANONYMOUS, ProfileVisibility.CONNECTED)
            .associateWith { profile.visiblePhoto(it)?.photoImageData() }
        content = CardContent(odinId, profile, publicProfile, defaults, photos)
        // Both tiers up front, so switching tier never waits on an encode.
        photos.values.filterNotNull().forEach(::imageSrcAsync)
        _uiState.update {
            it.copy(loadFailed = false, design = if (designPicked) it.design else defaults.design)
        }
        render()
    }

    private fun render() {
        val content = content ?: return
        val state = _uiState.value
        renderJob?.cancel()
        renderJob = viewModelScope.launch {
            val payload = buildCardPayload(
                odinId = content.odinId.domainName,
                state = content.profile,
                tier = state.tier,
                design = state.design,
                publicProfile = content.publicProfile,
                photoSrc = content.photos[state.tier]?.let { imageSrcAsync(it).await() },
                headerSrc = content.siteDefaults.header?.let { imageSrcAsync(it).await() },
                tagLine = content.siteDefaults.tagLine,
            )
            if (payload != lastRendered) {
                lastRendered = payload
                _host.value?.render(payload)
            }
        }
    }

    // Once per image for the ViewModel's life, so a design or tier switch never re-encodes; failures retry on the next show.
    private fun imageSrcAsync(image: HomebaseImageData): Deferred<String?> {
        val key = ImageKey(image.driveId, image.fileId, image.payloadKey, image.lastModified)
        return imageSrcs.getOrPut(key) {
            viewModelScope.async {
                attempt("card image ${image.fileId}") { source.imageSrc(image) }
                    .also { if (it == null) failedImages += key }
            }
        }
    }

    private suspend fun share(png: CardEvent.Png) {
        val bytes = withContext(Dispatchers.Default) { decodeCardPng(png.base64) }
        if (bytes == null) {
            Logger.w(tag = TAG) { "card export is not a PNG: ${png.base64.length} base64 chars, ${png.width}x${png.height}" }
            _events.tryEmit(ProfileCardEvent.ShareFailed)
            return
        }
        val path = attempt("writing the card image") { source.writeShareImage(bytes) }
        val fileName = content?.odinId?.domainName?.let { "$it-card.png" } ?: "card.png"
        _events.tryEmit(if (path == null) ProfileCardEvent.ShareFailed else ProfileCardEvent.ShareImage(path, fileName))
    }

    private suspend fun <T> attempt(what: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.w(tag = TAG, throwable = e) { "$what failed" }
        null
    }
}

@OptIn(ExperimentalEncodingApi::class)
internal fun decodeCardPng(base64: String): ByteArray? {
    val bytes = try {
        Base64.decode(base64)
    } catch (e: IllegalArgumentException) {
        return null
    }
    return bytes.takeIf { ImageFormatDetector.detectFormat(it) == "image/png" }
}
