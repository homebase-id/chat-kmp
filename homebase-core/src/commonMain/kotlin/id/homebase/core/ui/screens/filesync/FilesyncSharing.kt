package id.homebase.core.ui.screens.filesync

import co.touchlab.kermit.Logger
import id.homebase.api.client.ClientException
import id.homebase.api.client.ForbiddenException
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.NotFoundException
import id.homebase.api.client.OdinApiException
import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.RetryConfig
import id.homebase.api.client.RetryResult
import id.homebase.api.client.ServerException
import id.homebase.api.client.UnauthorizedException
import id.homebase.api.client.errorCodeEnum
import id.homebase.api.client.isExistingFileWithUniqueId
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.connections.CircleDesignation
import id.homebase.api.client.connections.CircleShareKeyProvider
import id.homebase.api.client.connections.CircleWithMembers
import id.homebase.api.client.connections.ConnectionNetworkProvider
import id.homebase.api.client.connections.CreateAppCircleRequest
import id.homebase.api.client.connections.DriveGrantRequest
import id.homebase.api.client.connections.EnrollmentOutcomeKind
import id.homebase.api.client.connections.PermissionedDrive
import id.homebase.api.client.connections.SealedMemberShareKey
import id.homebase.api.client.connections.SealedMemberShareKeys
import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.drives.ExternalFileKey
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.QueryBatchRequest
import id.homebase.api.client.drives.QueryBatchResultOptionsRequest
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.files.SecurityGroupType
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.drives.query.FileQueryParams
import id.homebase.api.client.drives.shares.DriveShareProvider
import id.homebase.api.client.drives.shares.ShareRequest
import id.homebase.api.client.drives.shares.ShareRow
import id.homebase.api.client.drives.upload.DriveUploadProvider
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.client.isTransientNetworkFailure
import id.homebase.api.client.peer.PeerDriveQueryProvider
import id.homebase.api.client.withRetryResult
import id.homebase.api.common.OdinId
import id.homebase.api.common.toSecureByteArray
import id.homebase.api.crypto.HashUtil
import id.homebase.api.crypto.ShareKeys
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.serialization.UuidSerializer
import id.homebase.api.util.compareStringUuId
import id.homebase.api.youauth.DrivePermission
import id.homebase.api.youauth.DrivePermissionSet
import id.homebase.api.youauth.ZkKeyStore
import id.homebase.core.config.filesyncAppRegistration
import id.homebase.core.config.filesyncLabeledDrive
import id.homebase.core.filesync.FilesyncProtocol
import io.ktor.client.HttpClient
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/** What a person may do with a file shared with them. */
enum class FilesyncAccess(val permissions: List<DrivePermission>) {
    View(listOf(DrivePermission.Read, DrivePermission.React, DrivePermission.Comment)),
    Edit(listOf(DrivePermission.Read, DrivePermission.Write, DrivePermission.React, DrivePermission.Comment)),
}

/** How long a share lasts; [days] null never ends. */
enum class FilesyncExpiry(val days: Int?) {
    Day(1), Week(7), Month(30), Year(365), Never(null);

    fun expiresAt(nowMs: Long): Long? = days?.let { nowMs + it.days.inWholeMilliseconds }

    companion object {
        val Default = Year
    }
}

/** A share made: the circle it went to, and the members whose key waits for them to set up Filesync. */
data class FilesyncShared(val circleId: Uuid, val waiting: List<OdinId>)

/** Whom files are shared with: one person, through a circle of their own, or a named group, a circle of the app's. */
sealed interface FilesyncAudience {
    val members: List<OdinId>

    data class Person(val odinId: OdinId) : FilesyncAudience {
        override val members get() = listOf(odinId)
    }

    data class Group(val circleId: Uuid, val name: String, override val members: List<OdinId>) : FilesyncAudience
}

val FilesyncAudience.label: String
    get() = when (this) {
        is FilesyncAudience.Person -> odinId.domainName
        is FilesyncAudience.Group -> name
    }

data class FilesyncShareRecipient(val audience: FilesyncAudience, val access: FilesyncAccess, val expiresAt: Long?)

data class FilesyncSharedFile(val node: FilesyncNode, val handle: FilesyncHandle, val access: FilesyncAccess, val expiresAt: Long?)

/**
 * What a move does to sharing, worked out before it, by the node moved: the people who had it only through the folder
 * it leaves stop seeing it, and it's shared like the folder it enters.
 */
