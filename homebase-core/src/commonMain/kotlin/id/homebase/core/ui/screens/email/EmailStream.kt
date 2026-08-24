@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.email

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.QueryBatch
import id.homebase.core.config.emailLabeledDrive
import id.homebase.core.ui.screens.email.model.EmailCredential
import id.homebase.core.ui.screens.email.model.EmailFileTypes
import id.homebase.core.ui.screens.email.model.EmailKeyRef
import id.homebase.core.ui.screens.email.model.toEmailCredential
import id.homebase.core.ui.screens.email.model.toEmailCurrentKey
import id.homebase.core.ui.screens.email.model.toEmailKeyMaterial
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val TAG = "EmailStream"

/**
 * What is on the email drive: the keyrings the server wrote, which one is current, and the
 * credentials this app recorded.
 *
 * Reads come from the local index — the sync pipeline decrypts appData.content before storing —
 * so this works offline and never blocks the UI on HTTP.
 */
class EmailStream(
    private val databaseManager: DatabaseManager,
    private val credentialsManager: CredentialsManager,
    private val eventBus: EventBus,
    private val scope: CoroutineScope,
) {
    private val driveId = emailLabeledDrive.drive.alias

    private val _keys = MutableStateFlow<List<EmailKeyRef>>(emptyList())
    val keys: StateFlow<List<EmailKeyRef>> = _keys.asStateFlow()

    private val _currentKeyFileId = MutableStateFlow<Uuid?>(null)
    val currentKeyFileId: StateFlow<Uuid?> = _currentKeyFileId.asStateFlow()

    private val _credentials = MutableStateFlow<List<EmailCredential>>(emptyList())
    val credentials: StateFlow<List<EmailCredential>> = _credentials.asStateFlow()

    private val _isLoaded = MutableStateFlow(false)
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    private var started = false

    init {
        scope.launch { observeEvents() }
    }

    /**
     * Cold-load from the local DB.
     *
     * Idempotent and also called from the ViewModel, because AppModule documents that
     * onPostAuthenticated "is deferred and frequently never runs on a warm relaunch" — relying on
     * it alone leaves the screen empty after reopening the app.
     */
    fun start() {
        if (started) return
        started = true
        scope.launch { loadAll() }
    }

    fun reset() {
        started = false
        _keys.value = emptyList()
        _currentKeyFileId.value = null
        _credentials.value = emptyList()
        _isLoaded.value = false
    }

    suspend fun loadAll() {
        val creds = credentialsManager.getActiveCredentials() ?: run {
            _isLoaded.value = true
            return
        }

        try {
            val result = QueryBatch(creds.getIdentityId()).queryBatchAsync(
                dbm = databaseManager,
                driveId = driveId,
                noOfItems = 500,
                sortOrder = QueryBatchSortOrder.NewestFirst,
                sortField = QueryBatchSortField.CreatedDate,
                fileSystemType = FileSystemType.Standard.value,
                filetypesAnyOf = listOf(
                    EmailFileTypes.KEY_MATERIAL,
                    EmailFileTypes.CURRENT_KEY_POINTER,
                    EmailFileTypes.APP_PASSWORD_CREDENTIAL,
                ),
            )

            val keys = mutableListOf<EmailKeyRef>()
            val credentials = mutableListOf<EmailCredential>()
            var currentKey: Uuid? = null

            for (file in result.records) {
                when (file.fileMetadata.appData.fileType) {
                    EmailFileTypes.KEY_MATERIAL -> file.toEmailKeyMaterial()?.let { content ->
                        val uniqueId = file.fileMetadata.appData.uniqueId ?: return@let
                        keys += EmailKeyRef(
                            uniqueId = uniqueId,
                            fingerprintHex = content.fingerprintHex,
                            userId = content.userId,
                            createdUtc = content.createdUtc,
                            secretKeyArmored = content.secretKeyArmored,
                            publicCertificateArmored = content.publicCertificateArmored,
                        )
                    }

                    EmailFileTypes.CURRENT_KEY_POINTER ->
                        currentKey = file.toEmailCurrentKey()?.keyFileUniqueId ?: currentKey

                    EmailFileTypes.APP_PASSWORD_CREDENTIAL -> file.toEmailCredential()?.let { content ->
                        credentials += EmailCredential(
                            fileId = file.fileId,
                            id = content.id,
                            label = content.label,
                            secret = content.secret,
                            emailAddress = content.emailAddress,
                            createdUtc = content.createdUtc,
                        )
                    }
                }
            }

            _keys.value = keys.sortedByDescending { it.createdUtc }
            _currentKeyFileId.value = currentKey
            _credentials.value = credentials.sortedByDescending { it.createdUtc }
        } catch (e: Exception) {
            Logger.e(e, TAG) { "Failed to load email drive contents" }
        } finally {
            _isLoaded.value = true
        }
    }

    private suspend fun observeEvents() {
        eventBus.events.collect { event ->
            when (event) {
                // Live push, and the optimistic writer's own signal.
                is BackendEvent.DataEvent.BatchReceived ->
                    if (event.driveId == driveId) loadAll()

                // Bulk sync writes rows without per-batch events, so reconcile when a round that
                // actually wrote something ends. Gated on totalCount, NOT on result == Success:
                // an aborted round still wrote the batches it finished.
                is BackendEvent.DriveEvent.Stopped ->
                    if (event.driveId == driveId && event.totalCount > 0) loadAll()

                is BackendEvent.OutboxEvent.ItemCompleted ->
                    if (event.driveId == driveId) loadAll()

                is BackendEvent.OutboxEvent.ItemFailed ->
                    if (event.driveId == driveId) loadAll()

                else -> {}
            }
        }
    }
}
