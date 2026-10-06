package id.homebase.core.ui.screens.card

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.OwnerSessionRepository
import id.homebase.api.client.connections.CircleWithMembers
import id.homebase.api.client.connections.ConnectionNetworkProvider
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.client.drives.QueryBatchRequest
import id.homebase.api.client.drives.QueryBatchResultOptionsRequest
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.drives.query.FileQueryParams
import id.homebase.api.client.drives.upload.DriveUploadProvider
import id.homebase.api.client.drives.upload.UpdateFileByFileIdRequest
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.identity.PublicIdentityRepository
import id.homebase.api.client.profile.ProfileAttribute
import id.homebase.api.client.profile.ProfileAttributeTypes
import id.homebase.api.client.profile.ProfileProvider
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
import id.homebase.core.ui.screens.contactbook.isOwnedByContactsApp
import id.homebase.core.ui.screens.profile.LoadedProfileAttributes
import id.homebase.core.ui.screens.profile.ProfileAudience
import id.homebase.core.ui.screens.profile.ProfileEditViewModel
import id.homebase.core.ui.screens.profile.audience
import id.homebase.core.ui.screens.profile.photoImageData
import id.homebase.core.ui.screens.profile.saveWithAudience
import id.homebase.core.ui.screens.profile.visiblePhoto
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonObject
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
import kotlinx.coroutines.flow.collectLatest
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
private val PAINT_TIMEOUT = 1.5.seconds
private val INTRO_READY_TIMEOUT = 5.seconds

class CardCover(val design: String, val image: ImageBitmap)

data class ProfileCardUiState(
    val savedDesign: String = CardDesign.BOARD,
    val previewDesign: String? = null,
    val isSavingDesign: Boolean = false,
    val loadFailed: Boolean = false,
    val isCardReady: Boolean = false,
    val cardFailed: Boolean = false,
    val cardUnsupported: Boolean = false,
    val isExporting: Boolean = false,
    val edges: Map<String, CardEvent.Edges> = emptyMap(),
    val isSwitchingDesign: Boolean = false,
    val cards: List<ProfileCard> = emptyList(),
    val selectedAudience: CardAudience = CardAudience.Public,
    val viewing: Boolean = false,
    val previewOverrides: CardOverrides? = null,
    val circleCardsSupported: Boolean = true,
    val hasLocalPublicDesign: Boolean = false,
    val isCardBusy: Boolean = false,
    val designAccessMissing: Boolean = false,
    val isDesignAccessPromptShown: Boolean = false,
    val designAccessDeclined: Boolean = false,
    val attributes: List<ProfileAttribute> = emptyList(),
    val circles: List<CardCircle> = emptyList(),
    val isContentBusy: Boolean = false,
) {
    internal val contentItems: List<CardContentItem> get() = cardContentItems(attributes, circles, selectedAudience)

    val selectedCard: ProfileCard? get() = cards.firstOrNull { it.audience == selectedAudience }
    private val baseDesign: String get() = selectedCard?.takeIf { it.audience is CardAudience.Circle }?.design ?: savedDesign
    val design: String get() = previewDesign ?: baseDesign
    val savedOverrides: CardOverrides get() = selectedCard?.overrides ?: CardOverrides.EMPTY
    val overrides: CardOverrides get() = previewOverrides ?: savedOverrides
    val hasUnsavedChanges: Boolean
        get() = (previewDesign != null && previewDesign != baseDesign) ||
            (previewOverrides != null && previewOverrides != savedOverrides)
    val isCircleReadOnly: Boolean get() = isCircleSelected && !circleCardsSupported
    private val hasStoredCard: Boolean
        get() = selectedCard?.let { !it.isDefault || (it.audience == CardAudience.Public && hasLocalPublicDesign) } ?: false
    val showsReset: Boolean get() = hasStoredCard && !isCircleReadOnly
    val isUnsavedCard: Boolean get() = selectedCard != null && !hasStoredCard
    val canReset: Boolean get() = showsReset && !isExporting && !isCardBusy && !isSavingDesign
    val isCircleSelected: Boolean get() = selectedAudience is CardAudience.Circle
    val cardTopArgb: Int? get() = edges[design]?.topArgb
    val cardBottomArgb: Int? get() = edges[design]?.bottomArgb
    val canShare: Boolean get() = isCardReady && !isExporting
    val canSaveDesign: Boolean get() = hasUnsavedChanges && !isSavingDesign && !isCardBusy && !isCircleReadOnly
    val showsAppOnlyTag: Boolean
        get() = designAccessMissing && designAccessDeclined && !isCircleSelected && !isDesignAccessPromptShown
}

