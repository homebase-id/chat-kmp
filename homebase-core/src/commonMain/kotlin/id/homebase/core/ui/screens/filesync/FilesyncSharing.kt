package id.homebase.core.ui.screens.filesync

import id.homebase.api.client.ClientException
import id.homebase.api.client.ForbiddenException
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.OdinApiException
import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.connections.CircleDesignation
import id.homebase.api.client.connections.CircleGrantOn
import id.homebase.api.client.connections.CircleShareKeyProvider
import id.homebase.api.client.connections.ConnectionNetworkProvider
import id.homebase.api.client.connections.CreateAppCircleRequest
import id.homebase.api.client.connections.DriveGrantRequest
import id.homebase.api.client.connections.PermissionedDrive
import id.homebase.api.client.connections.SealedMemberShareKey
import id.homebase.api.client.connections.SealedMemberShareKeys
import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.drives.ExternalFileKey
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.QueryBatchRequest
import id.homebase.api.client.drives.QueryBatchResultOptionsRequest
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.drives.files.SecurityGroupType
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.drives.query.FileQueryParams
import id.homebase.api.client.drives.shares.DriveShareProvider
import id.homebase.api.client.drives.shares.ShareRequest
import id.homebase.api.client.drives.shares.ShareRow
import id.homebase.api.client.drives.upload.DriveUploadProvider
import id.homebase.api.client.drives.upload.TransitOptions
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.client.peer.PeerDriveQueryProvider
import id.homebase.api.common.OdinId
import id.homebase.api.common.toSecureByteArray
import id.homebase.api.crypto.HashUtil
import id.homebase.api.crypto.ShareKeys
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.serialization.UuidSerializer
import id.homebase.api.youauth.DrivePermission
import id.homebase.api.youauth.DrivePermissionSet
import id.homebase.api.youauth.ZkKeyStore
import id.homebase.api.util.compareStringUuId
import id.homebase.core.config.filesyncAppRegistration
import id.homebase.core.config.filesyncLabeledDrive
import id.homebase.core.filesync.FilesyncProtocol
import io.ktor.client.HttpClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** What a person may do with a file shared with them. */
enum class FilesyncAccess(val permissions: List<DrivePermission>) {
    View(listOf(DrivePermission.Read, DrivePermission.React, DrivePermission.Comment)),
    Edit(listOf(DrivePermission.Read, DrivePermission.Write, DrivePermission.React, DrivePermission.Comment)),
}

data class FilesyncShareRecipient(val odinId: OdinId, val access: FilesyncAccess)

/** "[odinId] has files for you", left on the recipient's Filesync drive. */
@Serializable
data class FilesyncMarker(val odinId: String, @Serializable(with = UuidSerializer::class) val driveAlias: Uuid)

sealed class FilesyncSharingException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Sharing writes as Filesync, which this device hasn't signed in to. */
    class NotSignedIn : FilesyncSharingException("Not signed in to Filesync")

    /** [recipient] hasn't turned sharing on, so nothing can be left for them yet. */
    class RecipientNotReady(val recipient: OdinId, cause: Throwable? = null) :
        FilesyncSharingException("$recipient can't receive shared files yet", cause)
}

/**
 * Sharing files with one person: each person is a circle of one (an individual circle) whose key, locked with
 * Filesync's app key, locks the file keys of the files shared with them, and a sealed copy of it is theirs. Circles,
 * their keys and shares are Filesync's to write ([FilesyncSession]); everything else goes with the app's own token:
 * the recipient's public key, the marker left on their drive, and reading what others share.
 */
