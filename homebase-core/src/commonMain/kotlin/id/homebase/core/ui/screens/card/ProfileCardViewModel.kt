package id.homebase.core.ui.screens.card

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.OwnerSessionRepository
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.client.drives.QueryBatchRequest
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.identity.PublicIdentityRepository
import id.homebase.api.client.profile.ProfileAttribute
import id.homebase.api.client.profile.ProfileRepository
import id.homebase.api.client.profile.ProfileVisibility
import id.homebase.api.common.OdinId
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.lib.image.ImageFormatDetector
import id.homebase.api.youauth.MissingPermissionsResult
import id.homebase.api.youauth.PermissionCheckResult
import id.homebase.api.youauth.PermissionExtensionManager
import id.homebase.api.youauth.SecurityContextProvider
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.image.HomebaseImageLoader
import id.homebase.core.settings.DeveloperPreferences
import id.homebase.core.ui.screens.profile.photoImageData
import id.homebase.core.ui.screens.profile.visiblePhoto
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
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
    val cardUnsupported: Boolean = false,
    val isExporting: Boolean = false,
    val channelAccessMissing: Boolean = false,
) {
    val canShare: Boolean get() = isCardReady && !isExporting
    val showChannelAccessNotice: Boolean get() = channelAccessMissing && tier == ProfileVisibility.CONNECTED
}

sealed interface ProfileCardEvent {
    data class ShareImage(val path: String, val fileName: String) : ProfileCardEvent
    data class OpenLink(val url: String) : ProfileCardEvent
    data object CardFailed : ProfileCardEvent
    data object ShareFailed : ProfileCardEvent
}

interface ProfileCardSource {
    val reviewEnabled: Boolean
    /** Emits when the user comes back from granting the app more access in the owner console. */
    val accessGranted: Flow<Unit>
    suspend fun odinId(): OdinId
    suspend fun attributes(): List<ProfileAttribute>
    suspend fun siteDefaults(odinId: OdinId): CardSiteDefaults
    suspend fun posts(odinId: OdinId): CardPostsLoad
    /** The request for the [channels] the app can't decrypt, or null when there are none or it can't tell. */
    suspend fun missingChannelAccess(odinId: OdinId, channels: List<Uuid>): MissingPermissionsResult?
    suspend fun imageSrc(image: HomebaseImageData, maxEdge: Int): String?
    suspend fun writeShareImage(png: ByteArray): String
}

class DefaultProfileCardSource(
    private val ownerSessionRepository: OwnerSessionRepository,
    private val profileRepository: ProfileRepository,
    private val publicIdentityRepository: PublicIdentityRepository,
    private val imageLoader: HomebaseImageLoader,
    private val fileOperations: FileOperationsProvider,
    private val developerPreferences: DeveloperPreferences,
    private val driveQueryProvider: DriveQueryProvider,
    private val driveFileProvider: DriveFileProvider,
    private val securityContextProvider: SecurityContextProvider,
    private val eventBus: EventBus,
) : ProfileCardSource {
    override val reviewEnabled: Boolean get() = developerPreferences.connectionReviewEnabled.value

    // The bus replays its last event, which may be an older return; only one after subscribing counts.
    override val accessGranted: Flow<Unit> = flow {
        emitAll(
            eventBus.events.drop(eventBus.events.replayCache.size)
                .filterIsInstance<BackendEvent.PermissionsExtensionReturned>()
                .map { },
        )
    }

    override suspend fun odinId(): OdinId = ownerSessionRepository.user.filterNotNull().first().odinId

    override suspend fun attributes(): List<ProfileAttribute> = profileRepository.loadAttributes()

    override suspend fun siteDefaults(odinId: OdinId): CardSiteDefaults =
        publicIdentityRepository.loadCardSiteDefaults(odinId)

    override suspend fun posts(odinId: OdinId): CardPostsLoad =
        withContext(Dispatchers.Default) { loadCardPosts(odinId.domainName, postDrives) }

    override suspend fun missingChannelAccess(odinId: OdinId, channels: List<Uuid>): MissingPermissionsResult? {
        if (channels.isEmpty()) return null
        val context = securityContextProvider.getSecurityContext() ?: return null
        val result = PermissionExtensionManager(securityContextProvider, odinId.domainName)
            .getMissingPermissions(channelAccessConfig(channels, context), context)
        return (result as? PermissionCheckResult.Missing)?.details
    }

    override suspend fun imageSrc(image: HomebaseImageData, maxEdge: Int): String? =
        withContext(Dispatchers.Default) { loadCardImageSrc(image, imageLoader, maxEdge) }

    private val postDrives = object : CardPostDrives {
        override suspend fun channelIds(): List<Uuid> =
            driveQueryProvider.getChannelDrives().results.map { it.targetDrive.alias }

        override suspend fun query(channelId: Uuid, request: QueryBatchRequest) =
            driveQueryProvider.queryBatch(channelId, request)

        override suspend fun payloadText(channelId: Uuid, fileId: Uuid, key: String): String? =
            driveFileProvider.getPayloadBytesDecryptedViaResponseHeader(channelId, fileId, key)?.decodeToString()
    }

    override suspend fun writeShareImage(png: ByteArray): String =
        fileOperations.writeBytesToShareOutboundFile(png, ".png")
}

private class CardContent(
    val odinId: OdinId,
    val attributes: List<ProfileAttribute>,
    val siteDefaults: CardSiteDefaults,
    val photos: Map<ProfileVisibility, HomebaseImageData?>,
)

private data class ImageKey(
    val driveId: Uuid,
    val fileId: Uuid,
    val payloadKey: String,
    val lastModified: Long?,
    val maxEdge: Int,
)

