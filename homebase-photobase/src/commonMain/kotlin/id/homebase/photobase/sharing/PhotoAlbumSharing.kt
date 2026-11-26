package id.homebase.photobase.sharing

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.NotFoundException
import id.homebase.api.client.RetryConfig
import id.homebase.api.client.ServerException
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.connections.CircleDesignation
import id.homebase.api.client.connections.CircleShareKeyProvider
import id.homebase.api.client.connections.CircleWithMembers
import id.homebase.api.client.connections.ConnectionNetworkProvider
import id.homebase.api.client.connections.CreateAppCircleRequest
import id.homebase.api.client.connections.DriveGrantRequest
import id.homebase.api.client.connections.PermissionedDrive
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.QueryBatchRequest
import id.homebase.api.client.drives.QueryBatchResultOptionsRequest
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.drives.query.FileQueryParams
import id.homebase.api.client.drives.query.forEachPage
import id.homebase.api.client.drives.shares.DriveShareProvider
import id.homebase.api.client.drives.shares.RecipientKey
import id.homebase.api.client.drives.shares.SelectiveShareCircles
import id.homebase.api.client.drives.shares.SelectiveShareDrive
import id.homebase.api.client.drives.shares.ShareRequest
import id.homebase.api.client.drives.shares.ShareRow
import id.homebase.api.client.drives.shares.points
import id.homebase.api.client.drives.shares.unsupported
import id.homebase.api.client.isTransientNetworkFailure
import id.homebase.api.client.peer.PeerDriveQueryProvider
import id.homebase.api.client.withRetryResult
import id.homebase.api.common.OdinId
import id.homebase.api.crypto.HashUtil
import id.homebase.api.youauth.DrivePermission
import id.homebase.api.youauth.DrivePermissionSet
import id.homebase.core.config.photobaseAppRegistration
import id.homebase.core.config.photosLabeledDrive
import id.homebase.core.photobase.PhotobaseProtocol
import id.homebase.photobase.PhotobaseRecord
import id.homebase.photobase.gallery.PhotoAlbums
import io.ktor.client.HttpClient
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What a person may do with an album shared with them. */
enum class PhotoAlbumAccess { View, Add }

/** Whom an album is shared with: one person, through a circle of their own, or a named group, a circle of the app's. */
sealed interface AlbumAudience {
    val members: List<OdinId>

    data class Person(val odinId: OdinId) : AlbumAudience {
        override val members get() = listOf(odinId)
    }

    data class Group(val circleId: Uuid, val name: String, override val members: List<OdinId>) : AlbumAudience
}

data class AlbumShareRecipient(val audience: AlbumAudience, val access: PhotoAlbumAccess, val expiresAt: Long?)

/** A share made: the circle it went to, and the members whose key waits for them to set up Photos. */
data class AlbumShared(val circleId: Uuid, val waiting: List<OdinId>)

/** An album [owner] shares with this identity, as their drive holds it. */
data class SharedAlbum(
    val owner: OdinId,
    val id: Uuid,
    val name: String,
    /** The rows it reaches this identity through: what a photo added to it is shared with. */
    val shares: List<ShareRow>,
    val canAdd: Boolean,
)

sealed class PhotoSharingException(message: String) : Exception(message) {
    /** Sharing writes as Photobase, which this device hasn't signed in to. */
    class NotSignedIn : PhotoSharingException("Not signed in to Photobase")

    class RecipientNotAdded(val recipients: List<OdinId>) : PhotoSharingException("$recipients couldn't be added")

    /** [owner] hasn't reviewed this identity's connection, so it won't hand over the keys to what it shared. */
    class NotReviewedByOwner(val owner: OdinId) : PhotoSharingException("$owner hasn't reviewed this connection")

    /** [owner] hasn't sealed the circle key to this identity yet: it does once this identity has a Photos drive. */
    class NotSealedYet(val owner: OdinId) : PhotoSharingException("$owner hasn't sealed its key here yet")
}

/**
 * Sharing albums, the way Filesync shares folders (FilesyncSharing): everything stays on the owner's Photos drive, and a
 * person or group reads the album's record and its photos through share rows, each locking the file's key with the
 * circle's. Every row grants Read, React and Comment; Add is membership in the editors circle, whose drive Write lets a
 * member deposit photos and nothing more (odin-core `AssertMemberMayWriteAsync`). Which circles may add is kept in the
 * album record's content, which the owner's devices and the album's members read.
 */
