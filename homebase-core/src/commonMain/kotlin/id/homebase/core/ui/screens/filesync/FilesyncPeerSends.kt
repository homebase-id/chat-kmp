package id.homebase.core.ui.screens.filesync

import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.files.TransferStatus
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.client.peer.PeerDriveQueryProvider
import id.homebase.api.client.websockets.PeerSendResultNotification
import id.homebase.api.common.OdinId
import id.homebase.api.coroutines.supervisedScope
import id.homebase.core.filesync.FilesyncProtocol
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** How a member write went, as far as this app heard. */
sealed interface FilesyncPeerOutcome {
    val send: FilesyncPeerSend

    /** [versionTag] is its owner's copy's now, when known: the one the next edit names. */
    data class Delivered(override val send: FilesyncPeerSend, val versionTag: Uuid? = null) : FilesyncPeerOutcome

    /** Its owner's server hasn't taken it yet; this identity's server tries again. */
    data class Retrying(override val send: FilesyncPeerSend, val status: TransferStatus) : FilesyncPeerOutcome

    data class Refused(override val send: FilesyncPeerSend, val status: TransferStatus) : FilesyncPeerOutcome

    /** Missed while offline, and not there on its owner's drive: refused or lost, this app can't tell which. */
    data class NotConfirmed(override val send: FilesyncPeerSend) : FilesyncPeerOutcome
}

/**
 * The member writes waiting to hear how they went: the result comes on the socket, only to a connected app, so after a
 * reconnect each one this identity's server is done with is looked for on its owner's drive instead. In memory: what
 * was waiting when the app closed is not told.
 */
