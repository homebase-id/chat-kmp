package id.homebase.core.ui.screens.card

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.OwnerSessionRepository
import id.homebase.api.client.connections.CircleWithMembers
import id.homebase.api.client.connections.ConnectionNetworkProvider
import id.homebase.api.client.drives.CONFIRMED_CONNECTIONS_SYSTEM_CIRCLE
import id.homebase.api.client.drives.QueryBatchRequest
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.identity.PublicIdentityRepository
import id.homebase.api.client.profile.ProfileAttribute
import id.homebase.api.client.profile.ProfileRepository
import id.homebase.api.common.OdinId
import id.homebase.api.util.compareStringUuId
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.lib.image.ImageFormatDetector
import id.homebase.core.config.AUTO_CONNECTIONS_CIRCLE_ID
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.image.HomebaseImageLoader
import id.homebase.core.ui.screens.contactbook.isOwnedByContactsApp
import id.homebase.core.ui.screens.profile.LoadedProfileAttributes
import id.homebase.core.ui.screens.profile.ProfileAudience
import id.homebase.core.ui.screens.profile.keepingCircles
import id.homebase.core.ui.screens.profile.keepingOtherCircles
import id.homebase.core.ui.screens.profile.restoreAcl
import id.homebase.core.ui.screens.profile.audience
import id.homebase.core.ui.screens.profile.photoImageData
import id.homebase.core.ui.screens.profile.saveWithAudience
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import id.homebase.core.ui.screens.profile.computeAttributeEdit

private const val TAG = "ProfileCard"

private val EXPORT_TIMEOUT = 30.seconds
private val PAINT_TIMEOUT = 1.5.seconds
private val INTRO_READY_TIMEOUT = 5.seconds

class CardCover(val design: String, val image: ImageBitmap)

data class ProfileCardUiState(
    val savedDesign: String = CardDesign.LINKS,
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
    val attributes: List<ProfileAttribute> = emptyList(),
    val circles: List<CardCircle> = emptyList(),
    val isContentBusy: Boolean = false,
    val contentSaves: Int = 0,
    val contentFailures: Int = 0,
    val pendingImport: CardAudience? = null,
) {
    internal val contentItems: List<CardContentItem> get() = cardContentItems(attributes, circles, selectedAudience)

    internal val importSources: List<CardImportSource>
        get() = cards.map { it.audience }
            .filterNot { it.isSameAs(selectedAudience) }
            .mapNotNull { from ->
                importPlan(attributes, circles, from, selectedAudience).takeIf { it.isNotEmpty() }?.let { CardImportSource(from, it.size) }
            }

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
    private val isAtTemplate: Boolean
        get() = selectedCard?.let { card -> cardTemplateFor(card.audience)?.let { it.design == card.design && it.overrides == card.overrides } } == true
    val showsReset: Boolean get() = hasStoredCard && !isAtTemplate && !isCircleReadOnly
    val isUnsavedCard: Boolean get() = selectedCard != null && !hasStoredCard
    val canReset: Boolean get() = showsReset && !isExporting && !isCardBusy && !isSavingDesign
    val isCircleSelected: Boolean get() = selectedAudience is CardAudience.Circle
    val cardTopArgb: Int? get() = edges[design]?.topArgb
    val cardBottomArgb: Int? get() = edges[design]?.bottomArgb
    val canShare: Boolean get() = isCardReady && !isExporting
    val canSaveDesign: Boolean get() = hasUnsavedChanges && !isSavingDesign && !isCardBusy && !isCircleReadOnly
}

data class CardCircle(val id: String, val name: String)

internal data class CardImportSource(val audience: CardAudience, val newItems: Int)

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
    data class ContentImported(val count: Int, val from: CardAudience) : ProfileCardEvent
    data object ImportUndoExpired : ProfileCardEvent
}

interface ProfileCardSource {
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
    /** Every circle the server holds, disabled, other apps' and system ones included. */
    suspend fun serverCircleIds(): Set<String>
    suspend fun saveCircleCard(card: ProfileCard): ProfileCard?
    suspend fun saveProfileAttribute(type: String, data: JsonObject, audience: ProfileAudience, existing: ProfileAttribute?): ProfileAttribute
    /** Puts [previous]'s exact ACL back on [current], which is the same attribute after a later write. */
    suspend fun restoreProfileAttribute(current: ProfileAttribute, previous: ProfileAttribute): ProfileAttribute
    suspend fun deleteProfileAttribute(attribute: ProfileAttribute): Boolean
    suspend fun resetPublicCard()
    suspend fun resetCircleCard(card: ProfileCard)
    suspend fun clearSavedDesign()
}

