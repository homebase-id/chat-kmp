package id.homebase.chat.services.convo

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.query.QueryBatchCursor
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.BatchResult
import id.homebase.api.common.OdinId
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.QueryBatch
import id.homebase.chat.data.ContactUiModel
import id.homebase.chat.services.ChatProtocol
import id.homebase.core.config.contactTargetDrive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable


class ContactService(
    private val credentialsManager: CredentialsManager,
    private val dbm: DatabaseManager,
    private val eventBus: EventBus,
    private val scope: CoroutineScope
) {

    private val contactDrive = contactTargetDrive.alias
    private val _contacts = MutableStateFlow<List<ContactUiModel>>(emptyList())

    private val contactByOdinId =
        MutableStateFlow<Map<OdinId, ContactUiModel>>(emptyMap())

    val contacts: StateFlow<List<ContactUiModel>> = _contacts.asStateFlow()

    init {
        scope.launch {
            refresh() // call once for good measure

            eventBus.events.collect { event ->
                if (event is BackendEvent.DriveEvent.Completed &&
                    event.driveId == contactDrive
                ) {
                    refresh()
                }
            }
        }
        start()
    }

    fun start() {
        scope.launch {
            refresh()
        }
    }

    fun resolveByOdinId(odinId: OdinId): ContactUiModel? {
        return contactByOdinId.value[odinId]
    }

    private suspend fun refresh() {
        val result = fetchContacts()
        _contacts.value = result.records
        contactByOdinId.value =
            result.records.associateBy { it.odinId }
    }


    suspend fun fetchContacts(
        limit: Int = 1000,
        cursor: QueryBatchCursor? = null
    ): BatchResult<ContactUiModel> {

        val c = credentialsManager.requireActiveCredentials()
        val queryBatch = QueryBatch(c.getIdentityId())

        val result =
            queryBatch.queryBatchAsync(
                dbm = dbm,
                driveId = contactDrive,
                noOfItems = limit,
                cursor = cursor,
                sortOrder = QueryBatchSortOrder.NewestFirst,
                sortField = QueryBatchSortField.CreatedDate,
                fileSystemType = 0,
                filetypesAnyOf = listOf(ChatProtocol.ContactFileType)
            )

        return BatchResult(
            records = result.records.mapNotNull { mapToContact(it) },
            hasMoreRows = result.hasMoreRows,
            cursor = result.cursor
        )
    }

    private suspend fun mapToContact(header: HomebaseFile): ContactUiModel? {
        val metadata = header.fileMetadata
        val appData = metadata.appData

        val uid = appData.uniqueId
        if (uid == null) {
            Logger.e("Contact found with null uniqueId")
            return null
        }

        val content = appData.content ?: ""
        val parsedContact = OdinSystemSerializer.deserialize<ContactServerFile>(content)

        return ContactUiModel(
            id = uid,
            odinId = parsedContact.odinId
                ?: throw IllegalStateException("why is the odin id missing?"),
            name = parsedContact.name.displayName ?: parsedContact.odinId.domainName,
            avatarInitials = parsedContact.name.initials(),
            avatarUrl = "https://${parsedContact.odinId}/pub/image"
        )
    }
}


@Serializable
data class ContactServerFile(
    val odinId: OdinId?,
    val name: ContactName,
    val source: String?, // 'contact' | 'public' | 'user';

    val location: ContactLocation? = null,
    val phone: ContactPhone? = null,
    val email: ContactEmail? = null,
    val birthday: ContactBirthday? = null
)

@Serializable
data class ContactName(
    val displayName: String?,
    val givenName: String?,
    val additionalName: String?,
    val surname: String?
) {

    fun initials(): String {
        val first =
            givenName
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.firstOrNull()

        val last =
            surname
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.firstOrNull()

        if (first != null && last != null) {
            return "${first}${last}".uppercase()
        }

        // Fallback: try display name tokens
        if (displayName == null) {
            return "?"
        }

        val tokens =
            displayName
                .trim()
                .split("\\s+".toRegex())
                .filter { it.isNotEmpty() }

        return when {
            tokens.size >= 2 ->
                "${tokens.first().first()}${tokens.last().first()}".uppercase()

            tokens.size == 1 ->
                tokens.first().first().uppercaseChar().toString()

            else ->
                "?"
        }
    }
}


@Serializable
data class ContactLocation(
    val city: String?,
    val country: String?
)

@Serializable
data class ContactPhone(
    val number: String?
)

@Serializable
data class ContactEmail(
    val email: String?
)


@Serializable
data class ContactBirthday(
    val date: String?
)