class FilesyncMoveSharing internal constructor(
    internal val stops: Map<Uuid, List<Pair<Uuid, Uuid>>>,
    internal val starts: Map<Uuid, List<ShareRequest>>,
    val losingAccess: List<OdinId>,
) {
    companion object {
        val None = FilesyncMoveSharing(emptyMap(), emptyMap(), emptyList())
    }
}

/** "[odinId] has files for you", left on the recipient's Filesync drive. */
@Serializable
data class FilesyncMarker(val odinId: String, @Serializable(with = UuidSerializer::class) val driveAlias: Uuid)

sealed class FilesyncSharingException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Sharing writes as Filesync, which this device hasn't signed in to. */
    class NotSignedIn : FilesyncSharingException("Not signed in to Filesync")

    /** [recipients] couldn't be added to the circle shared with. */
    class RecipientNotAdded(val recipients: List<OdinId>) : FilesyncSharingException("$recipients couldn't be added")

    /** [owner] hasn't reviewed this identity's connection, so it won't hand over the keys to what it shared. */
    class NotReviewedByOwner(val owner: OdinId) : FilesyncSharingException("$owner hasn't reviewed this connection")

    /** [owner]'s Filesync hasn't sealed the circle key to this identity yet: it does once this identity has a drive. */
    class NotSealedYet(val owner: OdinId) : FilesyncSharingException("$owner hasn't sealed its key here yet")
}

/**
 * Sharing files with a person (a circle of one, an individual circle) or a group (a circle of the app's): the circle's
 * key, locked with Filesync's app key, locks the file keys of the files shared with it, and each member holds a sealed
 * copy of it. Circles, their keys and shares are Filesync's to write ([FilesyncSession]); everything else goes with the
 * app's own token: the recipient's public key, this identity's own markers, and reading what others share.
 */