data class CardCircle(val id: String, val name: String)

sealed interface ProfileCardEvent {
    data class ShareImage(val path: String, val fileName: String) : ProfileCardEvent
    data class OpenLink(val url: String) : ProfileCardEvent
    data object CardFailed : ProfileCardEvent
    data object ShareFailed : ProfileCardEvent
    data object DesignSaved : ProfileCardEvent
    data object DesignSaveFailed : ProfileCardEvent
    data object ResetFailed : ProfileCardEvent
    data object CircleCardsUnsupported : ProfileCardEvent
    data object ContentSaveFailed : ProfileCardEvent
}

interface ProfileCardSource {
    /** Emits when the user comes back from granting the app more access in the owner console. */
    val accessGranted: Flow<Unit>
    suspend fun odinId(): OdinId
    suspend fun attributes(): List<ProfileAttribute>
    suspend fun siteDefaults(odinId: OdinId): CardSiteDefaults
    suspend fun posts(odinId: OdinId): List<CardPostEntry>
    suspend fun imageSrc(image: HomebaseImageData, maxEdge: Int): String?
    suspend fun writeShareImage(png: ByteArray): String
    suspend fun savedDesign(): String?
    suspend fun saveDesign(design: String)
    /** Writes the public card attribute; a no-op on a server that doesn't know the type. */
    suspend fun savePublicCard(design: String, overrides: CardOverrides?)
    val supportsCircleCards: Boolean
    suspend fun circles(): List<CardCircle>
    suspend fun saveCircleCard(card: ProfileCard): ProfileCard?
    suspend fun saveProfileAttribute(type: String, data: JsonObject, audience: ProfileAudience, existing: ProfileAttribute?): ProfileAttribute
    suspend fun resetPublicCard()
    suspend fun resetCircleCard(card: ProfileCard)
    suspend fun clearSavedDesign()
    /** The request for writing the design to the home page, or null when the app may or it can't tell. */
    suspend fun missingDesignAccess(odinId: OdinId): MissingPermissionsResult?
    suspend fun publishDesign(design: String): CardDesignPublish
}

class DefaultProfileCardSource(
    private val ownerSessionRepository: OwnerSessionRepository,
    private val profileRepository: ProfileRepository,
    private val publicIdentityRepository: PublicIdentityRepository,
    private val imageLoader: HomebaseImageLoader,
    private val fileOperations: FileOperationsProvider,
    private val driveQueryProvider: DriveQueryProvider,
    private val driveFileProvider: DriveFileProvider,
    private val driveUploadProvider: DriveUploadProvider,
    private val securityContextProvider: SecurityContextProvider,
    private val eventBus: EventBus,
    private val cardPreferences: CardPreferences,
    private val cardRepository: CardRepository,
    private val connectionProvider: ConnectionNetworkProvider,
) : ProfileCardSource {
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

    override suspend fun posts(odinId: OdinId): List<CardPostEntry> =
        withContext(Dispatchers.Default) { loadCardPosts(odinId.domainName, postDrives) }

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

    override suspend fun savedDesign(): String? = cardPreferences.design.value

    override suspend fun saveDesign(design: String) = cardPreferences.setDesign(design)

    override suspend fun savePublicCard(design: String, overrides: CardOverrides?) {
        cardRepository.savePublic(design, overrides)
    }

    override val supportsCircleCards: Boolean get() = cardRepository.supportsCircleCards

    override suspend fun circles(): List<CardCircle> =
        contactsCircles(connectionProvider.getCirclesWithMembers(includeSystemCircle = false))

    override suspend fun saveCircleCard(card: ProfileCard) = cardRepository.saveCircle(card)

    override suspend fun saveProfileAttribute(
        type: String,
        data: JsonObject,
        audience: ProfileAudience,
        existing: ProfileAttribute?,
    ) = profileRepository.saveWithAudience(type, data, audience, existing)

    override suspend fun resetPublicCard() = cardRepository.resetPublic()

    override suspend fun resetCircleCard(card: ProfileCard) = cardRepository.resetCircle(card)

    override suspend fun clearSavedDesign() = cardPreferences.clearDesign()

    override suspend fun missingDesignAccess(odinId: OdinId): MissingPermissionsResult? {
        val context = securityContextProvider.getSecurityContext() ?: return null
        val result = PermissionExtensionManager(securityContextProvider, odinId.domainName)
            .getMissingPermissions(designAccessConfig(context), context)
        return (result as? PermissionCheckResult.Missing)?.details
    }

    override suspend fun publishDesign(design: String): CardDesignPublish = publishCardDesign(design, themeFiles)

    private val themeFiles = object : CardThemeFiles {
        override suspend fun query() = driveQueryProvider.queryBatch(
            driveId = SystemDriveConstants.homePageConfigDrive.alias,
            request = QueryBatchRequest(
                queryParams = FileQueryParams(
                    fileType = listOf(ProfileProvider.PROFILE_ATTRIBUTE_FILE_TYPE),
                    tagsMatchAtLeastOne = listOf(THEME_ATTRIBUTE_TYPE),
                ),
                resultOptionsRequest = QueryBatchResultOptionsRequest(maxRecords = 10, includeMetadataHeader = true),
            ),
        ).searchResults

        override suspend fun update(request: UpdateFileByFileIdRequest) =
            driveUploadProvider.updateFileByFileId(request, onVersionConflict = { null })
    }
}

