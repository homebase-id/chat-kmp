package id.homebase.photobase.sharing

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.drives.TargetDrive
import id.homebase.api.client.drives.shares.ShareRequest
import id.homebase.api.client.drives.upload.TransitOptions
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.OdinId
import id.homebase.core.photobase.PhotobaseProtocol
import id.homebase.photobase.backup.BackupRequestBuilder
import id.homebase.photobase.backup.HandOffResult
import id.homebase.upload.MediaUploadSpec
import id.homebase.upload.UploadOutcome
import id.homebase.upload.UploadService
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A photo this identity is adding to [owner]'s album [albumId]: a deposit picked as [globalTransitId]. */
data class PhotoAdd(val owner: OdinId, val albumId: Uuid, val uniqueId: Uuid, val globalTransitId: Uuid, val name: String)

/** How adding a photo went, as far as this app could tell. */
sealed interface PhotoAddOutcome {
    val add: PhotoAdd

    data class Delivered(override val add: PhotoAdd) : PhotoAddOutcome

    /** This identity's outbox will send it again. */
    data class Retrying(override val add: PhotoAdd) : PhotoAddOutcome

    /** The outbox gave up on it. */
    data class Refused(override val add: PhotoAdd) : PhotoAddOutcome

    /**
     * Sent, but not on its owner's drive: refused there, or lost. A deposit whose content the owner already has is
     * refused without telling the sender (odin-core `SentFileChanges`), so this is the likeliest reason.
     */
    data class NotConfirmed(override val add: PhotoAdd) : PhotoAddOutcome
}

/**
 * A member adding photos to an album someone shares with them, the way FilesyncMemberWrites.add adds a file to a shared
 * folder (odin-core SelectiveShareTests.MemberWrites): the photo is built as the member's own backup would build it,
 * sealed to the owner's Photos drive key, given a global transit id picked here, the album's app tag and the album's
 * share rows, so everyone the album reaches sees it at once. It goes through the outbox, and is confirmed by reading it
 * back from the owner's drive.
 */
