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

    // Also lists connected identities that have no saved contact record.
    private val _contacts = MutableStateFlow<List<ContactUiModel>>(emptyList())
    val contacts: StateFlow<List<ContactUiModel>> = _contacts.asStateFlow()

    private val _savedContactIdentities = MutableStateFlow<Set<OdinId>>(emptySet())
    val savedContactIdentities: StateFlow<Set<OdinId>> = _savedContactIdentities.asStateFlow()

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
                val savedIds = contacts.mapTo(HashSet()) { it.odinId }
                val unsaved = connectionState
                    .unsavedConnections(savedIds.mapTo(HashSet()) { it.domainName })
                    .map { ContactUiModel.fallbackFor(it) }
                savedIds to (contacts + unsaved).map { it.withConnection(connectionState) }
            }.collect { (savedIds, merged) ->
                _savedContactIdentities.value = savedIds
                // Publish lookups before the list so an observer of contacts never resolves against a stale map.
                contactByOdinId.value = merged.associateBy { it.odinId }
                _contacts.value = merged
            }
        }
    }

    private fun ContactUiModel.withConnection(connectionState: ConnectionState): ContactUiModel {
        val connection = connectionState.map[odinId]

        val state = when {
            !connectionState.isLoaded -> ContactConnectionState.Unknown
            connection == null -> ContactConnectionState.NotConnected
            connection.status == ConnectionStatus.Blocked -> ContactConnectionState.Blocked
            connection.status == ConnectionStatus.Connected -> ContactConnectionState.Connected
            connection.status == ConnectionStatus.None -> ContactConnectionState.Pending
            else -> ContactConnectionState.Unknown
        }

        return copy(
            connection = connection,
            connectionState = state
        )
    }

    /**
     * Resolves [odinId]'s row in [contacts], or an identity-only fallback (domain name,
     * domain-derived initials, canonical public-image URL) when none exists — never null,
     * never blank avatar fields.
     */
    fun resolveByOdinId(odinId: OdinId): ContactUiModel =
        contactByOdinId.value[odinId] ?: ContactUiModel.fallbackFor(odinId)
}