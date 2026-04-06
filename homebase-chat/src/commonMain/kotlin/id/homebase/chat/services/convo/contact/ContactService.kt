package id.homebase.chat.services.convo.contact

import id.homebase.api.client.connections.ConnectionStatus
import id.homebase.api.common.OdinId
import id.homebase.chat.data.ContactUiModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class ContactService(
    private val driveContacts: DriveContactService,
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

        driveContacts.start()
        connections.start()

        scope.launch {
            combine(
                driveContacts.contacts,
                connections.connections
            ) { contacts, connectionState ->

                contacts.map { contact ->

                    val connection = connectionState.map[contact.odinId]

                    val state = when {
                        !connectionState.isLoaded -> ContactConnectionState.Unknown
                        connection == null -> ContactConnectionState.NotConnected
                        connection.status == ConnectionStatus.Blocked -> ContactConnectionState.Blocked
                        connection.status == ConnectionStatus.Connected -> ContactConnectionState.Connected
                        connection.status == ConnectionStatus.Pending -> ContactConnectionState.Pending
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

    fun resolveByOdinId(odinId: OdinId): ContactUiModel? {
        return contactByOdinId.value[odinId] ?: ContactUiModel(
            id = odinId.toHashId(),
            odinId = odinId,
            name = odinId.domainName,
            avatarInitials = "",
            avatarUrl = "",
            connection = null,
            connectionState = ContactConnectionState.NotConnected
        )
    }
}