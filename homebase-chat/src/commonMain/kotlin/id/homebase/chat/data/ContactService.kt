package id.homebase.chat.data

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.query.QueryBatchCursor
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.BatchResult
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.QueryBatch
import id.homebase.core.config.contactTargetDrive
import id.homebase.homebasekmppoc.prototype.lib.serialization.OdinSystemSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

const val CONTACT_FILE_TYPE = 100

class ContactService(
    private val credentialsManager: CredentialsManager,
    private val dbm: DatabaseManager,
    private val eventBus: EventBus,
    private val scope: CoroutineScope
) {

    private val contactDrive = contactTargetDrive.alias
    private val _contacts = MutableStateFlow<List<ContactUiModel>>(emptyList())

    val contacts: StateFlow<List<ContactUiModel>> = _contacts.asStateFlow()

    init {
        scope.launch {
            eventBus.events.collect { event ->
                if (event is BackendEvent.DriveEvent.Completed &&
                    event.driveId == contactDrive
                ) {
                    refresh()
                }
            }
        }
    }

    fun start() {
        scope.launch {
            refresh()
        }
    }

    private suspend fun refresh() {
        val result = fetchContacts()
        _contacts.value = result.records
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
                filetypesAnyOf = listOf(CONTACT_FILE_TYPE)
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
            name = parsedContact.name.displayName,
            avatarInitials = "TD",
            avatarUrl = ""
        )
    }
}


@Serializable
data class ContactServerFile(
    val odinId: String?,
    val name: ContactName,
    val source: String?, // 'contact' | 'public' | 'user';

    val location: ContactLocation? = null,
    val phone: ContactPhone? = null,
    val email: ContactEmail? = null,
    val birthday: ContactBirthday? = null
)

@Serializable
data class ContactName(
    val displayName: String,
    val givenName: String?,
    val additionalName: String?,
    val surname: String?
)

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




