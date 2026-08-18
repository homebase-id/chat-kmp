package id.homebase.core.ui.screens.contactbook

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.contacts.ContactRepository
import id.homebase.api.client.contacts.ContactsProvider
import id.homebase.api.file.FileOperationsProvider
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.builder.AttachmentInput
import id.homebase.chat.services.builder.MessageAttachmentBuilder
import id.homebase.upload.PayloadBundle
import id.homebase.chat.services.ChatMessageSenderService
import id.homebase.chat.contactcard.ContactCardDescriptor
import id.homebase.chat.services.content.MessageContent
import id.homebase.core.contactbook.ContactOverrideStore
import id.homebase.core.ui.screens.contactbook.model.toContactBookEntry
import id.homebase.resources.MR
import id.homebase.resources.chat_contact_share_unshareable
import id.homebase.resources.error_unknown
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

private const val TAG = "ShareContactPickerViewModel"

/**
 * Picks a Homebase contact book row and sends it into [conversationId] as a
 * [MessageContent.ContactCard]. Lives in `:homebase-core` because the contact book does — the
 * chat attachment sheet can't reach it, so it navigates here (the Location share pattern).
 */
class ShareContactPickerViewModel(
    private val conversationId: Uuid,
    private val repo: ContactRepository,
    private val overrideStore: ContactOverrideStore,
    private val chatMessageSenderService: ChatMessageSenderService,
    private val fileOperationsProvider: FileOperationsProvider,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ShareContactPickerUiState())
    val uiState: StateFlow<ShareContactPickerUiState> = _uiState.asStateFlow()
    val searchTextState = TextFieldState()

    private val _events = MutableSharedFlow<ShareContactPickerUiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<ShareContactPickerUiEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch { repo.ensureLoaded() }
        // The extras, the organization and any edited primary live only in the override blob, which
        // the contacts query does not carry — without this the card ships the synced values the
        // user edited away from.
        viewModelScope.launch {
            repo.contacts.collect { list -> list.forEach { overrideStore.hydrate(it) } }
        }
        viewModelScope.launch {
            combine(
                repo.contacts,
                overrideStore.overrides,
                snapshotFlow { searchTextState.text.toString() },
            ) { contacts, overrides, query ->
                shareContactCandidates(
                    contacts.mapNotNull {
                        it.toContactBookEntry()?.withOverride(overrides[it.uniqueId])
                    },
                    query,
                )
            }.collect { candidates ->
                _uiState.update { it.copy(candidates = candidates) }
            }
        }
    }

    fun onUiAction(action: ShareContactPickerUiAction) {
        when (action) {
            is ShareContactPickerUiAction.ContactClicked -> {
                val candidate = _uiState.value.candidates
                    .firstOrNull { it.entry.uniqueId == action.entry.uniqueId }
                if (candidate == null || !candidate.shareable) {
                    _events.tryEmit(
                        ShareContactPickerUiEvent.ShowError(MR.string.chat_contact_share_unshareable)
                    )
                    return
                }
                _uiState.update {
                    it.copy(selectedId = if (it.selectedId == action.entry.uniqueId) null else action.entry.uniqueId)
                }
            }

            ShareContactPickerUiAction.SendClicked -> send()
            ShareContactPickerUiAction.BackClicked -> _events.tryEmit(ShareContactPickerUiEvent.Back)
        }
    }

    // The picker can be the first screen this session to touch overrides, and the list is built
    // from whatever has landed. Sending inside that window would ship the synced values the user
    // edited away from, so the one selected contact is re-read after its hydrate completes.
    private suspend fun resolvedDescriptor(
        uniqueId: Uuid?,
        fallback: ContactCardDescriptor,
    ): ContactCardDescriptor {
        val contact = repo.contacts.value.firstOrNull { it.uniqueId == uniqueId } ?: return fallback
        overrideStore.hydrateAll(listOf(contact))
        val entry = contact.toContactBookEntry()
            ?.withOverride(overrideStore.overrides.value[contact.uniqueId])
        return entry?.let { ContactCardImport.toDescriptor(it) } ?: fallback
    }

    // Re-uploaded rather than referenced: it lives on our own contacts drive, which the recipient
    // cannot read. Best-effort — a photo failure must never block the card itself.
    private suspend fun photoBundle(uniqueId: Uuid?): PayloadBundle? = runCatching {
        val contact = repo.contacts.value.firstOrNull { it.uniqueId == uniqueId } ?: return null
        val image = contact.image ?: return null
        val bytes = repo.loadPayloadBytes(contact, ContactsProvider.CONTACT_IMAGE_PAYLOAD_KEY)
        if (bytes == null || bytes.isEmpty()) return null
        val contentType = image.payload.contentType?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
        val path = fileOperationsProvider.writeBytesToTempFile(
            bytes = bytes,
            prefix = "contact-card-photo",
            suffix = if (contentType == "image/png") ".png" else ".jpg",
        )
        MessageAttachmentBuilder.buildSingle(
            attachment = AttachmentInput(filePath = path, contentType = contentType),
            fileOperationsProvider = fileOperationsProvider,
            payloadKey = ChatProtocol.PAYLOAD_KEY_MESSAGE_WEB + "0",
        )
    }.onFailure {
        if (it is kotlin.coroutines.cancellation.CancellationException) throw it
        Logger.w(throwable = it, tag = TAG) { "contact card photo skipped" }
    }.getOrNull()

    private fun send() {
        val state = _uiState.value
        if (state.isSending) return
        val descriptor = state.selected?.descriptor ?: run {
            _events.tryEmit(
                ShareContactPickerUiEvent.ShowError(MR.string.chat_contact_share_unshareable)
            )
            return
        }
        val selectedId = state.selected?.entry?.uniqueId
        _uiState.update { it.copy(isSending = true) }
        viewModelScope.launch {
            try {
                chatMessageSenderService.sendNewTypedMessage(
                    messageUniqueId = Uuid.random(),
                    conversationId = conversationId,
                    content = MessageContent.ContactCard(resolvedDescriptor(selectedId, descriptor)),
                    previousMessageUniqueId = null,
                    payloadBundle = photoBundle(selectedId),
                )
                _events.emit(ShareContactPickerUiEvent.MessageSent)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = TAG) { "contact card send failed" }
                _uiState.update { it.copy(isSending = false) }
                _events.emit(ShareContactPickerUiEvent.ShowError(MR.string.error_unknown))
            }
        }
    }
}
