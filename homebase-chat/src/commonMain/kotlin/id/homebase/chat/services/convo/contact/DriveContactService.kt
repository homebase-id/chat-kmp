package id.homebase.chat.services.convo.contact

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.files.ThumbnailFile
import id.homebase.api.client.drives.query.QueryBatchCursor
import id.homebase.api.client.drives.upload.CreateFileResult
import id.homebase.api.client.drives.upload.FileUpdateInstructionSet
import id.homebase.api.client.drives.upload.UpdateFileByUniqueIdRequest
import id.homebase.api.client.drives.upload.UpdateLocale
import id.homebase.api.client.drives.upload.UpdateManifest
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.client.drives.upload.DriveUploadProvider
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.client.identity.PublicIdentityRepository
import id.homebase.api.common.BatchResult
import id.homebase.api.common.OdinId
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.crypto.Md5
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.image.createThumbnails
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.QueryBatch
import id.homebase.chat.data.ContactUiModel
import id.homebase.chat.services.ChatProtocol
import id.homebase.core.config.contactTargetDrive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class DriveContactService(
    private val credentialsManager: CredentialsManager,
    private val dbm: DatabaseManager,
    private val eventBus: EventBus,
    private val scope: CoroutineScope,
    private val driveUploadProvider: DriveUploadProvider,
    private val publicIdentityRepository: PublicIdentityRepository,
    private val fileOperationsProvider: FileOperationsProvider,
) {

    companion object {
        private const val TAG = "DriveContactService"
    }

    private val contactDrive = contactTargetDrive.alias
    private val _contacts = MutableStateFlow<List<ContactUiModel>>(emptyList())

    private val contactByOdinId =
        MutableStateFlow<Map<OdinId, ContactUiModel>>(emptyMap())

    val contacts: StateFlow<List<ContactUiModel>> = _contacts.asStateFlow()

    init {
        scope.launch {
            eventBus.events.collect { event ->
                if (event is BackendEvent.DriveEvent.Stopped &&
                    event.result is BackendEvent.DriveResult.Success &&
                    event.driveId == contactDrive
                ) {
                    refresh()
                }
            }
        }
    }

    private var startJob: Job? = null

    fun start() {
        if (startJob?.isActive == true) return
        startJob = scope.launch {
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

    /**
     * Persists a contact to the owner's encrypted contact drive. Deduplicates by deriving the
     * uniqueId deterministically from the contact's odinId (MD5-GUID), so repeated calls for the
     * same identity update the existing record instead of creating a duplicate.
     *
     * Mirrors the TypeScript `saveContact` flow: embeds the JSON in the file header when it
     * fits under [ChatProtocol.MaxHeaderContentBytes]; otherwise spills into a
     * [ChatProtocol.DefaultPayloadKey] payload. If the contact carries a base64 image it's
     * encrypted into a [ContactProtocol.ProfileImageKey] payload with thumbnails.
     */
    @OptIn(ExperimentalEncodingApi::class)
    suspend fun saveContact(contact: ContactServerFile): CreateFileResult? {
        val odinId = contact.odinId
            ?: throw IllegalArgumentException("Contact is missing odinId")

        val uniqueId = Md5.toGuidId(odinId.toString().lowercase())

        val credentials = credentialsManager.requireActiveCredentials()
        val existing = dbm.driveMainIndex.selectHomebaseFileByUnique(
            credentials.getIdentityId(), contactDrive, uniqueId
        )

        val keyHeader = existing?.keyHeader?.aesKey?.let { aesKey ->
            KeyHeader(iv = ByteArrayUtil.getRndByteArray(16), aesKey = aesKey)
        } ?: KeyHeader.newRandom16()

        val payloads = mutableListOf<PayloadFile>()
        val thumbnails = mutableListOf<ThumbnailFile>()
        var previewThumb: id.homebase.api.client.drives.upload.EmbeddedThumb? = null

        // Strip image from header content; it travels as its own payload.
        val image = contact.image
        val contactForHeader = contact.copy(image = null)

        if (image != null) {
            val imageBytes = Base64.decode(image.content)

            val (_, tinyThumb, generatedThumbs) =
                createThumbnails(imageBytes, ContactProtocol.ProfileImageKey)
            previewThumb = tinyThumb

            val payloadKeyHeader = KeyHeader(
                iv = ByteArrayUtil.getRndByteArray(16),
                aesKey = keyHeader.aesKey
            )
            val encryptedImageBytes = payloadKeyHeader.encryptDataAes(imageBytes)
            val encryptedImagePath = fileOperationsProvider.writeBytesToTempFile(
                bytes = encryptedImageBytes,
                prefix = "contact_img",
                suffix = ".enc"
            )

            payloads += PayloadFile(
                key = ContactProtocol.ProfileImageKey,
                filePath = encryptedImagePath,
                contentType = image.contentType,
                iv = payloadKeyHeader.iv,
                isPreEncrypted = true
            )

            thumbnails += generatedThumbs.map { thumb ->
                thumb.copy(
                    thumbnailBytes = payloadKeyHeader.encryptDataAes(thumb.thumbnailBytes)
                )
            }
        }

        val contentJson = OdinSystemSerializer.serialize(contactForHeader)
        val canEmbedInHeader = ContactSizer.shouldEmbedInHeader(contentJson)

        if (!canEmbedInHeader) {
            val jsonKeyHeader = KeyHeader(
                iv = ByteArrayUtil.getRndByteArray(16),
                aesKey = keyHeader.aesKey
            )
            val encryptedJsonBytes =
                jsonKeyHeader.encryptDataAes(contentJson.encodeToByteArray())
            val encryptedJsonPath = fileOperationsProvider.writeBytesToTempFile(
                bytes = encryptedJsonBytes,
                prefix = "contact_body",
                suffix = ".enc"
            )

            payloads += PayloadFile(
                key = ChatProtocol.DefaultPayloadKey,
                filePath = encryptedJsonPath,
                contentType = "application/json",
                iv = jsonKeyHeader.iv,
                isPreEncrypted = true
            )
        }

        val metadata = UploadFileMetadata(
            allowDistribution = false,
            isEncrypted = true,
            accessControlList = AccessControlList(requiredSecurityGroup = "Owner"),
            versionTag = existing?.fileMetadata?.versionTag,
            appData = UploadAppFileMetaData(
                uniqueId = uniqueId,
                tags = listOf(uniqueId),
                fileType = ChatProtocol.ContactFileType,
                content = if (canEmbedInHeader) contentJson else null,
                previewThumbnail = previewThumb
            )
        )

        Logger.d(tag = TAG) {
            "saveContact odinId=$odinId uniqueId=$uniqueId existing=${existing != null} " +
                    "hasImage=${image != null} embedInHeader=$canEmbedInHeader"
        }

        return if (existing != null) {
            val result = driveUploadProvider.updateFileByUniqueId(
                UpdateFileByUniqueIdRequest(
                    driveId = contactDrive,
                    uniqueId = uniqueId,
                    keyHeader = keyHeader,
                    instructions = FileUpdateInstructionSet(
                        transferIv = ByteArrayUtil.getRndByteArray(16),
                        locale = UpdateLocale.Local,
                        recipients = emptyList(),
                        manifest = UpdateManifest.build(
                            payloads = payloads,
                            toDeletePayloads = null,
                            thumbnails = thumbnails,
                            generatePayloadIv = false
                        )
                    ),
                    metadata = metadata.encryptContent(keyHeader),
                    payloads = payloads,
                    thumbnails = thumbnails
                )
            ) ?: return null

            CreateFileResult(
                fileId = existing.fileId,
                driveId = contactDrive,
                globalTransitId = null,
                recipientStatus = result.recipientStatus,
                newVersionTag = result.newVersionTag
            )
        } else {
            driveUploadProvider.uploadFile(
                UploadFileRequest(
                    driveId = contactDrive,
                    keyHeader = keyHeader,
                    metadata = metadata.encryptContent(keyHeader),
                    payloads = payloads,
                    thumbnails = thumbnails,
                    fileSystemType = FileSystemType.Standard
                )
            )
        }
    }

    /**
     * Best-effort: fetches the other identity's public profile via `sitedata.json` and persists
     * it to the contact drive. Swallows errors so callers (typically the connection-request
     * flow) are never interrupted by a contact-save failure.
     */
    suspend fun saveContactForOdinId(odinId: OdinId) {
        try {
            val identity = publicIdentityRepository.resolve(odinId) ?: return

            saveContact(
                ContactServerFile(
                    odinId = odinId,
                    name = ContactName(
                        displayName = identity.displayName,
                        givenName = identity.firstName,
                        additionalName = null,
                        surname = identity.surName
                    ),
                    source = "public"
                )
            )
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(throwable = e, tag = TAG) { "saveContactForOdinId failed for $odinId" }
        }
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
        val parsedContact = try {
            OdinSystemSerializer.deserialize<ContactServerFile>(content)
        } catch (e: Exception) {
            Logger.e(e) { "Failed to deserialize contact content for uid=$uid: ${content.take(200)}" }
            return null
        }

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
