package id.homebase.chat.services.convo.contact

import id.homebase.api.client.connections.ConnectionStatus
import id.homebase.api.client.contacts.ContactRepository
import id.homebase.api.common.OdinId
import id.homebase.chat.data.ContactUiModel
import id.homebase.chat.data.toContactUiModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class ContactService(
    private val contactRepository: ContactRepository,
    private val connections: ConnectionService,
    private val scope: CoroutineScope
) {

    private val _contacts = MutableStateFlow<List<ContactUiModel>>(emptyList())
    val contacts: StateFlow<List<ContactUiModel>> = _contacts.asStateFlow()

    private val contactByOdinId =
        MutableStateFlow<Map<OdinId, ContactUiModel>>(emptyMap())

    private var started = false

    fun start() {
        if (started) return
        started = true

        // ContactRepository is started by the post-auth bootstrap; we only need connections here.
        connections.start()

        scope.launch {
            combine(
                contactRepository.contacts.map { list -> list.mapNotNull { it.toContactUiModel() } },
                connections.connections
            ) { contacts, connectionState ->

                contacts.map { contact ->

                    val connection = connectionState.map[contact.odinId]

                    val state = when {
                        !connectionState.isLoaded -> ContactConnectionState.Unknown
                        connection == null -> ContactConnectionState.NotConnected
                        connection.status == ConnectionStatus.Blocked -> ContactConnectionState.Blocked
                        connection.status == ConnectionStatus.Connected -> ContactConnectionState.Connected
                        connection.status == ConnectionStatus.None -> ContactConnectionState.Pending
                        else -> ContactConnectionState.Unknown
                    }

                    contact.copy(
                        connection = connection,
                        connectionState = state
                    )
                }
            }.collect { merged ->
                _contacts.value = merged
                contactByOdinId.value =
                    merged.associateBy { it.odinId }
            }
        }
    }

    /**
     * Resolves the saved contact for [odinId], or an identity-only fallback (domain name,
     * domain-derived initials, canonical public-image URL) when none exists — never null,
     * never blank avatar fields.
     */
    fun resolveByOdinId(odinId: OdinId): ContactUiModel =
        contactByOdinId.value[odinId] ?: ContactUiModel.fallbackFor(odinId)
}