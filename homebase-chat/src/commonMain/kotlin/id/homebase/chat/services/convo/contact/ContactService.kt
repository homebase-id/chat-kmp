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

    fun start() {
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
        return contactByOdinId.value[odinId]
    }
}