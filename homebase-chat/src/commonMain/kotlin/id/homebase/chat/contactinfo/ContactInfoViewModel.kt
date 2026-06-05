package id.homebase.chat.contactinfo

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.files.DescriptorContent
import id.homebase.api.common.BatchResult
import id.homebase.api.common.OdinId
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.ChatMessageStream
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.XorIdUtil
import id.homebase.chat.services.content.MessageContent
import id.homebase.chat.services.convo.contact.ContactService
import id.homebase.core.ui.navigation.Route
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ContactInfoViewModel(
    savedStateHandle: SavedStateHandle,
    val contactService: ContactService,
    private val chatMessageStream: ChatMessageStream,
    private val credentialsManager: CredentialsManager,
) : ViewModel() {
    val route = savedStateHandle.toRoute<Route.ContactInfo>()
    private val _uiState = MutableStateFlow(ContactInfoUiState())
    val uiState: StateFlow<ContactInfoUiState> = _uiState.asStateFlow()

    init {
        loadData()
        loadSummary()
    }

    fun onUiAction(action: ContactInfoUiAction) {
        when (action) {
            is ContactInfoUiAction.BackClicked -> _uiState.update { it.copy(uiEvent = ContactInfoUiEvent.Back)}
        }
    }

    fun eventConsumed() {
        _uiState.update { it.copy(uiEvent = null) }
    }

    private fun loadData() {
        viewModelScope.launch {
            try {
                contactService.start()
                val contact = contactService.resolveByOdinId(OdinId(route.odinId))
                _uiState.update { it.copy(contact = contact, isLoading = false) }
            } catch (e: Exception) {
                Logger.e( "Failed to load contact", e)
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    /**
     * Loads an overview of the 1:1 conversation with the contact. The 1:1
     * conversationId is the deterministic XorId of the two identities, so we can
     * query the chat drive directly without first resolving a conversation
     * record. Runs independently of [loadData] so the avatar/name paint before
     * the (heavier) message scan finishes.
     */
    private fun loadSummary() {
        viewModelScope.launch {
            try {
                val self = credentialsManager.requireActiveDomain()
                val other = OdinId(route.odinId)
                val conversationId =
                    XorIdUtil.getNewXorId(self.domainName, other.domainName)

                val batch = chatMessageStream.fetchMessages(
                    conversationId = conversationId,
                    limit = SUMMARY_MESSAGE_CAP,
                )

                // When the window is truncated, its oldest row isn't the real
                // first message — fetch that separately (cheap, limit = 1) so
                // "chatting since" stays accurate regardless of the cap.
                val firstMessageDate =
                    if (batch.hasMoreRows) {
                        chatMessageStream.fetchMessages(
                            conversationId = conversationId,
                            limit = 1,
                            sortOrder = QueryBatchSortOrder.OldestFirst,
                        ).records.firstOrNull()?.userDate
                    } else {
                        // newest-first → the last row is the oldest message
                        batch.records.lastOrNull()?.userDate
                    }

                val summary = withContext(Dispatchers.Default) {
                    buildSummary(batch, firstMessageDate)
                }
                _uiState.update { it.copy(summary = summary, isSummaryLoading = false) }
            } catch (e: Exception) {
                Logger.e("Failed to load chat summary", e)
                _uiState.update { it.copy(isSummaryLoading = false) }
            }
        }
    }

    private fun buildSummary(
        batch: BatchResult<MessageUiModel>,
        firstMessageDate: kotlin.time.Instant?,
    ): ChatSummaryUiModel {
        var photos = 0
        var stickers = 0
        var videos = 0
        var audio = 0
        var files = 0
        var links = 0
        var locations = 0
        var diceRolls = 0
        var events = 0
        var polls = 0
        val recentMedia = mutableListOf<SharedMediaItem>()

        for (message in batch.records) {
            when (message.messageContent) {
                is MessageContent.DiceRoll -> diceRolls++
                is MessageContent.Event -> events++
                is MessageContent.Groodle -> polls++
                else -> Unit // text/media and Unknown fall through to payload counting
            }

            // Mirror MessageBubbleRaw's media filter: skip the internal default
            // payload + descriptor payloads, count only real attachments.
            val mediaPayloads = message.payloads?.filter {
                it.key != ChatProtocol.DefaultPayloadKey &&
                        !it.key.startsWith(ChatProtocol.DEFAULT_PAYLOAD_DESCRIPTOR_KEY)
            } ?: emptyList()

            for (payload in mediaPayloads) {
                val contentType = payload.contentType
                when {
                    // chat_links / chat_loc previews also carry an image/* content
                    // type, so key-match them before the image branch.
                    payload.key == ChatProtocol.PAYLOAD_KEY_LINKS -> links++
                    payload.key == ChatProtocol.PAYLOAD_KEY_LOCATION -> locations++
                    contentType == null -> Unit
                    contentType.startsWith("image/") -> {
                        val isSticker =
                            (payload.descriptorInfo() as? DescriptorContent.ImageFile)?.isSticker == true
                        if (isSticker) stickers++ else photos++
                        if (recentMedia.size < RECENT_MEDIA_CAP) {
                            recentMedia.add(
                                SharedMediaItem(
                                    fileId = message.fileId,
                                    payload = payload,
                                    keyHeader = message.keyHeader,
                                    previewThumbnail = message.previewThumbnail,
                                    isSticker = isSticker,
                                )
                            )
                        }
                    }
                    contentType.startsWith("video/") ||
                            contentType == "application/vnd.apple.mpegurl" -> videos++
                    contentType.startsWith("audio/") -> audio++
                    else -> files++ // documents, archives, notes, etc.
                }
            }
        }

        return ChatSummaryUiModel(
            totalMessages = batch.records.size,
            isTruncated = batch.hasMoreRows,
            firstMessageDate = firstMessageDate,
            photoCount = photos,
            stickerCount = stickers,
            videoCount = videos,
            audioCount = audio,
            fileCount = files,
            linkCount = links,
            locationCount = locations,
            diceRollCount = diceRolls,
            eventCount = events,
            pollCount = polls,
            recentMedia = recentMedia.toImmutableList(),
        )
    }

    companion object {
        /** Upper bound on messages scanned for the conversation overview. */
        const val SUMMARY_MESSAGE_CAP = 1000

        /** Max thumbnails shown in the shared-media strip. */
        const val RECENT_MEDIA_CAP = 12
    }
}