class PhotoMemberWrites internal constructor(
    private val build: suspend (path: String, contentType: String, name: String, createdAtMs: Long, send: suspend (Uuid, MediaUploadSpec) -> HandOffResult) -> HandOffResult,
    private val sharesOfNewPhoto: suspend (SharedAlbum, KeyHeader, Uuid) -> List<ShareRequest>,
    private val ownersDriveKey: suspend (OdinId) -> ByteArray,
    private val enqueue: suspend (MediaUploadSpec) -> UploadOutcome,
    /** Whether the photo with this global transit id is on the album's owner's drive, as this identity reads it. */
    private val landed: suspend (SharedAlbum, Uuid) -> Boolean,
    outboxEvents: Flow<BackendEvent>,
    private val scope: CoroutineScope,
    private val confirmDelays: List<Duration> = listOf(2.seconds, 10.seconds, 30.seconds),
) {
    constructor(builder: BackupRequestBuilder, sharing: PhotoAlbumSharing, uploadService: UploadService, eventBus: EventBus, scope: CoroutineScope) : this(
        build = builder::buildPicked,
        sharesOfNewPhoto = sharing::sharesOfNewPhoto,
        ownersDriveKey = sharing::ownersDriveKey,
        enqueue = { spec -> uploadService.upload(spec, scope) },
        landed = { album, gtid -> sharing.sharedPhoto(album, gtid) != null },
        outboxEvents = eventBus.events,
        scope = scope,
    )

    private val lock = Mutex()
    private val pending = mutableMapOf<Uuid, Pair<PhotoAdd, SharedAlbum>>()

    private val _outcomes = MutableSharedFlow<PhotoAddOutcome>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val outcomes: SharedFlow<PhotoAddOutcome> = _outcomes.asSharedFlow()

    init {
        scope.launch {
            outboxEvents.collect { event ->
                if (event !is BackendEvent.OutboxEvent) return@collect
                when (event) {
                    is BackendEvent.OutboxEvent.ItemCompleted -> settled(event.driveId, event.uniqueId)?.let { scope.launch { confirm(it.first, it.second) } }
                    is BackendEvent.OutboxEvent.OutboxItemDropped -> settled(event.driveId, event.uniqueId)?.let { _outcomes.emit(PhotoAddOutcome.Refused(it.first)) }
                    is BackendEvent.OutboxEvent.ItemFailed -> waiting(event.driveId, event.uniqueId)?.let { _outcomes.emit(PhotoAddOutcome.Retrying(it)) }
                    else -> Unit
                }
            }
        }
    }

    /** Adds the file at [path] to [album]; how it went comes on [outcomes]. Throws when it couldn't be queued. */
    suspend fun add(album: SharedAlbum, path: String, contentType: String, name: String, modifiedMs: Long): PhotoAdd {
        check(album.canAdd) { "${album.owner}'s album ${album.id} takes no photos from this identity" }
        val globalTransitId = Uuid.random()
        var add: PhotoAdd? = null
        val result = build(path, contentType, name, modifiedMs) { uniqueId, spec ->
            // Without a share the photo lands for its owner alone: nobody else in the album would see it.
            val shares = sharesOfNewPhoto(album, spec.keyHeader, globalTransitId)
            check(shares.isNotEmpty()) { "${album.owner}'s album ${album.id} reaches this identity through no share" }
            val deposit = depositSpec(spec, album, globalTransitId, shares, ownersDriveKey(album.owner))
            val made = PhotoAdd(album.owner, album.id, uniqueId, globalTransitId, name)
            // Waiting before it's queued: the outbox may finish it before enqueue returns.
            lock.withLock { pending[uniqueId] = made to album }
            when (val outcome = enqueue(deposit)) {
                is UploadOutcome.Enqueued -> add = made
                else -> {
                    lock.withLock { pending.remove(uniqueId) }
                    error("photo not queued for ${album.owner}: $outcome")
                }
            }
            HandOffResult.Queued
        }
        check(result == HandOffResult.Queued) { "photo at $path changed while it was read" }
        return checkNotNull(add)
    }

    private suspend fun settled(driveId: Uuid, uniqueId: Uuid): Pair<PhotoAdd, SharedAlbum>? =
        if (driveId != DEPOSIT_DRIVE) null else lock.withLock { pending.remove(uniqueId) }

    private suspend fun waiting(driveId: Uuid, uniqueId: Uuid): PhotoAdd? =
        if (driveId != DEPOSIT_DRIVE) null else lock.withLock { pending[uniqueId]?.first }

    // The owner's server takes the deposit from its inbox a moment after this one's outbox has sent it.
    private suspend fun confirm(add: PhotoAdd, album: SharedAlbum) {
        for (wait in confirmDelays) {
            delay(wait)
            val there = try {
                landed(album, add.globalTransitId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(e, TAG) { "${add.globalTransitId} not looked for on ${add.owner}'s drive" }
                false
            }
            if (there) return _outcomes.emit(PhotoAddOutcome.Delivered(add))
        }
        _outcomes.emit(PhotoAddOutcome.NotConfirmed(add))
    }

    internal companion object {
        private const val TAG = "PhotoMemberWrites"
        val DEPOSIT_DRIVE: Uuid = SystemDriveConstants.transientTempDrive.alias

        /**
         * [spec], a photo as a backup builds it, made a deposit on [album]'s owner's Photos drive: sent from the transient
         * drive, its key sealed to [sealedTo], tagged with the album, carrying [shares] bound to [globalTransitId].
         */
        fun depositSpec(spec: MediaUploadSpec, album: SharedAlbum, globalTransitId: Uuid, shares: List<ShareRequest>, sealedTo: ByteArray): MediaUploadSpec =
            spec.copy(
                driveId = DEPOSIT_DRIVE,
                metadata = spec.metadata.copy(
                    allowDistribution = true,
                    appData = spec.metadata.appData.copy(tags = listOf(album.id)),
                ),
                transit = TransitOptions(
                    recipients = listOf(album.owner),
                    isTransient = true,
                    remoteTargetDrive = TargetDrive(PhotobaseProtocol.DriveAlias, PhotobaseProtocol.DriveType),
                ),
                sealedTo = sealedTo,
                shares = shares,
                globalTransitId = globalTransitId,
                // The owner already having it is a refusal there, not a success here.
                existingFileIsSuccess = false,
                priority = 1,
                sendNow = true,
            )
    }
}