class PhotoAlbumSharing(
    httpClient: HttpClient,
    private val appCredentials: CredentialsManager,
    private val connections: ConnectionNetworkProvider,
    circleShareKeys: CircleShareKeyProvider,
    peer: PeerDriveQueryProvider,
    /** This app's, for its own drive and for reading others' over peer. */
    private val query: DriveQueryProvider,
    private val record: PhotobaseRecord,
    private val albums: PhotoAlbums,
    /** The device's copies of these photos, with their keys. */
    private val filesOf: suspend (Collection<Uuid>) -> List<HomebaseFile>,
) {
    private val circles = SelectiveShareCircles(
        httpClient,
        PhotobaseSession,
        SelectiveShareDrive(PhotobaseProtocol.DriveAlias, PhotobaseProtocol.AppSlug, PhotobaseProtocol.DriveSlug),
        circleShareKeys,
        peer,
        appCredentials,
        notSignedIn = { PhotoSharingException.NotSignedIn() },
        notSealedYet = { PhotoSharingException.NotSealedYet(it) },
        notReviewedByOwner = { PhotoSharingException.NotReviewedByOwner(it) },
    )
    private val asApp = circles.asApp
    private val appShares = circles.appShares
    private val appQuery = DriveQueryProvider(httpClient, PhotobaseSession.credentials)
    private val editing = Mutex()

    suspend fun signOutIfRefused(e: Throwable?): Boolean = circles.signOutIfRefused(e)

    suspend fun signOut() = circles.signOut()

    /** Shares album [albumId] with [audience]: its record, and every photo in it. Telling its members is the caller's. */
    suspend fun share(albumId: Uuid, audience: AlbumAudience, access: PhotoAlbumAccess, expiresAt: Long?): AlbumShared {
        circles.requireSignedIn()
        val members = audience.members
        val keys = circles.keysOf(members)
        keys.unsupported().takeIf { it.isNotEmpty() }?.let { throw PhotoSharingException.RecipientNotAdded(it) }
        val points = keys.points()

        // Their individual shares then don't depend on a group they might leave.
        circles.enroll(recipientsCircle(), members)
        val circleId = when (audience) {
            is AlbumAudience.Group -> audience.circleId
            is AlbumAudience.Person -> asApp.createCircle(
                CreateAppCircleRequest(
                    name = audience.odinId.domainName,
                    slug = SelectiveShareCircles.individualSlug(audience.odinId),
                    designation = CircleDesignation.Individual,
                )
            ).also { circles.enroll(it, members) }
        }
        circles.sealCircleKey(circleId, points)

        albums.bringTagsTogether(albumId)
        val recordFile = albumRecord(albumId) ?: error("album $albumId isn't on the drive")
        val files = listOf(recordFile) + albumPhotos(albumId)
        files.chunked(DriveShareProvider.MAX_SHARES_PER_REQUEST).forEach { chunk ->
            appShares.share(PhotobaseProtocol.DriveAlias, chunk.map { circles.shareRequest(it.fileId, it.keyHeader, circleId, ROW_PERMISSIONS, expiresAt) })
        }
        setAdding(albumId, circleId, access == PhotoAlbumAccess.Add)
        // After the rows and the record, so a concurrent syncEditors that read them before can't take the members out again.
        if (access == PhotoAlbumAccess.Add) editing.withLock { circles.enroll(editorsCircle(), members) }
        else editorsMayHaveChanged(removalsOnly = true)
        return AlbumShared(circleId, waiting = members.filter { keys[it] == RecipientKey.NoDrive })
    }

    /** Stops sharing album [albumId] with [audience]; a photo that's also in another album shared with them stays. */
    suspend fun withdraw(albumId: Uuid, audience: AlbumAudience) {
        circles.requireSignedIn()
        val circleId = circleOf(audience) ?: return
        withdrawCircles(albumId, setOf(circleId))
        setAdding(albumId, circleId, false)
        editorsMayHaveChanged(removalsOnly = true)
    }

    /** Before album [albumId] is deleted: stops sharing it with everyone. */
    suspend fun withdrawAll(albumId: Uuid) {
        if (!PhotobaseSession.restoreFor(appCredentials)) return
        val circleIds = albumRecord(albumId)?.shares.orEmpty().mapTo(HashSet()) { it.circleId }
        if (circleIds.isEmpty()) return
        withdrawCircles(albumId, circleIds)
        editorsMayHaveChanged(removalsOnly = true)
    }

    /** Who album [albumId] is shared with now, withdrawn and expired shares left out. */
    suspend fun sharedWith(albumId: Uuid): List<AlbumShareRecipient> {
        circles.requireSignedIn()
        val (audiences, recordFile) = coroutineScope {
            val audiences = async { audienceCircles() }
            val recordFile = async { albumRecord(albumId) }
            audiences.await() to recordFile.await()
        }
        recordFile ?: return emptyList()
        val adding = addingCircles(recordFile)
        // includeShares returns only live rows.
        return recordFile.shares.orEmpty().mapNotNull { row ->
            audiences[row.circleId]?.let { AlbumShareRecipient(it, if (row.circleId in adding) PhotoAlbumAccess.Add else PhotoAlbumAccess.View, row.expiresAt) }
        }
    }

    /** The groups this identity made, by name, then the people it has shared albums with. */
    suspend fun audiences(): List<AlbumAudience> {
        circles.requireSignedIn()
        return audienceCircles().values.sortedWith(compareBy<AlbumAudience> { it is AlbumAudience.Person }.thenBy { it.label.lowercase() })
    }

    /** Makes a group called [name] of [members]: a circle of the app's granting the drive what the recipients' circle grants. */
    suspend fun createGroup(name: String, members: List<OdinId>): AlbumAudience.Group {
        circles.requireSignedIn()
        val circleId = asApp.createCircle(CreateAppCircleRequest(name = name, slug = SelectiveShareCircles.groupSlug(), driveGrants = recipientsDriveGrants()))
        val refused = circles.enroll(circleId, members)
        if (refused.isNotEmpty()) {
            asApp.deleteCircle(circleId, removeMembers = true)
            throw PhotoSharingException.RecipientNotAdded(refused.toList())
        }
        return AlbumAudience.Group(circleId, name, members.sortedBy { it.domainName })
    }

    /** Makes [group]'s members [members]; the ones added, for telling. Those who couldn't be added are named once the rest is done. */
    suspend fun setMembers(group: AlbumAudience.Group, members: List<OdinId>): List<OdinId> {
        circles.requireSignedIn()
        val keys = circles.keysOf(members.filter { it !in group.members })
        val added = keys.keys.filter { it !in keys.unsupported() }
        if (added.isNotEmpty()) circles.enroll(recipientsCircle(), added)
        val refused = circles.enroll(group.circleId, added) + keys.unsupported()
        val joined = added.filter { it !in refused }
        circles.sealCircleKey(group.circleId, keys.points().filterKeys { it in joined })
        group.members.filter { it !in members }.forEach { asApp.removeFromCircle(group.circleId, it) }
        editorsMayHaveChanged()
        if (refused.isNotEmpty()) throw PhotoSharingException.RecipientNotAdded(refused.toList())
        return joined
    }

    /**
     * Photos [fileIds] were put in album [albumId] or taken out: an added one is shared like the album, and a removed one
     * stops being shared with each of its circles unless another album the photo is in is shared with it too.
     */
    suspend fun albumTagChanged(albumId: Uuid, on: Boolean, fileIds: Collection<Uuid>) {
        if (fileIds.isEmpty()) return
        // A change can come before the sharing screen has ever opened in this run.
        if (!PhotobaseSession.restoreFor(appCredentials)) return
        val rows = albumRecord(albumId)?.shares.orEmpty().takeIf { it.isNotEmpty() } ?: return
        val photos = filesOf(fileIds)
        if (on) {
            val requests = photos.flatMap { photo -> rows.map { circles.shareRequest(photo.fileId, photo.keyHeader, it) } }
            requests.chunked(DriveShareProvider.MAX_SHARES_PER_REQUEST).forEach { chunk ->
                withRetryResult(RETRY, TAG) { appShares.share(PhotobaseProtocol.DriveAlias, chunk) }.getOrThrow()
            }
            return
        }
        val circleIds = rows.mapTo(HashSet()) { it.circleId }
        val otherRows = HashMap<Uuid, Set<Uuid>>()
        withdrawRows(photos.flatMap { photo ->
            val kept = photo.otherAlbums(albumId).flatMapTo(HashSet()) { other ->
                otherRows.getOrPut(other) { albumRecord(other)?.shares.orEmpty().mapTo(HashSet()) { it.circleId } }
            }
            (circleIds - kept).map { photo.fileId to it }
        })
    }

    /** Seals each circle's key to the members who have set up Photos since they were shared with. */
    suspend fun sealPending() {
        circles.requireSignedIn()
        circles.sealPending(audienceCircles().keys)
    }

    /**
     * Puts in the editors circle exactly the people a live Add share reaches: a row on an album's record, for a circle
     * the record lists as adding. Drive Write is what lets them deposit photos, anywhere on the drive.
     */
    suspend fun syncEditors(removalsOnly: Boolean = false) = editing.withLock {
        circles.requireSignedIn()
        val photobaseCircles = photobaseCircles()
        val listed = photobaseCircles.firstOrNull { it.circle.slug == PhotobaseProtocol.EditorsCircleSlug }
        // One deposited joins at their next call: counted, so they're neither added again nor left in.
        val current = (listed?.members.orEmpty() + listed?.pendingMembers.orEmpty().map { it.odinId }).toSet()
        if (removalsOnly && current.isEmpty()) return@withLock
        val audiences = audiencesOf(photobaseCircles)
        val confirmed = editorsOf(albumRecords(), audiences)
        val add = if (removalsOnly) emptySet() else confirmed - current
        val remove = current - confirmed
        if (add.isEmpty() && remove.isEmpty()) return@withLock
        val circleId = listed?.circle?.id?.let(Uuid::parse) ?: editorsCircle()
        circles.enroll(circleId, add)
        remove.forEach { asApp.removeFromCircle(circleId, it) }
    }

    /** Brings the tags of every shared album's photos together, so an added photo of a member's shows here too. */
    suspend fun bringSharedAlbumsTogether() {
        circles.requireSignedIn()
        albumRecords().filter { !it.shares.isNullOrEmpty() }.mapNotNull { it.fileMetadata.appData.uniqueId }.forEach { albums.bringTagsTogether(it) }
    }

    /** The people who have shared albums with this identity and been kept ([keepSharer]), newest first. */
    suspend fun sharers(): List<OdinId> =
        query.queryBatch(
            PhotobaseProtocol.DriveAlias,
            QueryBatchRequest(
                queryParams = FileQueryParams(fileType = listOf(PhotobaseProtocol.SharerFileType)),
                resultOptionsRequest = QueryBatchResultOptionsRequest(
                    maxRecords = PAGE,
                    includeMetadataHeader = true,
                    ordering = QueryBatchSortOrder.NewestFirst,
                    sorting = QueryBatchSortField.CreatedDate,
                ),
            ),
        ).searchResults.filter { it.fileState == FileState.Active && it.fileMetadata.senderOdinId == null }
            .mapNotNull { file -> file.fileMetadata.appData.content?.let(::sharerOf) }.distinct()

    /** Keeps [sharer] among [sharers], so their albums can be found again without the message that told of them. */
    suspend fun keepSharer(sharer: OdinId) {
        val self = requireNotNull(appCredentials.getActiveCredentials()).domain
        if (sharer == self || sharer in sharers()) return
        record.write(sharerId(sharer, self), PhotobaseProtocol.SharerFileType) { JsonObject(mapOf(ODIN_ID to JsonPrimitive(sharer.domainName))).toString() }
    }

    /** The albums [owner] shares with this identity, opened with the keys their shares unlock. */
    suspend fun sharedAlbums(owner: OdinId): List<SharedAlbum> {
        val refused = HashMap<Uuid, Exception>()
        val found = mutableListOf<SharedAlbum>()
        query.forEachPage(
            PhotobaseProtocol.DriveAlias,
            FileQueryParams(fileType = listOf(PhotobaseProtocol.AlbumFileType)),
            QueryBatchResultOptionsRequest(maxRecords = PAGE, includeMetadataHeader = true),
            ownerOdinId = owner,
            externalFileKey = circles.memberFileKeys(owner, refused),
        ) { page ->
            found += page.searchResults.filter { it.fileState == FileState.Active }.mapNotNull { it.toSharedAlbum(owner) }
            true
        }
        // Nothing opened: say why rather than show nothing.
        if (found.isEmpty() && refused.isNotEmpty()) throw whyUnopened(owner, refused.values)
        return found
    }

    /** A page of the photos in [album], newest taken first, and the cursor for the next when there's more. */
    suspend fun sharedPhotos(album: SharedAlbum, cursor: String?, pageSize: Int = PHOTO_PAGE): Pair<List<HomebaseFile>, String?> {
        val page = query.queryBatch(
            PhotobaseProtocol.DriveAlias,
            QueryBatchRequest(
                queryParams = FileQueryParams(fileType = listOf(PhotobaseProtocol.PhotoFileType), tagsMatchAtLeastOne = listOf(album.id)),
                resultOptionsRequest = QueryBatchResultOptionsRequest(
                    cursorState = cursor,
                    maxRecords = pageSize,
                    includeMetadataHeader = true,
                    ordering = QueryBatchSortOrder.NewestFirst,
                    sorting = QueryBatchSortField.UserDate,
                ),
            ),
            ownerOdinId = album.owner,
            externalFileKey = circles.memberFileKeys(album.owner, HashMap()),
        )
        return page.searchResults.filter { it.fileState == FileState.Active } to page.cursorState?.takeIf { page.hasMoreRows && it != cursor }
    }

    /** [album]'s owner's photo with [globalTransitId], when this identity can read it through the album. */
    suspend fun sharedPhoto(album: SharedAlbum, globalTransitId: Uuid): HomebaseFile? =
        query.queryBatch(
            PhotobaseProtocol.DriveAlias,
            QueryBatchRequest(
                queryParams = FileQueryParams(globalTransitId = listOf(globalTransitId)),
                resultOptionsRequest = QueryBatchResultOptionsRequest(maxRecords = 1, includeMetadataHeader = true),
            ),
            ownerOdinId = album.owner,
            externalFileKey = circles.memberFileKeys(album.owner, HashMap()),
        ).searchResults.firstOrNull { it.fileState == FileState.Active }

    /** One locked key per row [album] reaches this identity through: a photo added to it is shared as the album is with them. */
    internal suspend fun sharesOfNewPhoto(album: SharedAlbum, fileKey: KeyHeader, globalTransitId: Uuid): List<ShareRequest> =
        album.shares.filter { it.permissions.values.isNotEmpty() }.map { circles.memberShareOfNewFile(album.owner, it, fileKey, globalTransitId) }

    /** [owner]'s Photos drive's write-only key: a photo this identity adds there is sealed to it. */
    internal suspend fun ownersDriveKey(owner: OdinId): ByteArray = circles.ownersDriveKey(owner)

    private suspend fun withdrawCircles(albumId: Uuid, circleIds: Set<Uuid>) {
        val recordFile = albumRecord(albumId)
        val photos = albumPhotos(albumId)
        val otherRows = HashMap<Uuid, Set<Uuid>>()
        val rows = listOfNotNull(recordFile).flatMap { file -> file.shares.orEmpty().filter { it.circleId in circleIds }.map { file.fileId to it.circleId } } +
            photos.flatMap { photo ->
                val kept = photo.otherAlbums(albumId).flatMapTo(HashSet()) { other ->
                    otherRows.getOrPut(other) { albumRecord(other)?.shares.orEmpty().mapTo(HashSet()) { it.circleId } }
                }
                photo.shares.orEmpty().filter { it.circleId in circleIds && it.circleId !in kept }.map { photo.fileId to it.circleId }
            }
        withdrawRows(rows)
    }

    // One DELETE per row, a few at a time; a row gone already is what was asked.
    private suspend fun withdrawRows(rows: List<Pair<Uuid, Uuid>>) = rows.chunked(SelectiveShareCircles.PARALLEL).forEach { chunk ->
        coroutineScope {
            chunk.map { (fileId, circleId) ->
                async {
                    try {
                        withRetryResult(RETRY, TAG) { appShares.withdraw(PhotobaseProtocol.DriveAlias, fileId, circleId) }.getOrThrow()
                    } catch (e: NotFoundException) {
                        Logger.i(tag = TAG) { "photo $fileId wasn't shared with $circleId" }
                    }
                }
            }.awaitAll()
        }
    }

    /** Adds [circleId] to album [albumId]'s adding circles, or takes it out; written only when that changes. */
    private suspend fun setAdding(albumId: Uuid, circleId: Uuid, on: Boolean) {
        val stored = record.header(albumId)?.fileMetadata?.appData?.content
        if ((circleId in addingCircles(stored)) == on) return
        record.write(albumId, PhotobaseProtocol.AlbumFileType, create = false) { content ->
            val now = addingCircles(content).let { if (on) it + circleId else it - circleId }
            JsonObject(contentObject(content) + (ADDING to JsonArray(now.map { JsonPrimitive(it.toString()) }))).toString()
        }
    }

    // The editors circle is never shared with, nor the recipients' circle.
    private suspend fun audienceCircles(): Map<Uuid, AlbumAudience> = audiencesOf(photobaseCircles())

    private suspend fun photobaseCircles(): List<CircleWithMembers> =
        connections.getCirclesWithMembers(withIndividual = true).filter { it.circle.appId == photobaseAppId }

    private suspend fun circleOf(audience: AlbumAudience): Uuid? = when (audience) {
        is AlbumAudience.Group -> audience.circleId
        is AlbumAudience.Person -> audienceCircles().entries.firstOrNull { it.value == audience }?.key
    }

    private suspend fun albumRecord(albumId: Uuid): HomebaseFile? =
        appQuery.queryBatch(
            PhotobaseProtocol.DriveAlias,
            QueryBatchRequest(
                queryParams = FileQueryParams(fileType = listOf(PhotobaseProtocol.AlbumFileType), clientUniqueIdAtLeastOne = listOf(albumId)),
                resultOptionsRequest = QueryBatchResultOptionsRequest(maxRecords = 1, includeMetadataHeader = true, includeShares = true),
            ),
        ).searchResults.firstOrNull { it.fileState == FileState.Active }

    private suspend fun albumRecords(): List<HomebaseFile> = buildList {
        appQuery.forEachPage(
            PhotobaseProtocol.DriveAlias,
            FileQueryParams(fileType = listOf(PhotobaseProtocol.AlbumFileType)),
            QueryBatchResultOptionsRequest(maxRecords = PAGE, includeMetadataHeader = true, includeShares = true),
        ) { page ->
            addAll(page.searchResults.filter { it.fileState == FileState.Active })
            true
        }
    }

    private suspend fun albumPhotos(albumId: Uuid): List<HomebaseFile> = buildList {
        appQuery.forEachPage(
            PhotobaseProtocol.DriveAlias,
            FileQueryParams(fileType = listOf(PhotobaseProtocol.PhotoFileType), tagsMatchAtLeastOne = listOf(albumId)),
            QueryBatchResultOptionsRequest(maxRecords = PAGE, includeShares = true),
        ) { page ->
            addAll(page.searchResults.filter { it.fileState == FileState.Active })
            true
        }
    }

    // The change itself is done; a failure here is caught up by the next upkeep.
    private suspend fun editorsMayHaveChanged(removalsOnly: Boolean = false) {
        try {
            syncEditors(removalsOnly)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(e, TAG) { "editors not brought up to date" }
        }
    }

    private fun whyUnopened(owner: OdinId, failures: Collection<Exception>): Exception = circles.whyUnopened(owner, failures)

    private suspend fun recipientsCircle(): Uuid = asApp.createCircle(
        CreateAppCircleRequest(
            name = recipientsDeclared.name,
            slug = recipientsDeclared.slug,
            description = recipientsDeclared.description,
            emoji = recipientsDeclared.emoji,
            driveGrants = recipientsDriveGrants(),
        )
    )

    private suspend fun editorsCircle(): Uuid = asApp.createCircle(
        CreateAppCircleRequest(
            name = "Photos editors",
            slug = PhotobaseProtocol.EditorsCircleSlug,
            description = "People who can add photos to albums you share with them through Homebase Photos",
            driveGrants = listOf(DriveGrantRequest(PermissionedDrive(photosLabeledDrive.drive, DrivePermissionSet(listOf(DrivePermission.Write))))),
        )
    )

    private fun recipientsDriveGrants() =
        recipientsDeclared.drives.map { DriveGrantRequest(PermissionedDrive(photosLabeledDrive.drive, DrivePermissionSet(it.permissions))) }

    companion object {
        private const val TAG = "PhotoAlbumSharing"
        private const val PAGE = 1000
        private const val PHOTO_PAGE = 120
        private const val ADDING = "adding"
        private const val ODIN_ID = "odinId"
        private val RETRY = RetryConfig(maxRetries = 3, retryOn = { it.isTransientNetworkFailure() || it is ServerException })
        private val recipientsDeclared = photobaseAppRegistration.circles.single { it.slug == PhotobaseProtocol.CircleSlug }
        private val photobaseAppId = Uuid.parseHex(photobaseAppRegistration.appId)

        /** Read, React and Comment on every row, View or Add: Write on a row would let its circle change or delete the photo. */
        internal val ROW_PERMISSIONS = DrivePermission.combine(listOf(DrivePermission.Read, DrivePermission.React, DrivePermission.Comment))

        /** Every device derives the same: one record per sharer. */
        internal fun sharerId(sharer: OdinId, self: OdinId): Uuid =
            Uuid.fromByteArray(HashUtil.sha256Sync("photobase-sharer\n${sharer.domainName.lowercase()}\n${self.domainName.lowercase()}".encodeToByteArray()).copyOf(16))

        internal fun contentObject(content: String?): JsonObject =
            content?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: JsonObject(emptyMap())

        /** The circles an album record's content lists as adding. */
        internal fun addingCircles(content: String?): Set<Uuid> =
            contentObject(content)[ADDING]?.let { runCatching { it.jsonArray }.getOrNull() }.orEmpty()
                .mapNotNullTo(HashSet()) { runCatching { Uuid.parse(it.jsonPrimitive.content) }.getOrNull() }

        internal fun addingCircles(file: HomebaseFile): Set<Uuid> = addingCircles(file.fileMetadata.appData.content)

        /** Who an Add share reaches: the members of each circle an album lists as adding and still shares with. */
        internal fun editorsOf(records: List<HomebaseFile>, audiences: Map<Uuid, AlbumAudience>): Set<OdinId> =
            records.flatMapTo(HashSet()) { file ->
                val adding = addingCircles(file)
                file.shares.orEmpty().filter { it.circleId in adding }.flatMap { audiences[it.circleId]?.members.orEmpty() }
            }

        internal fun sharerOf(content: String): OdinId? =
            contentObject(content)[ODIN_ID]?.let { runCatching { OdinId(it.jsonPrimitive.content) }.getOrNull() }

        /** The app's circles that albums are shared with: each person's own, and the groups not disabled. */
        internal fun audiencesOf(circles: List<CircleWithMembers>): Map<Uuid, AlbumAudience> =
            circles.mapNotNull { c ->
                val id = Uuid.parse(c.circle.id)
                when {
                    c.circle.designation == CircleDesignation.Individual -> c.members.singleOrNull()?.let { id to AlbumAudience.Person(it) }
                    !c.circle.disabled && c.circle.slug?.matches(SelectiveShareCircles.GROUP_SLUG) == true ->
                        id to AlbumAudience.Group(id, c.circle.name, c.members.sortedBy { it.domainName })
                    else -> null
                }
            }.toMap()

        internal fun HomebaseFile.otherAlbums(albumId: Uuid): List<Uuid> =
            fileMetadata.appData.tags.orEmpty().filter { it != albumId }

        internal fun HomebaseFile.toSharedAlbum(owner: OdinId): SharedAlbum? {
            val id = fileMetadata.appData.uniqueId ?: return null
            val content = contentObject(fileMetadata.appData.content)
            val name = content["name"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() } ?: return null
            val rows = shares.orEmpty()
            val adding = addingCircles(this)
            return SharedAlbum(owner, id, name, rows, canAdd = rows.any { it.circleId in adding })
        }
    }
}

val AlbumAudience.label: String
    get() = when (this) {
        is AlbumAudience.Person -> odinId.domainName
        is AlbumAudience.Group -> name
    }
