package id.homebase.core.ui.screens.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.drives.files.SecurityGroupType
import id.homebase.api.client.link.LinkPreviewProvider
import id.homebase.api.client.profile.PublicProfileProviderCached
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.chat.conversationlist.AttachmentPendingFile
import id.homebase.core.feed.services.ChannelDefinitionService
import id.homebase.core.feed.services.EmbeddedPost
import id.homebase.core.feed.services.FeedPostSenderService
import id.homebase.core.feed.services.FeedProtocol
import id.homebase.core.feed.services.PostType
import id.homebase.core.feed.services.ReactAccess
import id.homebase.core.localization.TranslationUtil
import id.homebase.resources.MR
import id.homebase.resources.feed_compose_channel_public
import id.homebase.core.ui.screens.moments.toAttachmentInput
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

private const val TAG = "PostComposeViewModel"

/** Debounce window before firing a link-preview fetch for a freshly-typed URL. */
private const val LINK_PREVIEW_DEBOUNCE_MS = 600L

private val URL_REGEX = Regex(
    "https?://(?:www\\.)?[-a-zA-Z0-9@:%._+~#=]{1,256}\\.[a-zA-Z0-9()]{1,6}\\b(?:[-a-zA-Z0-9()@:%_+.~#?&/=]*)"
)

/**
 * Drives the [PostComposeScreen]. Builds a new feed post from a caption + optional media (or a
 * single auto-detected link preview when no media is attached) and publishes it to the public
 * channel drive via [FeedPostSenderService].
 *
 * Post type is derived at submit time: any attached media → [PostType.Media], otherwise
 * [PostType.Tweet]. The link preview is fetched (debounced) from the first URL in the caption and
 * is only shown/uploaded while no media is attached — attaching a file drops it, matching the
 * sender's `attachments.isEmpty() && linkPreview != null` contract.
 */