/**
 * Owns the pre-warmed [host] for the part of the graph that can open the card (Settings and
 * ProfileEdit), so the card screen only attaches it; [onCleared] disposes it. The host is built by
 * [startHost], not here, so the owning screen decides when its cost lands.
 */
class ProfileCardViewModel(
    private val source: ProfileCardSource,
    private val hostFactory: (odinId: String) -> CardHost,
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
    private var posts: Map<ProfileVisibility, List<CardPost>> = emptyMap()
    private var postsFresh = false
    private var channelAccess: MissingPermissionsResult? = null
    private var shownBefore = false
    private var hostJob: Job? = null
    private var loadJob: Job? = null
    private var postsJob: Job? = null
    private var renderJob: Job? = null

    init {
        load()
        viewModelScope.launch {
            source.accessGranted.collect {
                postsJob?.cancel()
                postsFresh = false
                loadPosts(source.odinId())
            }
        }
    }

    fun startHost() {
        if (hostJob != null) return
        hostJob = viewModelScope.launch {
            val host = hostFactory(source.odinId().domainName)
            _host.value = host
            observeHost(host)
            lastRendered?.let(host::render)
        }
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
        // The pre-warm's posts serve the first showing; a later one picks up newly published posts.
        if (shownBefore) postsFresh = false
        shownBefore = true
        load()
    }

    fun onRetry() = load()

    fun onAllowChannelAccess() {
        val url = channelAccess?.buildExtendPermissionUrl?.invoke() ?: return
        _events.tryEmit(ProfileCardEvent.OpenLink(url))
    }

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
                    is CardEvent.Ready -> _uiState.update {
                        it.copy(isCardReady = true, cardFailed = false, cardUnsupported = false)
                    }
                    is CardEvent.Link -> openLink(event.href)
                    is CardEvent.Error -> onCardError(event)
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

    private fun onCardError(error: CardEvent.Error) {
        val state = _uiState.value
        Logger.w(tag = TAG) { "card error design=${state.design} tier=${state.tier}: ${error.message}" }
        if (error.unsupported) {
            _uiState.update { it.copy(cardUnsupported = true) }
            return
        }
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
                val attributes = attempt("profile attributes") { source.attributes() }
                if (attributes == null) {
                    _uiState.update { it.copy(loadFailed = content == null) }
                } else {
                    onLoaded(odinId, attributes, defaults.await() ?: CardSiteDefaults())
                }
            }
        }
    }

    private fun onLoaded(
        odinId: OdinId,
        attributes: List<ProfileAttribute>,
        defaults: CardSiteDefaults,
    ) {
        val photos = cardTiers.associateWith { attributes.visiblePhoto(it)?.photoImageData() }
        content = CardContent(odinId, attributes, defaults, photos)
        // Both tiers up front, so switching tier never waits on an encode.
        photos.values.filterNotNull().forEach { imageSrcAsync(it, CARD_IMAGE_MAX_EDGE) }
        _uiState.update {
            it.copy(loadFailed = false, design = if (designPicked) it.design else defaults.design)
        }
        loadPosts(odinId)
        render()
    }

    // Both tiers in one load, thumbnails included, so the card never waits on posts and a tier switch never refetches.
    private fun loadPosts(odinId: OdinId) {
        if (postsFresh || postsJob?.isActive == true) return
        postsJob = viewModelScope.launch {
            val loaded = attempt("card posts") { source.posts(odinId) } ?: return@launch
            launch { checkChannelAccess(odinId, loaded.homePageChannels) }
            val withImages = loaded.posts.mapValues { (_, entries) ->
                entries.map { it.post to it.image?.let { image -> imageSrcAsync(image, CARD_POST_IMAGE_MAX_EDGE) } }
            }
            posts = withImages.mapValues { (_, entries) ->
                entries.map { (post, src) -> src?.await()?.let { post.copy(image = CardImage(it)) } ?: post }
            }
            postsFresh = true
            render()
        }
    }

    private suspend fun checkChannelAccess(odinId: OdinId, channels: List<Uuid>) {
        val missing = attempt("channel access check") { source.missingChannelAccess(odinId, channels) }
        channelAccess = missing
        _uiState.update { it.copy(channelAccessMissing = missing != null) }
    }

    private fun render() {
        val content = content ?: return
        val state = _uiState.value
        renderJob?.cancel()
        renderJob = viewModelScope.launch {
            val payload = buildCardPayload(
                odinId = content.odinId.domainName,
                attributes = content.attributes,
                tier = state.tier,
                design = state.design,
                photoSrc = content.photos[state.tier]?.let { imageSrcAsync(it, CARD_IMAGE_MAX_EDGE).await() },
                headerSrc = content.siteDefaults.header?.let { imageSrcAsync(it, CARD_IMAGE_MAX_EDGE).await() },
                tagLine = content.siteDefaults.tagLine,
                posts = posts[state.tier].orEmpty(),
            )
            if (payload != lastRendered) {
                lastRendered = payload
                _host.value?.render(payload)
            }
        }
    }

    // Once per image for the ViewModel's life, so a design or tier switch never re-encodes; failures retry on the next show.
    private fun imageSrcAsync(image: HomebaseImageData, maxEdge: Int): Deferred<String?> {
        val key = ImageKey(image.driveId, image.fileId, image.payloadKey, image.lastModified, maxEdge)
        return imageSrcs.getOrPut(key) {
            viewModelScope.async {
                attempt("card image ${image.fileId}") { source.imageSrc(image, maxEdge) }
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