class FilesyncSharing(
    httpClient: HttpClient,
    private val appCredentials: CredentialsManager,
    private val connections: ConnectionNetworkProvider,
    private val circleShareKeys: CircleShareKeyProvider,
    private val peer: PeerDriveQueryProvider,
    private val query: DriveQueryProvider,
    private val upload: DriveUploadProvider,
) {
    private val asFilesync = ConnectionNetworkProvider(httpClient, FilesyncSession.credentials)
    private val filesyncKeys = CircleShareKeyProvider(httpClient, FilesyncSession.credentials)
    private val filesyncShares = DriveShareProvider(httpClient, FilesyncSession.credentials)

    private class OwnCircleKey(val locked: ByteArray, val key: ByteArray)

    private val ownKeys = mutableMapOf<Uuid, OwnCircleKey>()
    private val memberKeys = mutableMapOf<Pair<OdinId, Uuid>, ByteArray>()
    private val keys = Mutex()

    /** Lets every connection leave this identity a marker: a circle all connections join, granting Write on the drive alone. */
    suspend fun openToMarkers() {
        requireSignedIn()
        val circleId = asFilesync.createCircle(
            CreateAppCircleRequest(
                name = "Filesync sharers",
                slug = FilesyncProtocol.SharersCircleSlug,
                description = "People who can tell you they shared files with you",
                driveGrants = listOf(DriveGrantRequest(PermissionedDrive(filesyncLabeledDrive.drive, DrivePermissionSet(listOf(DrivePermission.Write))))),
                grantOn = CircleGrantOn.Connect,
            )
        )
        val candidates = asFilesync.getEnrollmentCandidatesForCircle(circleId).candidates.map { it.odinId }
        candidates.chunked(ADD_MANY_LIMIT).forEach { asFilesync.addManyToCircle(circleId.toString(), it.map(OdinId::domainName)) }
    }

    /** What sharing may offer: whatever the circle every recipient joins grants on the drive. */
    suspend fun offeredAccess(): List<FilesyncAccess> {
        val granted = recipientsCircleGrant()
        return FilesyncAccess.entries.filter { granted.containsAll(it.permissions) }
    }

    /** Shares the [files] with [recipient], then leaves them a marker, if they have none from this identity yet. */
    suspend fun share(files: List<FilesyncHandle>, recipient: OdinId, access: FilesyncAccess) {
        requireSignedIn()
        val self = requireNotNull(appCredentials.getActiveCredentials()).domain
        val point = recipientPoint(recipient)

        asFilesync.addManyToCircle(recipientsCircle().toString(), listOf(recipient.domainName))
        val circleId = asFilesync.createCircle(
            CreateAppCircleRequest(name = recipient.domainName, slug = individualSlug(recipient), designation = CircleDesignation.Individual)
        )
        asFilesync.addManyToCircle(circleId.toString(), listOf(recipient.domainName))
        val own = ownCircleKey(circleId)
        if (filesyncKeys.getUnsealedMembers(circleId).any { it == recipient }) {
            val sealed = ShareKeys.sealCircleKey(own.key, point, circleId, recipient)
            filesyncKeys.setMemberKeys(circleId, SealedMemberShareKeys(own.locked, listOf(SealedMemberShareKey(recipient.domainName, sealed))))
        }

        val permissions = DrivePermission.combine(access.permissions)
        files.chunked(DriveShareProvider.MAX_SHARES_PER_REQUEST).forEach { chunk ->
            filesyncShares.share(
                FilesyncProtocol.DriveAlias,
                chunk.map { file ->
                    val locked = ShareKeys.lockFileKey(own.key, file.keyHeader.aesKey.toByteArray(), FilesyncProtocol.DriveAlias, file.fileId, circleId)
                    ShareRequest(file.fileId, circleId, permissions, lockedFileKey = locked)
                },
            )
        }
        leaveMarker(self, recipient, point)
    }

    /** Who [fileId] is shared with now, withdrawn shares left out. */
    suspend fun sharedWith(fileId: Uuid): List<FilesyncShareRecipient> {
        requireSignedIn()
        val people = individualCircles()
        return filesyncShares.shares(FilesyncProtocol.DriveAlias, fileId).mapNotNull { row ->
            val person = people[row.circleId] ?: return@mapNotNull null
            row.access()?.let { FilesyncShareRecipient(person, it) }
        }
    }

    suspend fun withdraw(fileId: Uuid, recipient: OdinId) {
        requireSignedIn()
        val circleId = individualCircles().entries.firstOrNull { it.value == recipient }?.key ?: return
        filesyncShares.withdraw(FilesyncProtocol.DriveAlias, fileId, circleId)
    }

    /** The people who left this identity a marker, each trusted only when it was sent by the identity it names. */
    suspend fun sharers(): List<OdinId> =
        query.queryBatch(
            FilesyncProtocol.DriveAlias,
            QueryBatchRequest(
                queryParams = FileQueryParams(fileType = listOf(FilesyncProtocol.MarkerFileType)),
                resultOptionsRequest = QueryBatchResultOptionsRequest(maxRecords = PAGE, includeMetadataHeader = true),
            ),
        ).searchResults.filter { it.fileState == FileState.Active }.mapNotNull { file ->
            val marker = file.fileMetadata.appData.content
                ?.let { runCatching { OdinSystemSerializer.deserialize<FilesyncMarker>(it) }.getOrNull() } ?: return@mapNotNull null
            file.fileMetadata.senderOdinId?.takeIf { it.domainName.equals(marker.odinId, ignoreCase = true) }
        }.distinct()

    /** The files [owner] shares with this identity, opened with the keys their shares unlock. */
    suspend fun sharedBy(owner: OdinId): List<Pair<FilesyncNode, FilesyncHandle>> {
        val self = requireNotNull(appCredentials.getActiveCredentials()).domain
        val byShare = ExternalFileKey { fileId, shares, locked ->
            val circleId = shares.firstOrNull()?.circleId ?: error("file $fileId came with no share")
            ShareKeys.openFileKey(memberCircleKey(owner, circleId, self), locked, FilesyncProtocol.DriveAlias, fileId, circleId).toSecureByteArray()
        }
        return query.queryBatch(
            FilesyncProtocol.DriveAlias,
            QueryBatchRequest(
                queryParams = FileQueryParams(fileType = listOf(FilesyncProtocol.FileFileType)),
                resultOptionsRequest = QueryBatchResultOptionsRequest(maxRecords = PAGE, includeMetadataHeader = true),
            ),
            ownerOdinId = owner,
            externalFileKey = byShare,
        ).searchResults.filter { it.fileState == FileState.Active }.mapNotNull { it.toFilesyncNode() }
    }

    private suspend fun leaveMarker(self: OdinId, recipient: OdinId, point: ByteArray) {
        val keyHeader = KeyHeader.newRandom16()
        val metadata = UploadFileMetadata(
            allowDistribution = true,
            isEncrypted = true,
            accessControlList = AccessControlList(requiredSecurityGroup = SecurityGroupType.Owner.value),
            appData = UploadAppFileMetaData(
                uniqueId = markerId(self, recipient),
                fileType = FilesyncProtocol.MarkerFileType,
                dataType = 0,
                userDate = Clock.System.now().toEpochMilliseconds(),
                content = OdinSystemSerializer.serialize(FilesyncMarker(self.domainName, FilesyncProtocol.DriveAlias)),
            ),
        ).encryptContent(keyHeader)
        upload.uploadFile(
            UploadFileRequest(
                driveId = SystemDriveConstants.transientTempDrive.alias,
                keyHeader = keyHeader,
                metadata = metadata,
                transitOptions = TransitOptions(recipients = listOf(recipient), isTransient = true, remoteTargetDrive = filesyncLabeledDrive.drive),
                sealedTo = point,
            )
        )
    }

    private suspend fun recipientPoint(recipient: OdinId): ByteArray {
        val key = try {
            peer.drivePublicKey(recipient, FilesyncProtocol.AppSlug, FilesyncProtocol.DriveSlug)
        } catch (e: OdinApiException) {
            // No drive yet (400), or no Write on it (403) until their sharers circle grants it: sharing is off there
            if (e !is ClientException && e !is ForbiddenException) throw e
            throw FilesyncSharingException.RecipientNotReady(recipient, e)
        }
        // Its server and members hold a collaborative drive's key: sealing to it would share with them too.
        if (!key.isZeroKnowledge || key.sharingModel == "collaborative") throw FilesyncSharingException.RecipientNotReady(recipient)
        return ShareKeys.pointOfJwk(key.publicKeyJwk)
    }

    private suspend fun ownCircleKey(circleId: Uuid): OwnCircleKey = keys.withLock {
        ownKeys[circleId]?.let { return@withLock it }
        val appKey = FilesyncSession.appKey ?: throw FilesyncSharingException.NotSignedIn()
        val locked = filesyncKeys.getShareKey(circleId) ?: try {
            ShareKeys.lockCircleKey(appKey, ShareKeys.newCircleKey(), circleId).also { filesyncKeys.setShareKey(circleId, it) }
        } catch (e: ClientException) {
            // Another device set it first: use theirs.
            if (e.errorCode != OdinClientErrorCode.CircleShareKeyAlreadySet) throw e
            checkNotNull(filesyncKeys.getShareKey(circleId))
        }
        OwnCircleKey(locked, ShareKeys.openCircleKey(appKey, locked, circleId)).also { ownKeys[circleId] = it }
    }

    private suspend fun memberCircleKey(owner: OdinId, circleId: Uuid, self: OdinId): ByteArray = keys.withLock {
        memberKeys[owner to circleId]?.let { return@withLock it }
        val sealed = circleShareKeys.getMySealedKey(owner, circleId) ?: error("$owner hasn't sealed circle $circleId's key to this identity")
        val writeOnlyKey = ZkKeyStore.writeOnlyPrivateKey(FilesyncProtocol.DriveAlias) ?: error("no write-only key for the Filesync drive")
        ShareKeys.unsealCircleKey(sealed, writeOnlyKey, circleId, self).also { memberKeys[owner to circleId] = it }
    }

    private suspend fun recipientsCircle(): Uuid {
        val declared = filesyncAppRegistration.circles.single { it.slug == FilesyncProtocol.CircleSlug }
        return asFilesync.createCircle(
            CreateAppCircleRequest(
                name = declared.name,
                slug = declared.slug,
                description = declared.description,
                emoji = declared.emoji,
                driveGrants = declared.drives.map { DriveGrantRequest(PermissionedDrive(filesyncLabeledDrive.drive, DrivePermissionSet(it.permissions))) },
            )
        )
    }

    private suspend fun recipientsCircleGrant(): List<DrivePermission> =
        connections.getCirclesWithMembers()
            .firstOrNull { it.circle.slug == FilesyncProtocol.CircleSlug && it.circle.appId == filesyncAppId }
            ?.circle?.driveGrants.orEmpty()
            .mapNotNull { it.permissionedDrive }
            .filter { compareStringUuId(it.drive?.alias.orEmpty(), FilesyncProtocol.DriveAlias.toString()) }
            .flatMap { DrivePermission.fromNames(it.permission.orEmpty()) }

    private suspend fun individualCircles(): Map<Uuid, OdinId> =
        connections.getCirclesWithMembers(withIndividual = true)
            .filter { it.circle.designation == CircleDesignation.Individual && it.circle.appId == filesyncAppId }
            .mapNotNull { c -> c.members.singleOrNull()?.let { Uuid.parse(c.circle.id) to it } }
            .toMap()

    private fun requireSignedIn() {
        if (!FilesyncSession.signedIn.value) throw FilesyncSharingException.NotSignedIn()
    }

    private fun ShareRow.access(): FilesyncAccess? = when {
        DrivePermission.Read !in permissions.values -> null
        DrivePermission.Write in permissions.values -> FilesyncAccess.Edit
        else -> FilesyncAccess.View
    }

    companion object {
        private const val PAGE = 1000
        private const val ADD_MANY_LIMIT = 500
        private val filesyncAppId = Uuid.parseHex(filesyncAppRegistration.appId)

        /** Every device derives the same: one marker per sharer and recipient, so a second send is refused as there. */
        fun markerId(sender: OdinId, recipient: OdinId): Uuid =
            Uuid.fromByteArray(HashUtil.sha256Sync("filesync-marker\n${sender.domainName.lowercase()}\n${recipient.domainName.lowercase()}".encodeToByteArray()).copyOf(16))

        /** `i` and 13 hex characters of the person's hash: within the 14-character slug limit, the same on every device. */
        fun individualSlug(person: OdinId): String =
            "i" + HashUtil.sha256Sync(person.domainName.lowercase().encodeToByteArray()).toHexString().take(13)
    }
}