class PostComposeViewModel(
    private val postSender: FeedPostSenderService,
    private val linkPreviewProvider: LinkPreviewProvider,
    private val channelService: ChannelDefinitionService,
    private val credentialsManager: CredentialsManager,
    private val publicProfileProvider: PublicProfileProviderCached,
    repostOfJson: String? = null,
    editOfJson: String? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PostComposeUiState())
    val uiState: StateFlow<PostComposeUiState> = _uiState.asStateFlow()

    /** The post being edited (caption-only update), or null for a fresh/repost compose. */
    private var editTarget: PostEditSeed? = null

    init {
        // Seed a repost: deserialize the source quote so the composer renders it and submit()
        // rides it on the new post. A parse failure just yields a plain (non-repost) compose.
        if (repostOfJson != null) {
            runCatching { OdinSystemSerializer.deserialize<EmbeddedPost>(repostOfJson) }
                .onFailure { Logger.w(throwable = it, tag = TAG) { "repost payload parse failed" } }
                .getOrNull()
                ?.let { embedded -> _uiState.update { it.copy(embeddedPost = embedded) } }
        }
        // Edit mode: prefill caption; submit() then calls updatePost instead of createPost.
        if (editOfJson != null) {
            runCatching { OdinSystemSerializer.deserialize<PostEditSeed>(editOfJson) }
                .onFailure { Logger.w(throwable = it, tag = TAG) { "edit payload parse failed" } }
                .getOrNull()
                ?.let { seed ->
                    editTarget = seed
                    val channelId = runCatching { Uuid.parse(seed.channelId) }.getOrNull()
                    _uiState.update {
                        it.copy(
                            caption = seed.caption,
                            isEditing = true,
                            selectedChannelId = channelId ?: it.selectedChannelId,
                        )
                    }
                }
        }
        loadChannels()
        resolveAuthor()
    }

    // You aren't in your own contacts, so the author name comes from the public profile.
    private fun resolveAuthor() {
        viewModelScope.launch {
            val self = credentialsManager.getActiveCredentials()?.domain ?: return@launch
            _uiState.update { it.copy(selfOdinId = self) }
            val name = runCatching { publicProfileProvider.getPublicProfile(self)?.name }.getOrNull()
            if (!name.isNullOrBlank()) _uiState.update { it.copy(selfName = name) }
        }
    }

    /**
     * Build the channel picker options: the public channel first (always present), then any
     * resolved channel definitions sorted by name. Public is the default selection.
     */
    private fun loadChannels() {
        viewModelScope.launch {
            val publicName = TranslationUtil.getString(MR.string.feed_compose_channel_public)
            val publicOption = ChannelOption(FeedProtocol.PublicChannelDriveAlias, publicName)
            val others = channelService.channels.value
                .mapNotNull { (id, def) ->
                    val uuid = runCatching { Uuid.parse(id) }.getOrNull() ?: return@mapNotNull null
                    if (uuid == FeedProtocol.PublicChannelDriveAlias) null
                    else ChannelOption(uuid, def.name)
                }
                .sortedBy { it.name }
            _uiState.update { it.copy(channels = listOf(publicOption) + others) }
        }
    }

    fun selectChannel(id: Uuid) {
        _uiState.update { it.copy(selectedChannelId = id) }
    }

    private val _events = MutableSharedFlow<PostComposeEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<PostComposeEvent> = _events.asSharedFlow()

    /** The debounced link-preview fetch in flight; cancelled when the caption changes again. */
    private var linkPreviewJob: Job? = null

    /** The URL we last resolved a preview for, so we don't re-fetch an unchanged link. */
    private var lastPreviewedUrl: String? = null

    fun onCaptionChange(text: String) {
        _uiState.update { it.copy(caption = text) }
        maybeFetchLinkPreview(text)
    }

    fun addAttachments(attachments: List<AttachmentPendingFile>) {
        if (attachments.isEmpty()) return
        // Attaching media hides any link preview (the sender wouldn't upload it anyway). Cancel an
        // in-flight fetch so a late result doesn't repopulate the card under the media.
        linkPreviewJob?.cancel()
        _uiState.update {
            it.copy(
                attachments = it.attachments + attachments,
                linkPreview = null,
                isFetchingLinkPreview = false,
            )
        }
        lastPreviewedUrl = null
    }

    fun removeAttachment(attachmentId: Uuid) {
        _uiState.update { state ->
            state.copy(attachments = state.attachments.filterNot { it.attachmentId == attachmentId })
        }
        // Removing the last attachment re-enables link previews — re-evaluate the current caption.
        if (_uiState.value.attachments.isEmpty()) maybeFetchLinkPreview(_uiState.value.caption)
    }

    fun clearLinkPreview() {
        linkPreviewJob?.cancel()
        // Pin the dismissed URL so the same link isn't re-fetched until the user types a new one.
        lastPreviewedUrl = firstUrl(_uiState.value.caption)
        _uiState.update { it.copy(linkPreview = null, isFetchingLinkPreview = false) }
    }

    fun pickAudience(audience: SecurityGroupType) {
        _uiState.update { it.copy(audience = audience) }
    }

    fun setReactAccess(reactAccess: ReactAccess) {
        _uiState.update { it.copy(reactAccess = reactAccess) }
    }

    fun submit() {
        val state = _uiState.value
        if (!state.canPost) return

        _uiState.update { it.copy(isPosting = true, errorMessage = null) }

        viewModelScope.launch {
            try {
                val edit = editTarget
                if (edit != null) {
                    // Caption-only: updatePost preserves channel/ACL/reactAccess.
                    postSender.updatePost(
                        channelId = Uuid.parse(edit.channelId),
                        postUniqueId = Uuid.parse(edit.postId),
                        versionTag = Uuid.parse(edit.versionTag),
                        caption = state.caption.trim(),
                    )
                } else {
                    // A repost is always a Tweet (it quotes a source); otherwise media → Media.
                    val type = if (state.attachments.isNotEmpty()) PostType.Media else PostType.Tweet
                    postSender.createPost(
                        channelId = state.selectedChannelId,
                        type = type,
                        caption = state.caption.trim(),
                        attachments = state.attachments.map { it.toAttachmentInput() },
                        // Only ride a link preview when there's no media (sender drops it otherwise).
                        linkPreview = state.effectiveLinkPreview,
                        acl = AccessControlList(requiredSecurityGroup = state.audience.value),
                        reactAccess = state.reactAccess,
                        embeddedPost = state.embeddedPost,
                    )
                }
                _events.tryEmit(PostComposeEvent.Dismiss)
            } catch (t: Throwable) {
                Logger.e(throwable = t, tag = TAG) { "createPost failed: ${t.message}" }
                _uiState.update { it.copy(errorMessage = t.message) }
                _events.tryEmit(PostComposeEvent.ShowSnackbar(t.message))
            } finally {
                _uiState.update { it.copy(isPosting = false) }
            }
        }
    }

    /**
     * Debounced auto link-preview. A no-op once media is attached. Clears the card when the caption
     * has no URL; otherwise waits [LINK_PREVIEW_DEBOUNCE_MS] then fetches — skipping a URL we've
     * already resolved so back-and-forth edits don't re-hit the network.
     */
    private fun maybeFetchLinkPreview(caption: String) {
        if (_uiState.value.attachments.isNotEmpty()) return

        val url = firstUrl(caption)
        if (url == null) {
            linkPreviewJob?.cancel()
            lastPreviewedUrl = null
            _uiState.update { it.copy(linkPreview = null, isFetchingLinkPreview = false) }
            return
        }
        if (url == lastPreviewedUrl) return

        linkPreviewJob?.cancel()
        linkPreviewJob = viewModelScope.launch {
            delay(LINK_PREVIEW_DEBOUNCE_MS)
            _uiState.update { it.copy(isFetchingLinkPreview = true) }
            val preview = runCatching { linkPreviewProvider.getLinkPreview(url) }
                .onFailure { Logger.w(throwable = it, tag = TAG) { "link preview fetch failed for $url" } }
                .getOrNull()
            lastPreviewedUrl = url
            // Media may have landed during the fetch; don't repopulate the card under it.
            if (_uiState.value.attachments.isNotEmpty()) {
                _uiState.update { it.copy(isFetchingLinkPreview = false) }
                return@launch
            }
            _uiState.update { it.copy(linkPreview = preview, isFetchingLinkPreview = false) }
        }
    }

    private fun firstUrl(text: String): String? = URL_REGEX.find(text)?.value
}

/** Edit-mode composer seed (UUIDs as strings). channelId = the post's DRIVE alias. */
@Serializable
data class PostEditSeed(
    val postId: String,
    val channelId: String,
    val versionTag: String,
    val caption: String,
)

// One typed koin param so repost vs edit (two nullable Strings) stay unambiguous by type.
data class ComposerArgs(
    val repostOfJson: String? = null,
    val editOfJson: String? = null,
)

/** One-time effects the [PostComposeScreen] collects in a [androidx.compose.runtime.LaunchedEffect]. */
sealed interface PostComposeEvent {
    /** The post was enqueued; close the composer. */
    data object Dismiss : PostComposeEvent

    /** Surface a transient error to the user. */
    data class ShowSnackbar(val message: String?) : PostComposeEvent
}