internal fun contactsCircles(circles: List<CircleWithMembers>): List<CardCircle> =
    circles
        .filter { it.circle.isOwnedByContactsApp() && !it.circle.disabled && it.circle.name.isNotBlank() }
        .map { CardCircle(it.circle.id, it.circle.name) }
        .distinctBy { it.id.lowercase() }
        .sortedBy { it.name.lowercase() }

private data class UnsavedCard(val design: String, val overrides: CardOverrides?)

private data class CardContent(
    val odinId: OdinId,
    val attributes: List<ProfileAttribute>,
    val siteDefaults: CardSiteDefaults,
    val photo: HomebaseImageData?,
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

    private val _uiState = MutableStateFlow(ProfileCardUiState())
    val uiState: StateFlow<ProfileCardUiState> = _uiState.asStateFlow()

    // A still of the live card, which the next opening slides in with while the native view attaches.
    private val _cover = MutableStateFlow<CardCover?>(null)
    val cover: StateFlow<CardCover?> = _cover.asStateFlow()

    // The editor re-renders the card in place; a still of the outgoing design covers it until the new one paints.
    private val _designCover = MutableStateFlow<ImageBitmap?>(null)
    val designCover: StateFlow<ImageBitmap?> = _designCover.asStateFlow()
    private var designSwitchJob: Job? = null

    private val _events = MutableSharedFlow<ProfileCardEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<ProfileCardEvent> = _events.asSharedFlow()

    private var content: CardContent? = null
    private var siteDefaults: CardSiteDefaults? = null
    private val readyCount = MutableStateFlow(0)
    private var coverStale = false
    private var lastRendered: CardPayload? = null
    private var renderDeferredByExport = false
    private val imageSrcs = mutableMapOf<ImageKey, Deferred<String?>>()
    private val failedImages = mutableSetOf<ImageKey>()
    private var posts: List<CardPost> = emptyList()
    private var postsFresh = false
    private var designAccess: MissingPermissionsResult? = null
    private var designAccessWanted = false
    private var designAccessAsked = false
    private var unpublishedDesign: String? = null
    private var unsavedCard: UnsavedCard? = null
    private var cardJob: Job? = null
    private var circles: List<CardCircle> = emptyList()
    private val audiencesBeforePublic = mutableMapOf<Uuid, ProfileAudience>()
    private var publishJob: Job? = null
    private var shownBefore = false
    private var shownAt: TimeMark? = null
    private val tilesLogged = mutableSetOf<String>()
    private val tilePayloads = mutableMapOf<String, CardPayload>()
    private val _introTiles = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    val introTiles: StateFlow<Map<String, ImageBitmap>> = _introTiles.asStateFlow()
    private val _introRevision = MutableStateFlow(0)
    val introRevision: StateFlow<Int> = _introRevision.asStateFlow()
    private var hostJob: Job? = null
    private var loadJob: Job? = null
    private var postsJob: Job? = null
    private var renderJob: Job? = null

    init {
        load()
        viewModelScope.launch { checkDesignAccess() }
        viewModelScope.launch {
            source.accessGranted.collect {
                checkDesignAccess()
                val design = unpublishedDesign
                if (design != null && designAccess == null) publishDesign(design)
                else if (design != null) Logger.i(tag = TAG) { "home page access still missing; card design $design stays unpublished" }
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

    fun onDesignSelected(design: String) {
        if (design == _uiState.value.design) return
        _uiState.update {
            val edited = it.overrides
            it.copy(
                previewDesign = design,
                previewOverrides = if (edited.isEmpty() && it.previewOverrides == null) null else edited.prunedFor(design),
            )
        }
        designSwitchJob?.cancel()
        designSwitchJob = viewModelScope.launch {
            coverOutgoingDesign()
            render()
        }
    }

    fun onOptionSelected(option: CardOption, value: String?) = editOverrides { it.with(option, value) }

    fun onBlockOrderChanged(kinds: List<String>?) = editOverrides { it.withBlockOrder(kinds) }

    private fun editOverrides(edit: (CardOverrides) -> CardOverrides) {
        val state = _uiState.value
        if (state.isSavingDesign) return
        val spec = CardDesignSpecs.of(state.design) ?: return
        val edited = edit(state.overrides).prunedFor(state.design)
        if (edited == state.overrides || spec.options.isEmpty()) return
        _uiState.update { it.copy(previewOverrides = edited) }
        render()
    }

    private suspend fun coverOutgoingDesign() {
        val host = _host.value ?: return
        if (!_uiState.value.isCardReady) return
        _designCover.value = attempt("capturing the outgoing design") { host.snapshot() } ?: return
        _uiState.update { it.copy(isSwitchingDesign = true) }
    }

    fun onCardOpened(audience: CardAudience) {
        val state = _uiState.value
        val card = state.cards.firstOrNull { it.audience.isSameAs(audience) }
        if (state.isExporting || card == null) return
        selectAudience(card.audience)
        _uiState.update { it.copy(viewing = true) }
    }

    fun onIntroReturned() {
        _uiState.update { it.copy(viewing = false) }
    }

    fun onIntroTilePainted(audience: CardAudience) {
        val shown = shownAt ?: return
        if (!tilesLogged.add(audienceKey(audience))) return
        Logger.i(tag = TAG) {
            "card intro first paint: \"${audience.logName()}\" ${shown.elapsedNow().inWholeMilliseconds}ms (${_uiState.value.cards.size} cards)"
        }
    }

    private fun CardAudience.logName() = when (this) {
        CardAudience.Public -> "Public"
        is CardAudience.Circle -> label
    }

    private fun interruptedByViewer() = _uiState.value.viewing || _uiState.value.isExporting

    // The host goes back to the card the viewer shows when done or cancelled.
    suspend fun captureIntroTiles() {
        val host = _host.value ?: return
        host.isLoaded.first { it }
        var touched = false
        try {
            for (card in _uiState.value.cards) {
                if (interruptedByViewer()) return
                val key = audienceKey(card.audience)
                val design = if (card.audience == CardAudience.Public) _uiState.value.savedDesign else card.design
                val payload = payloadFor(design, card.audience) ?: return
                if (tilePayloads[key] == payload && key in _introTiles.value) continue
                val ready = withTimeoutOrNull(INTRO_READY_TIMEOUT) {
                    host.events.onSubscription { touched = true; host.render(payload) }.first { it is CardEvent.Ready }
                }
                if (ready == null) {
                    Logger.w(tag = TAG) { "card intro: no ready reply for ${card.audience.logName()}" }
                    continue
                }
                withTimeoutOrNull(PAINT_TIMEOUT) {
                    host.events.onSubscription { host.requestPaint() }.first { it is CardEvent.Painted }
                }
                if (interruptedByViewer()) return
                val image = attempt("capturing the ${card.audience.logName()} intro tile") { host.snapshot() }
                if (image == null) {
                    Logger.i(tag = TAG) { "card intro: no snapshot for ${card.audience.logName()}, tile stays a placeholder" }
                    return
                }
                // The viewer may have opened during the snapshot, so the image can show its card, not this one.
                if (interruptedByViewer()) return
                tilePayloads[key] = payload
                _introTiles.update { it + (key to image) }
            }
        } finally {
            if (touched) lastRendered?.let(host::render)
        }
    }

    private fun selectAudience(audience: CardAudience) {
        val state = _uiState.value
        if (audience == state.selectedAudience || state.cards.none { it.audience == audience }) return
        designSwitchJob?.cancel()
        _cover.value = null
        _uiState.update { it.copy(selectedAudience = audience, previewDesign = null, previewOverrides = null) }
        designSwitchJob = viewModelScope.launch {
            if (!_uiState.value.isExporting) coverOutgoingDesign()
            render()
        }
    }

    fun onContentToggled(id: Uuid, on: Boolean) {
        val state = _uiState.value
        if (state.isContentBusy) return
        val item = state.contentItems.firstOrNull { it.id == id } ?: return
        val attribute = state.attributes.firstOrNull { it.id == id } ?: return
        val next = item.audience.shownOn(state.selectedAudience, on, audiencesBeforePublic[id]) ?: return
        if (on && state.selectedAudience == CardAudience.Public) audiencesBeforePublic[id] = item.audience
        writeContent(attribute.type, attribute.data, next, attribute)
    }

    fun onContentAudienceChanged(id: Uuid, audience: ProfileAudience) {
        val state = _uiState.value
        if (state.isContentBusy || !audience.isSavableWith(state.circles)) return
        val attribute = state.attributes.firstOrNull { it.id == id } ?: return
        if (audience == attribute.audience(state.circles)) return
        writeContent(attribute.type, attribute.data, audience, attribute)
    }

    fun onContentAdded(type: String, updates: Map<String, String>) {
        val state = _uiState.value
        if (state.isContentBusy) return
        val existing = if (type == ProfileAttributeTypes.LINK) null else LoadedProfileAttributes.from(state.attributes).byType[type]
        val existingAudience = existing?.audience(state.circles)
        val audience = existingAudience?.let { it.shownOn(state.selectedAudience, true, null) ?: it } ?: state.selectedAudience.newItemAudience()
        val edit = ProfileEditViewModel.computeAttributeEdit(existing, type, updates, audience, existingAudience) ?: return
        writeContent(edit.type, edit.data, edit.audience, existing)
    }

    private fun writeContent(type: String, data: JsonObject, audience: ProfileAudience, existing: ProfileAttribute?) {
        _uiState.update { it.copy(isContentBusy = true) }
        viewModelScope.launch {
            val saved = attempt("saving profile content $type") { source.saveProfileAttribute(type, data, audience, existing) }
            if (saved == null) {
                _uiState.update { it.copy(isContentBusy = false) }
                _events.tryEmit(ProfileCardEvent.ContentSaveFailed)
                return@launch
            }
            content = content?.let { c -> c.copy(attributes = c.attributes.filterNot { it.id == saved.id } + saved) }
            _uiState.update { it.copy(isContentBusy = false, attributes = it.attributes.filterNot { a -> a.id == saved.id } + saved) }
            render()
        }
    }

    fun onPreviewDiscarded() {
        if (_uiState.value.previewDesign == null && _uiState.value.previewOverrides == null) return
        _uiState.update { it.copy(previewDesign = null, previewOverrides = null) }
        render()
    }

    fun onSaveDesign() {
        val state = _uiState.value
        if (!state.canSaveDesign) return
        if (state.isCircleSelected) return saveCircleCard(state)
        val design = state.design
        val overrides = state.overrides.takeIf { state.previewOverrides != null }
        val designChanged = design != state.savedDesign
        _uiState.update { it.copy(isSavingDesign = true) }
        viewModelScope.launch {
            val saved = attempt("saving card design $design") { source.saveDesign(design) } != null
            if (saved) writeCard(UnsavedCard(design, overrides).also { unsavedCard = it })
            _uiState.update {
                if (saved) it.copy(
                    isSavingDesign = false,
                    savedDesign = design,
                    hasLocalPublicDesign = true,
                    previewDesign = null,
                    previewOverrides = null,
                    cards = it.cards.map { card ->
                        if (card.audience != CardAudience.Public) card else card.withDesign(design, overrides)
                    },
                )
                else it.copy(isSavingDesign = false)
            }
            if (saved) render()
            if (saved && designChanged) {
                unpublishedDesign = design
                if (designAccess == null) publishDesign(design)
            }
            _events.tryEmit(if (saved) ProfileCardEvent.DesignSaved else ProfileCardEvent.DesignSaveFailed)
        }
    }

    private fun saveCircleCard(state: ProfileCardUiState) {
        val card = state.selectedCard ?: return
        val overrides = state.overrides.takeIf { state.previewOverrides != null }
        val updated = card.withDesign(state.design, overrides)
        _uiState.update { it.copy(isSavingDesign = true) }
        viewModelScope.launch {
            val stored = attempt("saving the ${card.audience} card") { source.saveCircleCard(updated) }
            _uiState.update {
                withCircleSupport(
                    if (stored != null) it.copy(
                        isSavingDesign = false,
                        previewDesign = null,
                        previewOverrides = null,
                        cards = it.cards.map { c -> if (c.audience == updated.audience) stored else c },
                    )
                    else it.copy(isSavingDesign = false),
                )
            }
            if (stored != null) {
                render()
                load()
            }
            val event = when {
                stored != null -> ProfileCardEvent.DesignSaved
                !source.supportsCircleCards -> ProfileCardEvent.CircleCardsUnsupported
                else -> ProfileCardEvent.DesignSaveFailed
            }
            _events.tryEmit(event)
        }
    }

    fun onResetCardConfirmed() {
        val state = _uiState.value
        val card = state.selectedCard ?: return
        if (!state.canReset) return
        _uiState.update { it.copy(isCardBusy = true) }
        viewModelScope.launch {
            val done = attempt("resetting the ${card.audience} card") {
                when (card.audience) {
                    CardAudience.Public -> {
                        cardJob?.cancel()
                        unsavedCard = null
                        source.resetPublicCard()
                        source.clearSavedDesign()
                    }
                    is CardAudience.Circle -> source.resetCircleCard(card)
                }
            } != null
            _uiState.update { it.copy(isCardBusy = false, previewDesign = null, previewOverrides = null) }
            if (done) {
                if (card.audience == CardAudience.Public) {
                    _uiState.update { it.copy(savedDesign = siteDefaults?.design ?: CardDesign.BOARD) }
                }
                load(force = true)
            } else {
                _events.tryEmit(ProfileCardEvent.ResetFailed)
            }
        }
    }

    // Off the save path: offline it would hang the spinner. Until it lands the local design wins on reload, and a reload retries it.
    private fun writeCard(card: UnsavedCard) {
        cardJob?.cancel()
        cardJob = viewModelScope.launch {
            attempt("saving the public card ${card.design}") { source.savePublicCard(card.design, card.overrides) } ?: return@launch
            if (unsavedCard == card) unsavedCard = null
        }
    }

    private fun withCircleSupport(state: ProfileCardUiState) = state.copy(circleCardsSupported = source.supportsCircleCards)

    // The public card follows the home page; a failure here leaves the local save, which this viewer shows, standing.
    private fun publishDesign(design: String) {
        publishJob?.cancel()
        publishJob = viewModelScope.launch {
            val result = attempt("publishing card design $design") { source.publishDesign(design) } ?: return@launch
            unpublishedDesign = null
            if (result == CardDesignPublish.NoTheme) {
                Logger.i(tag = TAG) { "no home page theme to publish card design $design to" }
            }
        }
    }

    private suspend fun checkDesignAccess() {
        designAccess = attempt("design access check") { source.missingDesignAccess(source.odinId()) }
        _uiState.update { it.copy(designAccessMissing = designAccess != null) }
        promptForDesignAccessIfWanted()
    }

    /** The public card's design is written to the home page, which needs a grant the app may not have yet: ask before editing, not mid-save. */
    fun onEditorOpened() {
        designAccessWanted = true
        promptForDesignAccessIfWanted()
    }

    fun onEditorClosed() {
        designAccessWanted = false
    }

    private fun promptForDesignAccessIfWanted() {
        if (!designAccessWanted || designAccessAsked || designAccess == null || _uiState.value.isCircleSelected) return
        designAccessAsked = true
        designAccessWanted = false
        _uiState.update { it.copy(isDesignAccessPromptShown = true) }
    }

    fun onDesignAccessAccepted() {
        _uiState.update { it.copy(isDesignAccessPromptShown = false) }
        designAccess?.let { _events.tryEmit(ProfileCardEvent.OpenLink(it.buildExtendPermissionUrl())) }
    }

    fun onDesignAccessDeclined() = _uiState.update { it.copy(isDesignAccessPromptShown = false, designAccessDeclined = true) }

    fun onPublishRetry() {
        if (designAccess != null) {
            _uiState.update { it.copy(isDesignAccessPromptShown = true) }
            return
        }
        unpublishedDesign?.let(::publishDesign)
    }

    /** Picks up profile edits made since the pre-warm; the warm card shows until they land. */
    fun onScreenShown() {
        shownAt = TimeSource.Monotonic.markNow()
        tilesLogged.clear()
        startHost()
        // A publish that failed with the grant in place has no button of its own; each showing retries it.
        if (designAccess == null && publishJob?.isActive != true) unpublishedDesign?.let(::publishDesign)
        failedImages.forEach(imageSrcs::remove)
        failedImages.clear()
        // The pre-warm's posts serve the first showing; a later one picks up newly published posts.
        if (shownBefore) postsFresh = false
        shownBefore = true
        load()
    }

    fun onRetry() {
        if (_uiState.value.cardFailed) {
            _uiState.update { it.copy(cardFailed = false) }
            lastRendered?.let { payload -> _host.value?.render(payload) }
        }
        load()
    }

    fun onShareClicked() {
        val host = _host.value
        if (!_uiState.value.canShare || host == null) return
        _uiState.update { it.copy(isExporting = true) }
        viewModelScope.launch {
            val publicPayload = if (_uiState.value.isCircleSelected) payloadFor(_uiState.value.savedDesign, CardAudience.Public) else null
            try {
                val result = withTimeoutOrNull(EXPORT_TIMEOUT) {
                    // Sharing always sends the public card, so a selected circle card is swapped out for the export only.
                    if (publicPayload != null) {
                        host.events.onSubscription { host.render(publicPayload) }.first { it is CardEvent.Ready }
                    }
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
                if (publicPayload != null || renderDeferredByExport) lastRendered?.let(host::render)
                renderDeferredByExport = false
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
                    is CardEvent.Ready -> {
                        _uiState.update { it.copy(isCardReady = true, cardFailed = false, cardUnsupported = false) }
                        readyCount.update { it + 1 }
                    }
                    is CardEvent.Edges -> onEdges(event)
                    is CardEvent.Link -> openLink(event.href)
                    is CardEvent.Error -> onCardError(event)
                    CardEvent.Loaded, is CardEvent.Png, CardEvent.Painted -> Unit
                }
            }
        }
        viewModelScope.launch {
            host.isLoaded.collect { loaded ->
                if (!loaded) {
                    _designCover.value = null
                    _uiState.update { it.copy(isCardReady = false, isSwitchingDesign = false) }
                    readyCount.value = 0
                }
            }
        }
    }

    private fun onEdges(edges: CardEvent.Edges) {
        _uiState.update { it.copy(edges = it.edges + (it.design to edges)) }
    }

    // Per attached view: every render the page reports is awaited onto the screen, then its edges are probed.
    suspend fun paintWhileAttached(onPainted: () -> Unit) {
        val host = _host.value ?: return
        coverStale = false
        readyCount.collectLatest { count ->
            if (count == 0) return@collectLatest
            val painted = withTimeoutOrNull(PAINT_TIMEOUT) {
                host.events.onSubscription { host.requestPaint() }.first { it is CardEvent.Painted }
            }
            if (painted == null) Logger.w(tag = TAG) { "no paint reply after $PAINT_TIMEOUT" }
            host.probeEdges()
            coverStale = true
            _uiState.update { it.copy(isSwitchingDesign = false) }
            onPainted()
        }
    }

    // Only a painted, not-yet-captured card is worth a still: anything else would capture the cover itself.
    suspend fun captureCover() {
        val host = _host.value ?: return
        if (!coverStale) return
        coverStale = false
        val state = _uiState.value
        val image = attempt("capturing the card cover") { host.snapshot() }
        if (image == null) {
            coverStale = true
            return
        }
        _cover.value = CardCover(state.design, image)
    }

    private fun onCardError(error: CardEvent.Error) {
        _uiState.update { it.copy(isSwitchingDesign = false) }
        val state = _uiState.value
        Logger.w(tag = TAG) { "card error design=${state.design}: ${error.message}" }
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

    private fun load(force: Boolean = false) {
        if (loadJob?.isActive == true) {
            if (!force) return
            loadJob?.cancel()
        }
        _uiState.update { it.copy(loadFailed = false) }
        loadJob = viewModelScope.launch {
            val odinId = source.odinId()
            loadPosts(odinId)
            coroutineScope {
                val defaults = async {
                    siteDefaults ?: attempt("site defaults") { source.siteDefaults(odinId) }?.also { siteDefaults = it }
                }
                val stored = async { attempt("saved card design") { source.savedDesign() } }
                val circleList = async { attempt("card circles") { source.circles() } }
                val attributes = attempt("profile attributes") { source.attributes() }
                circleList.await()?.let { circles = it }
                if (attributes == null) {
                    _uiState.update { it.copy(loadFailed = content == null) }
                } else {
                    onLoaded(odinId, attributes, defaults.await() ?: CardSiteDefaults(), stored.await())
                }
            }
        }
    }

    private fun onLoaded(
        odinId: OdinId,
        attributes: List<ProfileAttribute>,
        defaults: CardSiteDefaults,
        storedDesign: String?,
    ) {
        val photo = attributes.visiblePhoto(ProfileVisibility.ANONYMOUS)?.photoImageData()
        content = CardContent(odinId, attributes, defaults, photo)
        photo?.let { imageSrcAsync(it, CARD_IMAGE_MAX_EDGE) }
        val stored = attributes.profileCards()
        val storedPublic = stored.publicCard()
        val cardDesign = storedPublic?.design?.takeIf { it in CardDesign.all }
        val pending = unsavedCard
        val saved = pending?.design ?: cardDesign ?: storedDesign ?: defaults.design
        if (pending != null && cardJob?.isActive != true) writeCard(pending)
        val pendingOverrides = pending?.overrides
        val publicCard = (storedPublic ?: ProfileCard(Uuid.NIL, Uuid.NIL, CardAudience.Public, saved))
            .let { card -> if (pendingOverrides != null) card.copy(overrides = pendingOverrides) else card }
        val cards = (listOf(publicCard) + circleCards(stored, publicCard)).sortedCards()
        _uiState.update {
            val selected = cards.firstOrNull { card -> card.audience.isSameAs(it.selectedAudience) }?.audience
            it.copy(
                loadFailed = false,
                savedDesign = saved,
                hasLocalPublicDesign = storedDesign != null || pending != null,
                attributes = attributes,
                circles = circles,
                cards = cards,
                selectedAudience = selected ?: CardAudience.Public,
            ).let(::withCircleSupport)
        }
        render()
    }

    // One card per Contacts circle; a circle never saved, or saved in a design this build doesn't know, shows the public design until its first save.
    private fun circleCards(stored: List<ProfileCard>, publicCard: ProfileCard): List<ProfileCard> {
        val priorities = fixedCirclePriorities(circles)
        return circles.map { circle ->
            val audience = CardAudience.Circle(circle.id, circle.name)
            val priority = priorities.getValue(circle.id)
            val saved = stored.firstOrNull { it.audience.isSameAs(audience) }
            when {
                saved == null -> ProfileCard(Uuid.NIL, Uuid.NIL, audience, publicCard.design, publicCard.overrides, priority)
                saved.design in CardDesign.all -> saved.copy(audience = audience, priority = priority)
                else -> saved.copy(audience = audience, design = publicCard.design, overrides = publicCard.overrides, priority = priority)
            }
        }
    }

    // Thumbnails included, so the card never waits on posts.
    private fun loadPosts(odinId: OdinId) {
        if (postsFresh || postsJob?.isActive == true) return
        postsJob = viewModelScope.launch {
            val loaded = attempt("card posts") { source.posts(odinId) } ?: return@launch
            val withImages = loaded.map { it.post to it.image?.let { image -> imageSrcAsync(image, CARD_POST_IMAGE_MAX_EDGE) } }
            posts = withImages.map { (post, src) -> src?.await()?.let { post.copy(image = CardImage(it)) } ?: post }
            postsFresh = true
            render()
        }
    }

    private fun render() {
        val state = _uiState.value
        _cover.update { cover -> cover?.takeIf { it.design == state.savedDesign } }
        if (content == null) return
        _introRevision.update { it + 1 }
        renderJob?.cancel()
        renderJob = viewModelScope.launch {
            val payload = payloadFor(state.design, state.selectedAudience) ?: return@launch
            if (payload != lastRendered) {
                lastRendered = payload
                if (_uiState.value.isExporting) renderDeferredByExport = true else _host.value?.render(payload)
            }
        }
    }

    private suspend fun payloadFor(design: String, audience: CardAudience): CardPayload? {
        val content = content ?: return null
        val photo = if (audience == CardAudience.Public) content.photo
        else content.attributes.visiblePhoto(audience.aclFilter())?.photoImageData()
        val card = _uiState.value.cards.firstOrNull { it.audience == audience }
        val stored = _uiState.value.previewOverrides.takeIf { audience == _uiState.value.selectedAudience } ?: card?.overrides ?: CardOverrides.EMPTY
        return buildCardPayload(
            odinId = content.odinId.domainName,
            attributes = content.attributes,
            design = design,
            photoSrc = photo?.let { imageSrcAsync(it, CARD_IMAGE_MAX_EDGE).await() },
            headerSrc = content.siteDefaults.header?.let { imageSrcAsync(it, CARD_IMAGE_MAX_EDGE).await() },
            tagLine = content.siteDefaults.tagLine,
            posts = posts,
            audience = audience,
            overrides = card?.withDesign(design, stored)?.overrides ?: stored,
        )
    }

    // Once per image for the ViewModel's life, so a design switch never re-encodes; failures retry on the next show.
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

private fun List<ProfileCard>.sortedCards() =
    sortedWith(compareBy<ProfileCard> { it.audience !is CardAudience.Public }.thenBy { it.priority })

@OptIn(ExperimentalEncodingApi::class)
internal fun decodeCardPng(base64: String): ByteArray? {
    val bytes = try {
        Base64.decode(base64)
    } catch (e: IllegalArgumentException) {
        return null
    }
    return bytes.takeIf { ImageFormatDetector.detectFormat(it) == "image/png" }
}