class DefaultProfileCardSource(
    private val ownerSessionRepository: OwnerSessionRepository,
    private val profileRepository: ProfileRepository,
    private val publicIdentityRepository: PublicIdentityRepository,
    private val imageLoader: HomebaseImageLoader,
    private val fileOperations: FileOperationsProvider,
    private val driveQueryProvider: DriveQueryProvider,
    private val driveFileProvider: DriveFileProvider,
    private val cardPreferences: CardPreferences,
    private val cardRepository: CardRepository,
    private val connectionProvider: ConnectionNetworkProvider,
) : ProfileCardSource {
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

    // Listing system circles is owner-only, but the server creates both at startup, so they always exist.
    override suspend fun serverCircleIds(): Set<String> =
        connectionProvider.getCirclesWithMembers(includeSystemCircle = false).map { it.circle.id }.toSet() +
            setOf(CONFIRMED_CONNECTIONS_SYSTEM_CIRCLE, AUTO_CONNECTIONS_CIRCLE_ID)

    override suspend fun saveCircleCard(card: ProfileCard) = cardRepository.saveCircle(card)

    override suspend fun saveProfileAttribute(
        type: String,
        data: JsonObject,
        audience: ProfileAudience,
        existing: ProfileAttribute?,
    ) = profileRepository.saveWithAudience(type, data, audience, existing)

    override suspend fun restoreProfileAttribute(current: ProfileAttribute, previous: ProfileAttribute) =
        profileRepository.restoreAcl(current, previous)

    override suspend fun deleteProfileAttribute(attribute: ProfileAttribute) =
        profileRepository.delete(attribute.id, attribute.versionTag)

    override suspend fun resetPublicCard() = cardRepository.resetPublic()

    override suspend fun resetCircleCard(card: ProfileCard) = cardRepository.resetCircle(card)

    override suspend fun clearSavedDesign() = cardPreferences.clearDesign()

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
 * Owns the pre-warmed [host] for the part of the graph that can open the card (Settings), so the
 * card screen only attaches it; [onCleared] disposes it. The host is built by
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
    private var designSaveJob: Job? = null

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
    private var unsavedCard: UnsavedCard? = null
    private var cardJob: Job? = null
    private var circles: List<CardCircle> = emptyList()
    private var importUndo: List<ProfileAttribute>? = null
    private val audiencesBeforePublic = mutableMapOf<Uuid, ProfileAudience>()
    private val templateLock = Mutex()
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

    /** The design picker saves as it picks; Customise's own Save is for the options. */
    fun onDesignPicked(design: String) {
        onDesignSelected(design)
        saveLatestPick()
    }

    // One save in flight; picks made meanwhile collapse into the last one, saved when it lands.
    private fun saveLatestPick() {
        if (designSaveJob?.isActive == true) return
        designSaveJob = viewModelScope.launch {
            while (true) {
                _uiState.first { !it.isSavingDesign }
                val picked = _uiState.value.previewDesign
                if (picked == null || !_uiState.value.canSaveDesign) break
                saveDesign(leaveAfter = false)?.join()
                if (_uiState.value.previewDesign == picked) break
            }
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
        dropImportUndo()
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
        val item = state.contentItems.firstOrNull { it.id == id }?.takeUnless { it.required } ?: return
        val attribute = state.attributes.firstOrNull { it.id == id } ?: return
        val next = item.audience.shownOn(state.selectedAudience, on, audiencesBeforePublic[id], state.circles) ?: return
        if (on && state.selectedAudience == CardAudience.Public) audiencesBeforePublic[id] = item.audience
        writeContent(attribute.type, attribute.data, next, attribute)
    }

    fun onContentAdded(type: String, updates: Map<String, String>) {
        val state = _uiState.value
        if (state.isContentBusy) return
        // A repeatable type only refills a blank record; one with a value stays and the add is a new record.
        val existing = LoadedProfileAttributes.from(state.attributes).byType[type]
            ?.takeUnless { type in REPEATABLE_TYPES && state.contentItems.any { item -> item.id == it.id } }
        val existingAudience = existing?.audience(state.circles)
        val audience = existingAudience?.let { it.shownOn(state.selectedAudience, true, null) ?: it } ?: state.selectedAudience.newItemAudience()
        val edit = computeAttributeEdit(existing, type, updates, audience, existingAudience) ?: return
        writeContent(edit.type, edit.data, edit.audience, existing)
    }

    fun onCardSelected(audience: CardAudience) = selectAudience(audience)

    // The editor can open long after the card was loaded, and the profile may have changed since.
    fun onContentEditorShown() = load()

    fun onContentEdited(id: Uuid, updates: Map<String, String>, audience: ProfileAudience) {
        val state = _uiState.value
        if (state.isContentBusy || !audience.isSavableWith(state.circles)) return
        val attribute = state.attributes.firstOrNull { it.id == id } ?: return
        val edit = computeAttributeEdit(attribute, attribute.type, updates, audience, attribute.audience(state.circles)) ?: return
        writeContent(edit.type, edit.data, edit.audience, attribute)
    }

    fun onContentDeleted(id: Uuid) {
        val state = _uiState.value
        if (state.isContentBusy) return
        val attribute = state.attributes.firstOrNull { it.id == id } ?: return
        dropImportUndo()
        _uiState.update { it.copy(isContentBusy = true) }
        viewModelScope.launch {
            val deleted = attempt("deleting profile content ${attribute.type}") { source.deleteProfileAttribute(attribute) } == true
            if (deleted) applyAttributes(emptyList(), saved = true, removed = setOf(id)) else contentSaveFailed()
        }
    }

    private fun dropImportUndo() {
        if (importUndo == null) return
        importUndo = null
        _events.tryEmit(ProfileCardEvent.ImportUndoExpired)
    }

    private fun writeContent(type: String, data: JsonObject, audience: ProfileAudience, existing: ProfileAttribute?) {
        dropImportUndo()
        val card = _uiState.value.selectedAudience
        _uiState.update { it.copy(isContentBusy = true) }
        viewModelScope.launch {
            persistTemplateIfUnsaved(card)
            val kept = existingCircles(audience.circleIds)
            val saved = attempt("saving profile content $type") { source.saveProfileAttribute(type, data, audience.keepingOtherCircles(kept), existing) }
            if (saved == null) {
                contentSaveFailed()
                return@launch
            }
            applyAttributes(listOf(saved), saved = true, removed = deleteDuplicates(existing))
        }
    }

    // A same-data leftover would keep showing the item to every connection after it was hidden.
    private suspend fun deleteDuplicates(edited: ProfileAttribute?): Set<Uuid> {
        if (edited == null || edited.type in REPEATABLE_TYPES) return emptySet()
        val loaded = LoadedProfileAttributes.from(_uiState.value.attributes)
        if (loaded.byType[edited.type]?.id != edited.id) return emptySet()
        return loaded.duplicates[edited.type].orEmpty()
            .filter { attempt("deleting a leftover ${it.type}") { source.deleteProfileAttribute(it) } == true }
            .map { it.id }
            .toSet()
    }

    // The web resolves a Public template itself but can't tell which circle a visitor is in, so a circle card is stored on its first edit.
    private suspend fun persistTemplateIfUnsaved(audience: CardAudience): Boolean = templateLock.withLock {
        val card = _uiState.value.cards.firstOrNull { it.audience.isSameAs(audience) }
        if (card == null || card.audience !is CardAudience.Circle || !card.isDefault || cardTemplateFor(card.audience) == null || !source.supportsCircleCards) return@withLock false
        val stored = attempt("saving the ${card.audience.logName()} card template") { source.saveCircleCard(card) }
        _uiState.update(::withCircleSupport)
        if (stored == null) return@withLock false
        _uiState.update { state -> state.copy(cards = state.cards.map { if (it.audience.isSameAs(stored.audience)) stored else it }) }
        true
    }

    fun onPhotoChanged() = load(force = true)

    fun onImportRequested(from: CardAudience) {
        val state = _uiState.value
        if (state.isContentBusy || state.isCircleReadOnly || state.importSources.none { it.audience.isSameAs(from) }) return
        if (state.selectedAudience == CardAudience.Public) _uiState.update { it.copy(pendingImport = from) } else runImport(from)
    }

    fun onImportConfirmed() {
        val from = _uiState.value.pendingImport ?: return
        _uiState.update { it.copy(pendingImport = null) }
        runImport(from)
    }

    fun onImportCancelled() = _uiState.update { it.copy(pendingImport = null) }

    // No multi-attribute write exists, so a failure part-way restores the earlier ones.
    private fun runImport(from: CardAudience) {
        val state = _uiState.value
        val plan = importPlan(state.attributes, state.circles, from, state.selectedAudience)
        if (plan.isEmpty()) return
        dropImportUndo()
        if (state.selectedAudience == CardAudience.Public) plan.forEach { audiencesBeforePublic[it.attribute.id] = it.previous }
        _uiState.update { it.copy(isContentBusy = true) }
        viewModelScope.launch {
            persistTemplateIfUnsaved(state.selectedAudience)
            val kept = existingCircles(plan.flatMap { it.next.circleIds + it.attribute.acl.circleIdList.orEmpty() })
            val written = mutableListOf<Pair<ProfileAttribute, ProfileAttribute>>()
            for (step in plan) {
                val saved = attempt("importing ${step.attribute.type}") {
                    source.saveProfileAttribute(step.attribute.type, step.attribute.data, step.next.keepingOtherCircles(kept), step.attribute)
                }
                if (saved == null) {
                    plan.forEach { audiencesBeforePublic.remove(it.attribute.id) }
                    val rolledBack = restoreAcls(written, kept).attributes
                    _events.tryEmit(ProfileCardEvent.ContentSaveFailed)
                    applyAttributes(rolledBack, saved = false, failed = true)
                    return@launch
                }
                written += step.attribute to saved
            }
            importUndo = written.map { it.first }
            applyAttributes(written.map { it.second }, saved = true)
            _events.tryEmit(ProfileCardEvent.ContentImported(written.size, from))
        }
    }

    fun onImportUndone() {
        val previous = importUndo ?: return
        if (_uiState.value.isContentBusy) return
        importUndo = null
        previous.forEach { audiencesBeforePublic.remove(it.id) }
        _uiState.update { it.copy(isContentBusy = true) }
        viewModelScope.launch {
            val current = _uiState.value.attributes.associateBy { it.id }
            val kept = existingCircles(previous.flatMap { it.acl.circleIdList.orEmpty() })
            val restored = restoreAcls(previous.mapNotNull { p -> current[p.id]?.let { p to it } }, kept)
            importUndo = restored.failed.takeIf { it.isNotEmpty() }
            if (restored.failed.isNotEmpty()) _events.tryEmit(ProfileCardEvent.ContentSaveFailed)
            applyAttributes(restored.attributes, saved = restored.failed.isEmpty(), failed = restored.failed.isNotEmpty())
        }
    }

    // A deleted circle stays named in stored ACLs and the server refuses any write that names it; ids the server can't confirm are kept.
    private suspend fun existingCircles(ids: List<String>): (String) -> Boolean {
        fun known(id: String) = circles.any { compareStringUuId(it.id, id) }
        if (ids.all(::known)) return { true }
        val onServer = attempt("listing the server's circles") { source.serverCircleIds() } ?: return { true }
        return { id -> known(id) || onServer.any { compareStringUuId(it, id) } }
    }

    private class Restored(val attributes: List<ProfileAttribute>, val failed: List<ProfileAttribute>)

    // A restore that fails keeps the attribute as the server has it, so the list never shows an access that was not written.
    private suspend fun restoreAcls(pairs: List<Pair<ProfileAttribute, ProfileAttribute>>, kept: (String) -> Boolean): Restored {
        val failed = mutableListOf<ProfileAttribute>()
        val attributes = pairs.map { (previous, current) ->
            attempt("restoring the access of ${previous.type}") { source.restoreProfileAttribute(current, previous.keepingCircles(kept)) }
                ?: current.also { failed += previous }
        }
        return Restored(attributes, failed)
    }

    private fun applyAttributes(changed: List<ProfileAttribute>, saved: Boolean, removed: Set<Uuid> = emptySet(), failed: Boolean = false) {
        val ids = changed.map { it.id }.toSet() + removed
        content = content?.let { c -> c.copy(attributes = c.attributes.filterNot { it.id in ids } + changed) }
        _uiState.update {
            it.copy(
                isContentBusy = false,
                contentSaves = if (saved) it.contentSaves + 1 else it.contentSaves,
                contentFailures = if (failed) it.contentFailures + 1 else it.contentFailures,
                attributes = it.attributes.filterNot { a -> a.id in ids } + changed,
            )
        }
        render()
    }

    private fun contentSaveFailed() {
        _uiState.update { it.copy(isContentBusy = false, contentFailures = it.contentFailures + 1) }
        _events.tryEmit(ProfileCardEvent.ContentSaveFailed)
    }

    fun onPreviewDiscarded() {
        if (designSaveJob?.isActive == true) return
        if (_uiState.value.previewDesign == null && _uiState.value.previewOverrides == null) return
        _uiState.update { it.copy(previewDesign = null, previewOverrides = null) }
        render()
    }

    fun onSaveDesign() {
        saveDesign(leaveAfter = true)
    }

    private fun saveDesign(leaveAfter: Boolean): Job? {
        val state = _uiState.value
        if (!state.canSaveDesign) return null
        if (state.isCircleSelected) return saveCircleCard(state, leaveAfter)
        val design = state.design
        val overrides = state.overrides.takeIf { state.previewOverrides != null }
        _uiState.update { it.copy(isSavingDesign = true) }
        return viewModelScope.launch {
            val saved = attempt("saving card design $design") { source.saveDesign(design) } != null
            if (saved) writeCard(UnsavedCard(design, overrides).also { unsavedCard = it })
            _uiState.update {
                val newerPick = it.previewDesign?.takeIf { p -> p != design }
                if (saved) it.copy(
                    isSavingDesign = false,
                    savedDesign = design,
                    hasLocalPublicDesign = true,
                    previewDesign = newerPick,
                    previewOverrides = it.previewOverrides.takeIf { newerPick != null },
                    cards = it.cards.map { card ->
                        if (card.audience != CardAudience.Public) card else card.withDesign(design, overrides)
                    },
                )
                else it.copy(isSavingDesign = false)
            }
            if (saved) render()
            if (!saved) _events.tryEmit(ProfileCardEvent.DesignSaveFailed) else if (leaveAfter) _events.tryEmit(ProfileCardEvent.DesignSaved)
        }
    }

    private fun saveCircleCard(state: ProfileCardUiState, leaveAfter: Boolean): Job? {
        val card = state.selectedCard ?: return null
        val overrides = state.overrides.takeIf { state.previewOverrides != null }
        val updated = card.withDesign(state.design, overrides)
        _uiState.update { it.copy(isSavingDesign = true) }
        return viewModelScope.launch {
            val stored = attempt("saving the ${card.audience} card") { source.saveCircleCard(updated) }
            _uiState.update {
                val newerPick = it.previewDesign?.takeIf { p -> p != updated.design }
                withCircleSupport(
                    if (stored != null) it.copy(
                        isSavingDesign = false,
                        previewDesign = newerPick,
                        previewOverrides = it.previewOverrides.takeIf { newerPick != null },
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
                stored != null -> ProfileCardEvent.DesignSaved.takeIf { leaveAfter }
                !source.supportsCircleCards -> ProfileCardEvent.CircleCardsUnsupported
                else -> ProfileCardEvent.DesignSaveFailed
            }
            event?.let(_events::tryEmit)
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

    fun onEditorClosed() {
        dropImportUndo()
    }

    /** Picks up profile edits made since the pre-warm; the warm card shows until they land. */
    fun onScreenShown() {
        shownAt = TimeSource.Monotonic.markNow()
        tilesLogged.clear()
        startHost()
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
        val photo = attributes.photoFor(CardAudience.Public)?.photoImageData()
        content = CardContent(odinId, attributes, defaults, photo)
        photo?.let { imageSrcAsync(it, CARD_IMAGE_MAX_EDGE) }
        val stored = attributes.profileCards()
        val storedPublic = stored.publicCard()
        val cardDesign = storedPublic?.design?.takeIf { it in CardDesign.all }
        val pending = unsavedCard
        val siteDesign = defaults.design?.takeIf { it in CardDesign.all }
        val useTemplate = storedDesign == null && pending == null && siteDesign == null
        val saved = pending?.design ?: cardDesign ?: storedDesign ?: siteDesign ?: PUBLIC_CARD_TEMPLATE.design
        if (pending != null && cardJob?.isActive != true) writeCard(pending)
        val pendingOverrides = pending?.overrides
        val publicCard = (storedPublic ?: ProfileCard(
            Uuid.NIL, Uuid.NIL, CardAudience.Public, saved,
            if (useTemplate) PUBLIC_CARD_TEMPLATE.overrides else CardOverrides.EMPTY,
        ))
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

    // Circles without a template, saved in a design this build doesn't know, or on a server that can't keep circle cards, borrow the public card: that is what /card serves them.
    private fun circleCards(stored: List<ProfileCard>, publicCard: ProfileCard): List<ProfileCard> {
        val priorities = fixedCirclePriorities(circles)
        return circles.map { circle ->
            val audience = CardAudience.Circle(circle.id, circle.name)
            val priority = priorities.getValue(circle.id)
            val saved = stored.firstOrNull { it.audience.isSameAs(audience) }
            when {
                saved == null -> cardTemplateFor(audience)?.takeIf { source.supportsCircleCards }
                    ?.let { ProfileCard(Uuid.NIL, Uuid.NIL, audience, it.design, it.overrides, priority) }
                    ?: ProfileCard(Uuid.NIL, Uuid.NIL, audience, publicCard.design, publicCard.overrides, priority)
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
        val photo = content.attributes.photoFor(audience)?.photoImageData()
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
            visitorPreview = true,
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
