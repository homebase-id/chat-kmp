package id.homebase.photobase.gallery

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.DayCount
import id.homebase.api.sync.database.CountedDay
import id.homebase.core.photobase.PhotobaseProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import kotlin.time.TimeSource

/** The library's days that have photos, newest first, and how many each has. [days] count days since 1970-01-01 of the local date. */
class PhotoDays(val days: IntArray, val counts: IntArray) {
    val photos: Int = counts.sum()
}

/**
 * The library's days held in memory: built when the gallery first wants them, kept current while it shows them, and
 * only marked stale while it doesn't, so a reconnect or a background sync costs nothing.
 */
class PhotoDayIndex(
    private val dayCounts: suspend () -> List<DayCount>,
    private val dayOf: suspend (userDate: Long) -> CountedDay,
    // The identity and time zone the days were counted for: either changing means counting again.
    private val countedFor: suspend () -> String,
    changes: Flow<PhotoChange>,
    scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val current = MutableStateFlow<PhotoDays?>(null)
    private val watchers = MutableStateFlow(0)
    private var stale = true
    private var builtFor: String? = null

    init {
        scope.launch { changes.collect { change -> mutex.withLock { apply(change) } } }
    }

    /** The days, counted on first collection if they aren't current, and kept current for as long as they're collected. */
    val days: Flow<PhotoDays> = flow {
        watchers.update { it + 1 }
        try {
            mutex.withLock { if (stale || builtFor != countedFor()) rebuild() }
            emitAll(current.filterNotNull())
        } finally {
            watchers.update { it - 1 }
        }
    }

    /** Photobase is gone: count again from scratch next time. */
    suspend fun forget() = mutex.withLock {
        current.value = null
        stale = true
    }

    private suspend fun apply(change: PhotoChange) {
        if (stale || watchers.value == 0) {
            stale = true
            return
        }
        try {
            when (change) {
                PhotoChange.RoundFinished -> rebuild()
                // A count per day, not +1/-1: the event has only the file's new state, and a recount can't drift.
                // Once per day: a busy day takes tens of milliseconds to count.
                is PhotoChange.FilesArrived -> {
                    val counted = mutableListOf<LongRange>()
                    for (userDate in change.userDates) {
                        if (counted.any { userDate in it }) continue
                        val day = dayOf(userDate)
                        counted += day.userDates
                        put(day.count)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(tag = TAG, throwable = e) { "couldn't update the day index; counting again next time" }
            stale = true
        }
    }

    private suspend fun rebuild() {
        val started = TimeSource.Monotonic.markNow()
        val key = countedFor()
        val counted = dayCounts()
        current.value = PhotoDays(IntArray(counted.size) { counted[it].day }, IntArray(counted.size) { counted[it].photos })
        builtFor = key
        stale = false
        Logger.i(tag = TAG) { "counted ${counted.size} days, ${current.value?.photos} photos in ${started.elapsedNow()}" }
    }

    private fun put(day: DayCount) {
        val held = current.value ?: return
        val at = held.days.binarySearchDescending(day.day)
        val days: IntArray
        val counts: IntArray
        when {
            at >= 0 && day.photos == 0 -> {
                days = held.days.copyOfRange(0, at) + held.days.copyOfRange(at + 1, held.days.size)
                counts = held.counts.copyOfRange(0, at) + held.counts.copyOfRange(at + 1, held.counts.size)
            }
            at >= 0 -> {
                if (held.counts[at] == day.photos) return
                days = held.days
                counts = held.counts.copyOf().also { it[at] = day.photos }
            }
            day.photos == 0 -> return
            else -> {
                val insert = -at - 1
                days = held.days.copyOfRange(0, insert) + day.day + held.days.copyOfRange(insert, held.days.size)
                counts = held.counts.copyOfRange(0, insert) + day.photos + held.counts.copyOfRange(insert, held.counts.size)
            }
        }
        current.value = PhotoDays(days, counts)
    }

    private companion object {
        const val TAG = "PhotoDayIndex"
    }
}

/** Like [IntArray.binarySearch] on an array sorted largest first. */
internal fun IntArray.binarySearchDescending(value: Int): Int {
    var low = 0
    var high = size - 1
    while (low <= high) {
        val mid = (low + high) ushr 1
        when {
            this[mid] > value -> low = mid + 1
            this[mid] < value -> high = mid - 1
            else -> return mid
        }
    }
    return -(low + 1)
}

fun photoDayIndex(db: DatabaseManager, credentials: CredentialsManager, changes: Flow<PhotoChange>, scope: CoroutineScope): PhotoDayIndex {
    val identity = suspend { credentials.requireActiveCredentials().getIdentityId() }
    val drive = PhotobaseProtocol.DriveAlias
    val type = PhotobaseProtocol.PhotoFileType.toLong()
    return PhotoDayIndex(
        dayCounts = { db.photoDays.dayCounts(identity(), drive, type) },
        dayOf = { db.photoDays.dayOf(identity(), drive, type, it) },
        countedFor = { "${identity()} ${TimeZone.currentSystemDefault().id}" },
        changes = changes,
        scope = scope,
    )
}
