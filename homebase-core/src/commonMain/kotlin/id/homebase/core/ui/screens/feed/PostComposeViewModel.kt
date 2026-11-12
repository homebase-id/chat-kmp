package id.homebase.core.ui.screens.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.connections.ConnectionNetworkProvider
import id.homebase.api.client.contacts.ContactInfoGateway
import id.homebase.chat.conversationlist.AttachmentPendingFile
import id.homebase.core.feed.services.ChannelDefinitionService
import id.homebase.core.feed.services.FeedPostSenderService
import id.homebase.core.feed.services.FeedProtocol
import id.homebase.core.feed.services.PostAudience
import id.homebase.core.feed.services.postAcl
import id.homebase.core.localization.TranslationUtil
import id.homebase.core.ui.screens.moments.toAttachmentInput
import id.homebase.resources.MR
import id.homebase.resources.feed_compose_channel_public
import id.homebase.resources.feed_compose_post_failed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

private const val TAG = "PostComposeViewModel"

class PostComposeViewModel(
    private val postSender: FeedPostSenderService,
    private val channelService: ChannelDefinitionService,
    private val credentialsManager: CredentialsManager,
    private val contactInfo: ContactInfoGateway,
    private val connectionProvider: ConnectionNetworkProvider,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PostComposeUiState())
    val uiState: StateFlow<PostComposeUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<PostComposeEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<PostComposeEvent> = _events.asSharedFlow()

    init {
        loadChannels()
        loadCircles()
        resolveAuthor()
    }

    // You aren't in your own contacts, so the author name comes from the public profile.
    private fun resolveAuthor() {
        viewModelScope.launch {
            val self = credentialsManager.getActiveCredentials()?.domain ?: return@launch
            _uiState.update { it.copy(selfOdinId = self) }
            val name = runCatching { contactInfo.displayName(self) }.getOrNull()
            if (!name.isNullOrBlank()) _uiState.update { it.copy(selfName = name) }
        }
    }

    private fun loadChannels() {
        viewModelScope.launch {
            val publicName = TranslationUtil.getString(MR.string.feed_compose_channel_public)
            val publicOption = ChannelOption(FeedProtocol.PublicChannelDriveAlias, publicName)
            val others = channelService.channels.value
                .mapNotNull { (id, def) ->
                    val uuid = runCatching { Uuid.parse(id) }.getOrNull() ?: return@mapNotNull null
                    if (uuid == FeedProtocol.PublicChannelDriveAlias) null else ChannelOption(uuid, def.name)
                }
                .sortedBy { it.name }
            _uiState.update { it.copy(channels = listOf(publicOption) + others) }
        }
    }

    private fun loadCircles() {
        viewModelScope.launch {
            val circles = runCatching { connectionProvider.getCirclesWithMembers() }
                .onFailure { Logger.w(throwable = it, tag = TAG) { "circle list failed" } }
                .getOrDefault(emptyList())
                .filter { !it.circle.disabled }
                .map { CircleOption(it.circle.id, it.circle.name) }
                .sortedBy { it.name.lowercase() }
            _uiState.update { it.copy(circles = circles) }
        }
    }

    fun selectChannel(id: Uuid) = _uiState.update { it.copy(selectedChannelId = id) }

    fun onCaptionChange(text: String) = _uiState.update { it.copy(caption = text) }

    fun addAttachments(attachments: List<AttachmentPendingFile>) {
        if (attachments.isEmpty()) return
        _uiState.update { it.copy(attachments = it.attachments + attachments) }
    }

    fun removeAttachment(attachmentId: Uuid) = _uiState.update { state ->
        state.copy(attachments = state.attachments.filterNot { it.attachmentId == attachmentId })
    }

    fun pickPublic() = _uiState.update { it.copy(audience = PostAudience.Public) }

    fun pickCircles(circleIds: Set<String>) = _uiState.update {
        if (circleIds.isEmpty()) it.copy(audience = PostAudience.Public, selectedCircleIds = emptySet())
        else it.copy(audience = PostAudience.Circles, selectedCircleIds = circleIds)
    }

    fun submit() {
        val state = _uiState.value
        if (!state.canPost) return
        _uiState.update { it.copy(isPosting = true) }

        viewModelScope.launch {
            try {
                postSender.createPost(
                    channelId = state.selectedChannelId,
                    caption = state.caption,
                    attachments = state.attachments.map { it.toAttachmentInput() },
                    acl = postAcl(state.audience, state.selectedCircleIds.toList()),
                )
                _events.tryEmit(PostComposeEvent.Dismiss)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = TAG) { "createPost failed" }
                _events.tryEmit(PostComposeEvent.ShowSnackbar(TranslationUtil.getString(MR.string.feed_compose_post_failed)))
            } finally {
                _uiState.update { it.copy(isPosting = false) }
            }
        }
    }
}

sealed interface PostComposeEvent {
    data object Dismiss : PostComposeEvent
    data class ShowSnackbar(val message: String) : PostComposeEvent
}