class FilesyncPeerSends internal constructor(
    eventBus: EventBus,
    /** The global transit ids' versions on [OdinId]'s drive this identity can open now. */
    private val versionsOn: suspend (OdinId, List<Uuid>) -> Map<Uuid, Uuid?>,
    /** Whether this identity's server still holds the send; it throws when it can't say. */
    private val stillPending: suspend (FilesyncPeerSend) -> Boolean,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    constructor(eventBus: EventBus, sharing: FilesyncSharing, peer: PeerDriveQueryProvider) : this(
        eventBus,
        versionsOn = { owner, ids -> sharing.sharedByGlobalTransitId(owner, ids).mapNotNull { (_, h) -> h.globalTransitId?.let { it to h.versionTag } }.toMap() },
        stillPending = { send -> peer.sendStatus(send.owner, FilesyncProtocol.DriveAlias, send.globalTransitId, send.sentVersionTag).pending },
    )

    private class Pending(
        val send: FilesyncPeerSend,
        val onKeyHeaderRefused: (suspend () -> Unit)?,
        val onSettled: () -> Unit,
        val since: TimeMark,
        var lastStatus: TransferStatus? = null,
    )

    private val scope = supervisedScope("filesync-peer-sends")
    private val lock = Mutex()
    private val pending = mutableListOf<Pending>()
    // A result can come before its send's answer does: kept a while for the send it belongs to.
    private val unclaimed = ArrayDeque<PeerSendResultNotification>()

    private val _outcomes = MutableSharedFlow<FilesyncPeerOutcome>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val outcomes: SharedFlow<FilesyncPeerOutcome> = _outcomes.asSharedFlow()

    init {
        scope.launch {
            eventBus.events.collect { event ->
                when (event) {
                    is BackendEvent.PeerSendResult -> heard(event.result)
                    BackendEvent.ConnectionOnline -> lookForMissed()
                    is BackendEvent.SessionEnded -> lock.withLock {
                        pending.onEach { it.onSettled() }.clear()
                        unclaimed.clear()
                    }
                    else -> Unit
                }
            }
        }
    }

    /**
     * Waits for [send]'s result. [onKeyHeaderRefused] sends an edit again sealed, for a file its owner's device hasn't
     * taken in yet; [onSettled] runs once nothing more will be done with it.
     */
    suspend fun track(send: FilesyncPeerSend, onKeyHeaderRefused: (suspend () -> Unit)? = null, onSettled: () -> Unit = {}) {
        val early = lock.withLock {
            pending += Pending(send, onKeyHeaderRefused, onSettled, timeSource.markNow())
            unclaimed.filter { send.answeredBy(it) }.also { unclaimed.removeAll(it) }
        }
        early.forEach { heard(it) }
    }

    internal suspend fun heard(result: PeerSendResultNotification) {
        if (result.driveId != FilesyncProtocol.DriveAlias) return
        val entry = lock.withLock {
            pending.firstOrNull { it.send.answeredBy(result) }.also {
                if (it == null) {
                    unclaimed += result
                    if (unclaimed.size > UNCLAIMED_KEPT) unclaimed.removeFirst()
                }
            }
        } ?: return
        when {
            result.status == TransferStatus.Delivered -> settle(entry, FilesyncPeerOutcome.Delivered(entry.send, result.versionTag))
            result.willRetry -> {
                // A send retried for hours would tell the same thing each time.
                if (entry.lastStatus != result.status) _outcomes.emit(FilesyncPeerOutcome.Retrying(entry.send, result.status))
                entry.lastStatus = result.status
            }
            result.status == TransferStatus.RecipientIdentityReturnedInvalidKeyHeader && entry.onKeyHeaderRefused != null -> {
                lock.withLock { pending.remove(entry) }
                Logger.i(tag = TAG) { "${entry.send.globalTransitId} is still sealed at ${entry.send.owner}; sending it sealed" }
                entry.onKeyHeaderRefused.invoke()
            }
            else -> settle(entry, FilesyncPeerOutcome.Refused(entry.send, result.status))
        }
    }

    internal suspend fun lookForMissed() {
        // A send this new may still have its result on its way: the next reconnect looks at it.
        val waiting = lock.withLock { pending.filter { it.since.elapsedNow() >= MISSED_AFTER } }
        // The outbox first: a send it finishes after the drive is read would otherwise look lost.
        val done = waiting.filter { entry ->
            try {
                !stillPending(entry.send)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(e, TAG) { "${entry.send.globalTransitId}'s send status wasn't read; asked again at the next reconnect" }
                false
            }
        }
        done.groupBy { it.send.owner }.forEach { (owner, entries) ->
            val versions = try {
                versionsOn(owner, entries.map { it.send.globalTransitId })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(e, TAG) { "$owner's copies weren't read; looked for again at the next reconnect" }
                return@forEach
            }
            entries.forEach { entry ->
                val gtid = entry.send.globalTransitId
                val landed = entry.send.landed(gtid in versions, versions[gtid])
                settle(entry, if (landed) FilesyncPeerOutcome.Delivered(entry.send, versions[gtid]) else FilesyncPeerOutcome.NotConfirmed(entry.send))
            }
        }
    }

    private suspend fun settle(entry: Pending, outcome: FilesyncPeerOutcome) {
        val removed = lock.withLock { pending.remove(entry) }
        if (!removed) return
        entry.onSettled()
        _outcomes.emit(outcome)
    }

    private companion object {
        const val TAG = "FilesyncPeerSends"
        const val UNCLAIMED_KEPT = 64
        val MISSED_AFTER = 30.seconds
    }
}

private fun FilesyncPeerSend.answeredBy(result: PeerSendResultNotification): Boolean =
    owner == result.recipient &&
        globalTransitId == result.globalTransitId &&
        isUpdate == result.isUpdate &&
        sentVersionTag == result.sentVersionTag

/** A new file landed when it's there at all; an edit when the copy is at the version it sent, which the owner's server keeps. */
private fun FilesyncPeerSend.landed(exists: Boolean, version: Uuid?): Boolean = when {
    !exists -> false
    !isUpdate -> true
    else -> version == sentVersionTag
}