class FilesyncSharing(
    httpClient: HttpClient,
    private val appCredentials: CredentialsManager,
    private val connections: ConnectionNetworkProvider,
    private val circleShareKeys: CircleShareKeyProvider,
    private val peer: PeerDriveQueryProvider,
    private val query: DriveQueryProvider,
    private val uploads: DriveUploadProvider,
) {
    private val asFilesync = ConnectionNetworkProvider(httpClient, FilesyncSession.credentials)
    private val filesyncKeys = CircleShareKeyProvider(httpClient, FilesyncSession.credentials)
    private val filesyncShares = DriveShareProvider(httpClient, FilesyncSession.credentials)
    private val filesyncQuery = DriveQueryProvider(httpClient, FilesyncSession.credentials)

    private class OwnCircleKey(val locked: ByteArray, val key: ByteArray)

    private val ownKeys = mutableMapOf<Uuid, OwnCircleKey>()
    private val memberKeys = mutableMapOf<Pair<OdinId, Uuid>, ByteArray>()
    private val keys = Mutex()
    private val editing = Mutex()

    /** Signs this device out of Filesync and drops every circle key that sign-in unlocked: at logout, or once refused. */
    suspend fun signOut() {
        FilesyncSession.forget()
        keys.withLock {
            ownKeys.clear()
            memberKeys.clear()
        }
    }

    /** What sharing may offer: whatever the circle every recipient joins grants on the drive, and Write from the editors circle. */
    suspend fun offeredAccess(): List<FilesyncAccess> {
        val granted = recipientsCircleGrant() + DrivePermission.Write
        return FilesyncAccess.entries.filter { granted.containsAll(it.permissions) }
    }

    /**
     * Shares the [files] with [audience]: the circle it went to, and the members without a Filesync drive yet, whose key
     * [sealPending] seals once they have one ([FilesyncShareUpkeep]). Telling its members is the caller's.
     */
    suspend fun share(files: List<FilesyncHandle>, audience: FilesyncAudience, access: FilesyncAccess, expiresAt: Long?): FilesyncShared {
        requireSignedIn()
        val members = audience.members
        val points = readyPoints(members)

        // Their individual shares then don't depend on a group they might leave.
        enroll(recipientsCircle(), members)
        val circleId = when (audience) {
            is FilesyncAudience.Group -> audience.circleId
            is FilesyncAudience.Person -> asFilesync.createCircle(
                CreateAppCircleRequest(name = audience.odinId.domainName, slug = individualSlug(audience.odinId), designation = CircleDesignation.Individual)
            ).also { enroll(it, members) }
        }
        sealCircleKey(circleId, points)

        val permissions = DrivePermission.combine(access.permissions)
        files.chunked(DriveShareProvider.MAX_SHARES_PER_REQUEST).forEach { chunk ->
            filesyncShares.share(FilesyncProtocol.DriveAlias, chunk.map { shareRequest(it.fileId, it.keyHeader, circleId, permissions, expiresAt) })
        }
        // After the rows, so a concurrent syncEditors that read them before can't take the members out again.
        if (access == FilesyncAccess.Edit) editing.withLock { enroll(editorsCircle(), members) }
        // Sharing as View may have replaced someone's Edit.
        else editorsMayHaveChanged()
        return FilesyncShared(circleId, waiting = members.filter { it !in points })
    }

    /** Seals each circle's key to the members who have set up Filesync since they were shared with. */
    suspend fun sealPending() {
        requireSignedIn()
        val pending = audienceCircles().keys.chunked(PARALLEL).flatMap { chunk ->
            coroutineScope { chunk.map { async { it to filesyncKeys.getUnsealedMembers(it) } }.awaitAll() }
        }.filter { it.second.isNotEmpty() }
        if (pending.isEmpty()) return
        val points = readyPoints(pending.flatMap { it.second }.distinct())
        pending.forEach { (circleId, unsealed) -> sealCircleKey(circleId, points, unsealed) }
    }

    /**
     * Puts in the editors circle exactly the people a live Edit share reaches, on their own or through a group: drive
     * Write is what lets them change a file, and it also lets them add files anywhere on the drive. Every change here
     * that can end an Edit share runs it; [FilesyncShareUpkeep] runs it for the ones that expire.
     */
    suspend fun syncEditors() = editing.withLock {
        requireSignedIn()
        val circles = filesyncCircles()
        val audiences = audiencesOf(circles)
        val editingCircles = HashSet<Uuid>()
        if (audiences.isNotEmpty()) {
            filesyncQuery.forEachFilesyncPage(
                FileQueryParams(fileType = listOf(FilesyncProtocol.FolderFileType, FilesyncProtocol.FileFileType), sharedWith = audiences.keys.toList()),
                QueryBatchResultOptionsRequest(maxRecords = PAGE, includeShares = true),
            ) { page ->
                // includeShares returns only live rows.
                page.searchResults.forEach { file ->
                    file.shares.orEmpty().filter { it.circleId in audiences && it.access() == FilesyncAccess.Edit }.mapTo(editingCircles) { it.circleId }
                }
                editingCircles.size < audiences.size
            }
        }
        val editors = editingCircles.flatMapTo(HashSet()) { audiences.getValue(it).members }
        val listed = circles.firstOrNull { it.circle.slug == FilesyncProtocol.EditorsCircleSlug }
        val current = listed?.members.orEmpty().toSet()
        if (editors == current) return@withLock
        val circleId = listed?.circle?.id?.let(Uuid::parse) ?: editorsCircle()
        enroll(circleId, editors - current)
        (current - editors).forEach { asFilesync.removeFromCircle(circleId, it) }
    }

    /**
     * Makes a group called [name] of [members]: a circle of the app's granting the drive what the circle every recipient
     * joins grants. Edit's Write comes from the editors circle.
     */
    suspend fun createGroup(name: String, members: List<OdinId>): FilesyncAudience.Group {
        requireSignedIn()
        val circleId = asFilesync.createCircle(CreateAppCircleRequest(name = name, slug = groupSlug(), driveGrants = recipientsDriveGrants()))
        val refused = enroll(circleId, members)
        if (refused.isNotEmpty()) {
            asFilesync.deleteCircle(circleId, removeMembers = true)
            throw FilesyncSharingException.RecipientNotAdded(refused.toList())
        }
        return FilesyncAudience.Group(circleId, name, members.sortedBy { it.domainName })
    }

    /**
     * Makes [group]'s members [members]: whoever's added sees everything shared with it, whoever's removed stops seeing
     * it. Those who couldn't be added are named once the rest is done; the ones added are returned, for telling.
     */
    suspend fun setGroupMembers(group: FilesyncAudience.Group, members: List<OdinId>): List<OdinId> {
        requireSignedIn()
        val added = members.filter { it !in group.members }
        if (added.isNotEmpty()) enroll(recipientsCircle(), added)
        val refused = enroll(group.circleId, added)
        val joined = added.filter { it !in refused }
        sealCircleKey(group.circleId, readyPoints(joined))
        group.members.filter { it !in members }.forEach { asFilesync.removeFromCircle(group.circleId, it) }
        editorsMayHaveChanged()

        if (refused.isNotEmpty()) throw FilesyncSharingException.RecipientNotAdded(refused.toList())
        return joined
    }

    /** Who [fileId] is shared with now, withdrawn and expired shares left out. */
    suspend fun sharedWith(fileId: Uuid): List<FilesyncShareRecipient> {
        requireSignedIn()
        val (audiences, rows) = coroutineScope {
            val audiences = async { audienceCircles() }
            val rows = async { filesyncShares.shares(FilesyncProtocol.DriveAlias, fileId) }
            audiences.await() to rows.await()
        }
        val now = Clock.System.now().toEpochMilliseconds()
        return rows.mapNotNull { row ->
            val audience = audiences[row.circleId] ?: return@mapNotNull null
            // Expired rows are listed too, for renewing; they no longer share anything.
            if (row.expiresAt?.let { it <= now } == true) return@mapNotNull null
            row.access()?.let { FilesyncShareRecipient(audience, it, row.expiresAt) }
        }
    }

    /** Shares [file] with [audience] again on new terms; the new row replaces the old. */
    suspend fun renew(file: FilesyncHandle, audience: FilesyncAudience, access: FilesyncAccess, expiresAt: Long?) {
        requireSignedIn()
        val circleId = circleOf(audience) ?: error("no circle for $audience")
        filesyncShares.share(FilesyncProtocol.DriveAlias, listOf(shareRequest(file.fileId, file.keyHeader, circleId, DrivePermission.combine(access.permissions), expiresAt)))
    }

    /** Stops sharing [tree] (node id to handle, a folder's contents included) with [audience] wherever it is; the nodes it stopped. */
    suspend fun withdraw(tree: Map<Uuid, FilesyncHandle>, audience: FilesyncAudience): Set<Uuid> {
        requireSignedIn()
        val circleId = circleOf(audience) ?: return emptySet()
        val rows = liveShares(tree.keys)
        val shared = tree.filterKeys { id -> rows[id].orEmpty().any { it.circleId == circleId } }
        withdrawAll(shared.values.map { it.fileId to circleId })
        if (shared.isNotEmpty()) editorsMayHaveChanged()
        return shared.keys
    }

    /** Shares a new node like the folder it was made in: the same people, access and end; whether there was any to share. */
    suspend fun inherit(parentId: Uuid, nodeId: Uuid, keyHeader: KeyHeader): Boolean {
        if (parentId == FilesyncProtocol.RootNodeId) return false
        // An upload can come before the Filesync screen has ever opened in this run.
        if (!FilesyncSession.restoreFor(appCredentials)) return false
        val result = withRetryResult(RETRY, TAG) {
            val found = filesyncQuery.queryBatch(
                FilesyncProtocol.DriveAlias,
                QueryBatchRequest(
                    queryParams = FileQueryParams(clientUniqueIdAtLeastOne = listOf(parentId, nodeId)),
                    resultOptionsRequest = QueryBatchResultOptionsRequest(maxRecords = 2, includeShares = true),
                ),
            ).searchResults.associateBy { it.fileMetadata.appData.uniqueId }
            // includeShares returns only live rows.
            val rows = found[parentId]?.shares.orEmpty().takeIf { it.isNotEmpty() } ?: return@withRetryResult false
            val fileId = found[nodeId]?.fileId ?: error("node $nodeId isn't on the drive")
            filesyncShares.share(FilesyncProtocol.DriveAlias, rows.map { shareRequest(fileId, keyHeader, it) })
            true
        }
        if ((result as? RetryResult.Failure)?.exception is UnauthorizedException) signOut()
        return result.getOrThrow() == true
    }

    /**
     * How moving [nodes] into [into] changes their sharing, and that of everything below them as [tree] lists it (by
     * node id, the node's own handle included). A share is taken to come from the folder a node leaves when that folder
     * is shared with the same person.
     */
    suspend fun planMove(nodes: List<FilesyncNode>, into: Uuid, tree: suspend (FilesyncNode) -> Map<Uuid, FilesyncHandle>): FilesyncMoveSharing {
        if (!FilesyncSession.signedIn.value) return FilesyncMoveSharing.None
        val folders = liveShares(nodes.mapTo(HashSet()) { it.parentId } + into)
        // Neither folder shares anything: the move changes no one's access, and big folders aren't walked for it.
        if (folders.isEmpty()) return FilesyncMoveSharing.None
        val audiences = audienceCircles()
        val entering = folders[into].orEmpty()
        val kept = entering.mapTo(HashSet()) { it.circleId }
        val trees = nodes.associate { it.nodeId to tree(it) }
        val rows = liveShares(trees.values.flatMapTo(HashSet()) { it.keys })
        val stops = mutableMapOf<Uuid, List<Pair<Uuid, Uuid>>>()
        val starts = mutableMapOf<Uuid, List<ShareRequest>>()
        for (node in nodes) {
            val tree = trees.getValue(node.nodeId)
            val leaving = folders[node.parentId].orEmpty().map { it.circleId }.filter { it !in kept && it in audiences }.toSet()
            stops[node.nodeId] = tree.flatMap { (id, handle) -> rows[id].orEmpty().filter { it.circleId in leaving }.map { handle.fileId to it.circleId } }
            starts[node.nodeId] = tree.flatMap { (id, handle) ->
                val has = rows[id].orEmpty().mapTo(HashSet()) { it.circleId }
                entering.filter { it.circleId !in has }.map { shareRequest(handle.fileId, handle.keyHeader, it) }
            }
        }
        val losing = stops.values.flatten().mapNotNull { audiences[it.second] }.flatMap { it.members }.distinct().sortedBy { it.domainName }
        return FilesyncMoveSharing(stops, starts, losing)
    }

    /** Carries out [plan] for the nodes that did move. */
    suspend fun applyMove(plan: FilesyncMoveSharing, moved: Collection<Uuid>) {
        moved.flatMap { plan.starts[it].orEmpty() }.chunked(DriveShareProvider.MAX_SHARES_PER_REQUEST).forEach { chunk ->
            withRetryResult(RETRY, TAG) { filesyncShares.share(FilesyncProtocol.DriveAlias, chunk) }.getOrThrow()
        }
        withdrawAll(moved.flatMap { plan.stops[it].orEmpty() })
        if (moved.any { !plan.starts[it].isNullOrEmpty() || !plan.stops[it].isNullOrEmpty() }) editorsMayHaveChanged()
    }

    // One DELETE per row, a few at a time.
    private suspend fun withdrawAll(rows: List<Pair<Uuid, Uuid>>) = rows.chunked(PARALLEL).forEach { chunk ->
        coroutineScope {
            chunk.map { (fileId, circleId) ->
                async { withRetryResult(RETRY, TAG) { filesyncShares.withdraw(FilesyncProtocol.DriveAlias, fileId, circleId) }.getOrThrow() }
            }.awaitAll()
        }
    }

    // The change itself is done; a failure here is caught up by the next upkeep.
    private suspend fun editorsMayHaveChanged() {
        try {
            syncEditors()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(e, TAG) { "editors not brought up to date" }
        }
    }

    /** The live shares of each of [nodeIds] that has any. */
    private suspend fun liveShares(nodeIds: Set<Uuid>): Map<Uuid, List<ShareRow>> =
        nodeIds.chunked(PAGE).flatMap { chunk ->
            filesyncQuery.queryBatch(
                FilesyncProtocol.DriveAlias,
                QueryBatchRequest(
                    queryParams = FileQueryParams(clientUniqueIdAtLeastOne = chunk),
                    resultOptionsRequest = QueryBatchResultOptionsRequest(maxRecords = chunk.size, includeShares = true),
                ),
            ).searchResults.mapNotNull { file ->
                val nodeId = file.fileMetadata.appData.uniqueId ?: return@mapNotNull null
                file.shares?.takeIf { it.isNotEmpty() }?.let { nodeId to it }
            }
        }.toMap()

    /** The nodes in [folderId] with a live share. */
    suspend fun sharedIn(folderId: Uuid): Set<Uuid> {
        requireSignedIn()
        val shared = HashSet<Uuid>()
        filesyncQuery.forEachFilesyncPage(
            FileQueryParams(fileType = listOf(FilesyncProtocol.FolderFileType, FilesyncProtocol.FileFileType), groupId = listOf(folderId)),
            QueryBatchResultOptionsRequest(maxRecords = PAGE, includeShares = true),
        ) { page ->
            page.searchResults.forEach { file -> if (!file.shares.isNullOrEmpty()) file.fileMetadata.appData.uniqueId?.let(shared::add) }
            true
        }
        return shared
    }

    /** The groups this identity made, by name, then the people it has shared files with. */
    suspend fun audiences(): List<FilesyncAudience> {
        requireSignedIn()
        return audienceCircles().values.sortedWith(
            compareBy<FilesyncAudience> { it is FilesyncAudience.Person }.thenBy { it.label.lowercase() }
        )
    }

    /** Whom [circleId] shares with, when it's one of this identity's. */
    suspend fun audienceOf(circleId: Uuid): FilesyncAudience? {
        requireSignedIn()
        return audienceCircles()[circleId]
    }

    /** The files shared with [audience] now, each with the access its share gives. */
    suspend fun sharedWithAudience(audience: FilesyncAudience): List<FilesyncSharedFile> {
        requireSignedIn()
        val circleId = circleOf(audience) ?: return emptyList()
        val found = mutableListOf<FilesyncSharedFile>()
        filesyncQuery.forEachFilesyncPage(
            FileQueryParams(
                fileType = listOf(FilesyncProtocol.FolderFileType, FilesyncProtocol.FileFileType),
                sharedWith = listOf(circleId),
                archivalStatus = FilesyncStatus.Live.archivalStatus,
            ),
            QueryBatchResultOptionsRequest(maxRecords = PAGE, includeMetadataHeader = true, includeShares = true),
        ) { page ->
            page.searchResults.filter { it.fileState == FileState.Active }.forEach { file ->
                val row = file.shares.orEmpty().firstOrNull { it.circleId == circleId } ?: return@forEach
                val access = row.access() ?: return@forEach
                file.toFilesyncNode()?.let { (node, handle) -> found += FilesyncSharedFile(node, handle, access, row.expiresAt) }
            }
            true
        }
        return found
    }

    /**
     * The people this identity keeps a marker for, newest first. Its own markers are trusted; one someone else left (an
     * older client's) only when it names its sender.
     */
    suspend fun sharers(): List<OdinId> {
        val self = appCredentials.getActiveCredentials()?.domain
        return query.queryBatch(
            FilesyncProtocol.DriveAlias,
            QueryBatchRequest(
                queryParams = FileQueryParams(fileType = listOf(FilesyncProtocol.MarkerFileType)),
                resultOptionsRequest = QueryBatchResultOptionsRequest(
                    maxRecords = PAGE,
                    includeMetadataHeader = true,
                    ordering = QueryBatchSortOrder.NewestFirst,
                    sorting = QueryBatchSortField.AnyChangeDate,
                ),
            ),
        ).searchResults.filter { it.fileState == FileState.Active }.mapNotNull { file ->
            val marker = file.fileMetadata.appData.content
                ?.let { runCatching { OdinSystemSerializer.deserialize<FilesyncMarker>(it) }.getOrNull() } ?: return@mapNotNull null
            val sender = file.fileMetadata.senderOdinId
            val named = runCatching { OdinId(marker.odinId) }.getOrNull() ?: return@mapNotNull null
            named.takeIf { sender == null || sender == self || sender.domainName.equals(marker.odinId, ignoreCase = true) }
        }.distinct()
    }

    /** Keeps [sharer] among [sharers], so their files can be found again without the chat message that told of them. */
    suspend fun keepSharer(sharer: OdinId) {
        val self = requireNotNull(appCredentials.getActiveCredentials()).domain
        if (sharer in sharers()) return
        val keyHeader = KeyHeader.newRandom16()
        val metadata = UploadFileMetadata(
            allowDistribution = false,
            isEncrypted = true,
            accessControlList = AccessControlList(requiredSecurityGroup = SecurityGroupType.Owner.value),
            appData = UploadAppFileMetaData(
                uniqueId = markerId(sharer, self),
                fileType = FilesyncProtocol.MarkerFileType,
                dataType = 0,
                userDate = Clock.System.now().toEpochMilliseconds(),
                content = OdinSystemSerializer.serialize(FilesyncMarker(sharer.domainName, FilesyncProtocol.DriveAlias)),
            ),
        ).encryptContent(keyHeader)
        try {
            uploads.uploadFile(UploadFileRequest(driveId = FilesyncProtocol.DriveAlias, keyHeader = keyHeader, metadata = metadata))
        } catch (e: ClientException) {
            // Another device kept it first.
            if (!e.isExistingFileWithUniqueId()) throw e
        }
    }

    /** The files and folders [owner] shares with this identity, opened with the keys their shares unlock. */
    suspend fun sharedBy(owner: OdinId): List<Pair<FilesyncNode, FilesyncHandle>> {
        val self = requireNotNull(appCredentials.getActiveCredentials()).domain
        // A file whose key won't open is left out of the page, so why is remembered here to say so, once per circle.
        var unopened: FilesyncSharingException? = null
        val refusedCircles = HashSet<Uuid>()
        val byShare = ExternalFileKey { fileId, shares, locked ->
            val circleId = shares.firstOrNull()?.circleId ?: error("file $fileId came with no share")
            if (circleId in refusedCircles) throw unopened ?: error("circle $circleId's key was refused")
            val circleKey = try {
                memberCircleKey(owner, circleId, self)
            } catch (e: FilesyncSharingException.NotSealedYet) {
                refusedCircles += circleId
                unopened = e
                throw e
            } catch (e: ForbiddenException) {
                refusedCircles += circleId
                if (e.problem?.errorCodeEnum() == OdinClientErrorCode.NotReviewedByOwner) unopened = FilesyncSharingException.NotReviewedByOwner(owner)
                throw e
            }
            ShareKeys.openFileKey(circleKey, locked, FilesyncProtocol.DriveAlias, fileId, circleId).toSecureByteArray()
        }
        val found = mutableListOf<Pair<FilesyncNode, FilesyncHandle>>()
        try {
            query.forEachFilesyncPage(
                FileQueryParams(fileType = listOf(FilesyncProtocol.FolderFileType, FilesyncProtocol.FileFileType), archivalStatus = FilesyncStatus.Live.archivalStatus),
                QueryBatchResultOptionsRequest(maxRecords = PAGE, includeMetadataHeader = true),
                ownerOdinId = owner,
                externalFileKey = byShare,
            ) { page ->
                found += page.searchResults.filter { it.fileState == FileState.Active }.mapNotNull { it.toFilesyncNode(addedByOtherThan = owner) }
                true
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw unopened ?: e
        }
        if (found.isEmpty()) unopened?.let { throw it }
        return found
    }

    /** Adds [members] to [circleId]; the ones it refused. One deposited joins at their next call, and a later share seals to them. */
    private suspend fun enroll(circleId: Uuid, members: Collection<OdinId>): Set<OdinId> =
        members.chunked(ADD_MANY_LIMIT).flatMapTo(HashSet()) { chunk ->
            asFilesync.addManyToCircle(circleId.toString(), chunk.map(OdinId::domainName)).outcomes
                .filter { it.kind != EnrollmentOutcomeKind.Enrolled && it.kind != EnrollmentOutcomeKind.Deposited }.map { it.odinId }
        }

    private suspend fun shareRequest(fileId: Uuid, keyHeader: KeyHeader, circleId: Uuid, permissions: Int, expiresAt: Long?): ShareRequest {
        val locked = ShareKeys.lockFileKey(ownCircleKey(circleId).key, keyHeader.aesKey.toByteArray(), FilesyncProtocol.DriveAlias, fileId, circleId)
        return ShareRequest(fileId, circleId, permissions, expiresAt = expiresAt, lockedFileKey = locked)
    }

    /** A share like [row], of another file. */
    private suspend fun shareRequest(fileId: Uuid, keyHeader: KeyHeader, row: ShareRow): ShareRequest =
        shareRequest(fileId, keyHeader, row.circleId, DrivePermission.combine(row.permissions.values), row.expiresAt)

    private suspend fun sealCircleKey(circleId: Uuid, points: Map<OdinId, ByteArray>, unsealedMembers: List<OdinId>? = null) {
        val unsealed = (unsealedMembers ?: filesyncKeys.getUnsealedMembers(circleId)).filter { it in points }
        if (unsealed.isEmpty()) return
        val own = ownCircleKey(circleId)
        val sealed = unsealed.map { SealedMemberShareKey(it.domainName, ShareKeys.sealCircleKey(own.key, points.getValue(it), circleId, it)) }
        filesyncKeys.setMemberKeys(circleId, SealedMemberShareKeys(own.locked, sealed))
    }

    /** The point to seal to of each of [recipients] who has a Filesync drive, each from their own server, a few at a time. */
    private suspend fun readyPoints(recipients: List<OdinId>): Map<OdinId, ByteArray> =
        recipients.chunked(PARALLEL).flatMap { chunk ->
            coroutineScope { chunk.map { async { recipientPointOrNull(it)?.let { point -> it to point } } }.awaitAll() }
        }.filterNotNull().toMap()

    private suspend fun recipientPointOrNull(recipient: OdinId): ByteArray? {
        val key = try {
            peer.drivePublicKey(recipient, FilesyncProtocol.AppSlug, FilesyncProtocol.DriveSlug)
        } catch (e: OdinApiException) {
            // No Filesync drive there (404; 400 or 403 from an older server): sharing is off.
            if (e !is NotFoundException && e !is ClientException && e !is ForbiddenException) throw e
            return null
        }
        // Its server and members hold a collaborative drive's key: sealing to it would share with them too.
        if (!key.isZeroKnowledge || key.sharingModel == "collaborative") return null
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
        val sealed = circleShareKeys.getMySealedKey(owner, circleId) ?: throw FilesyncSharingException.NotSealedYet(owner)
        val writeOnlyKey = ZkKeyStore.writeOnlyPrivateKey(FilesyncProtocol.DriveAlias) ?: error("no write-only key for the Filesync drive")
        ShareKeys.unsealCircleKey(sealed, writeOnlyKey, circleId, self).also { memberKeys[owner to circleId] = it }
    }

    private suspend fun recipientsCircle(): Uuid = asFilesync.createCircle(
        CreateAppCircleRequest(
            name = recipientsDeclared.name,
            slug = recipientsDeclared.slug,
            description = recipientsDeclared.description,
            emoji = recipientsDeclared.emoji,
            driveGrants = recipientsDriveGrants(),
        )
    )

    private suspend fun editorsCircle(): Uuid = asFilesync.createCircle(
        CreateAppCircleRequest(
            name = "Filesync editors",
            slug = FilesyncProtocol.EditorsCircleSlug,
            description = "People who can change files you share with them through Homebase Filesync, and add files to your Filesync drive",
            driveGrants = listOf(DriveGrantRequest(PermissionedDrive(filesyncLabeledDrive.drive, DrivePermissionSet(listOf(DrivePermission.Write))))),
        )
    )

    private fun recipientsDriveGrants() =
        recipientsDeclared.drives.map { DriveGrantRequest(PermissionedDrive(filesyncLabeledDrive.drive, DrivePermissionSet(it.permissions))) }

    private suspend fun recipientsCircleGrant(): List<DrivePermission> =
        connections.getCirclesWithMembers()
            .firstOrNull { it.circle.slug == FilesyncProtocol.CircleSlug && it.circle.appId == filesyncAppId }
            ?.circle?.driveGrants.orEmpty()
            .mapNotNull { it.permissionedDrive }
            .filter { compareStringUuId(it.drive?.alias.orEmpty(), FilesyncProtocol.DriveAlias.toString()) }
            .flatMap { DrivePermission.fromNames(it.permission.orEmpty()) }

    private suspend fun filesyncCircles(): List<CircleWithMembers> =
        connections.getCirclesWithMembers(withIndividual = true).filter { it.circle.appId == filesyncAppId }

    private suspend fun audienceCircles(): Map<Uuid, FilesyncAudience> = audiencesOf(filesyncCircles())

    /** The app's circles that files are shared with: each person's own, and the groups not disabled. */
    private fun audiencesOf(circles: List<CircleWithMembers>): Map<Uuid, FilesyncAudience> =
        circles
            .mapNotNull { c ->
                val id = Uuid.parse(c.circle.id)
                when {
                    c.circle.designation == CircleDesignation.Individual -> c.members.singleOrNull()?.let { id to FilesyncAudience.Person(it) }
                    !c.circle.disabled && c.circle.slug?.matches(GROUP_SLUG) == true ->
                        id to FilesyncAudience.Group(id, c.circle.name, c.members.sortedBy { it.domainName })
                    else -> null
                }
            }
            .toMap()

    private suspend fun circleOf(audience: FilesyncAudience): Uuid? = when (audience) {
        is FilesyncAudience.Group -> audience.circleId
        is FilesyncAudience.Person -> audienceCircles().entries.firstOrNull { it.value == audience }?.key
    }

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
        private val RETRY = RetryConfig(maxRetries = 3, retryOn = { it.isTransientNetworkFailure() || it is ServerException })
        private const val ADD_MANY_LIMIT = 500
        private const val PARALLEL = 8
        private val recipientsDeclared = filesyncAppRegistration.circles.single { it.slug == FilesyncProtocol.CircleSlug }
        // The recipients and editors circles' slugs never look like this; nothing else of Filesync's may.
        private val GROUP_SLUG = Regex("g[0-9a-f]{13}")
        private const val TAG = "FilesyncSharing"
        private val filesyncAppId = Uuid.parseHex(filesyncAppRegistration.appId)

        /** Every device derives the same: one marker per sharer and recipient. */
        fun markerId(sender: OdinId, recipient: OdinId): Uuid =
            Uuid.fromByteArray(HashUtil.sha256Sync("filesync-marker\n${sender.domainName.lowercase()}\n${recipient.domainName.lowercase()}".encodeToByteArray()).copyOf(16))

        /** `i` and 13 hex characters of the person's hash: within the 14-character slug limit, the same on every device. */
        fun individualSlug(person: OdinId): String =
            "i" + HashUtil.sha256Sync(person.domainName.lowercase().encodeToByteArray()).toHexString().take(13)

        internal fun groupSlug(): String = "g" + Uuid.random().toHexString().take(13)
    }
}
