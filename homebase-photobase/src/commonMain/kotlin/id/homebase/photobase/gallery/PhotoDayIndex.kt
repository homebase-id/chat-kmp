package id.homebase.photobase.gallery

import co.touchlab.kermit.Logger
import id.homebase.api.sync.database.CountedDay
import id.homebase.api.sync.database.DayCount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

/** The library's days that have photos, newest first, and how many each has. */
class LibraryDays(val days: IntArray, val counts: IntArray) {
    val photos: Int get() = counts.sum()
}

/**
 * The library's days held in memory: built when the gallery first wants them, kept current while it shows them, and
 * only marked stale while it doesn't, so a reconnect or a background sync costs nothing.
 */
class PhotoDayIndex(
    private val dayCounts: suspend () -> List<DayCount>,
    private val dayOf: suspend (userDate: Long) -> CountedDay?,
    // The identity and time zone the days were counted for: either changing means counting again.
    private val countedFor: suspend () -> String,
    changes: Flow<PhotoChange>,
    scope: CoroutineScope,
    private val recountGap: Duration = 2.seconds,
) {
    private val mutex = Mutex()
    private val current = MutableStateFlow<LibraryDays?>(null)
    // Null: count again before the next look.
    private var builtFor: String? = null
    private val arrived = mutableListOf<Long>()
    private val recount = Channel<Unit>(Channel.CONFLATED)

    init {
        // Caught per change: reading the key throws while nobody is signed in, and must not end the collection.
        scope.launch { changes.collect { change -> mutex.withLock { orCountAgainLater { note(change) } } } }
        scope.launch {
            for (signal in recount) {
                mutex.withLock { recountArrived() }
                // A backup brings a photo every second or so, often to one busy day that takes tens of ms to count.
                delay(recountGap)
            }
        }
    }

    // A failed count leaves the days stale; the next change while the screen watches counts them again.
    val days: Flow<LibraryDays> = current
        .onSubscription { mutex.withLock { orCountAgainLater { if (builtFor != countedFor()) rebuild() } } }
        .filterNotNull()

    /** Photobase is gone: count again from scratch next time. */
    suspend fun forget() = mutex.withLock {
        current.value = LibraryDays(IntArray(0), IntArray(0))
        builtFor = null
        arrived.clear()
    }

    private suspend fun note(change: PhotoChange) {
        if (current.subscriptionCount.value == 0) {
            builtFor = null
            arrived.clear()
            return
        }
        // After a failure, a forget, or a new time zone: on screen, so count it all now.
        if (builtFor == null || builtFor != countedFor()) {
            arrived.clear()
            orCountAgainLater { rebuild() }
            return
        }
        when (change) {
            PhotoChange.RoundFinished -> {
                arrived.clear()
                orCountAgainLater { rebuild() }
            }
            is PhotoChange.FilesArrived -> {
                arrived += change.userDates
                recount.trySend(Unit)
            }
        }
    }

    private suspend fun recountArrived() {
        if (builtFor == null || arrived.isEmpty()) return
        val dates = arrived.toList()
        arrived.clear()
        orCountAgainLater {
            // A catch-up after time offline: one count of the whole library beats hundreds of days. A new time zone
            // since the count: days counted now mustn't go in among the old ones.
            if (dates.size > REBUILD_ABOVE || builtFor != countedFor()) return@orCountAgainLater rebuild()
            val spans = mutableListOf<LongRange>()
            val counted = mutableListOf<DayCount>()
            for (userDate in dates) {
                if (spans.any { userDate in it }) continue
                val day = dayOf(userDate) ?: continue
                spans += day.userDates
                counted += day.count
            }
            put(counted)
        }
    }

    private suspend fun rebuild() {
        val key = countedFor()
        val (counted, took) = measureTimedValue { dayCounts() }
        val days = IntArray(counted.size) { counted[it].day }
        val counts = IntArray(counted.size) { counted[it].photos }
        val held = current.value
        if (held == null || !held.days.contentEquals(days) || !held.counts.contentEquals(counts)) current.value = LibraryDays(days, counts)
        builtFor = key
        Logger.i(tag = TAG) { "counted ${counted.size} days, ${counted.sumOf { it.photos }} photos in $took" }
    }

    private fun put(counted: List<DayCount>) {
        val held = current.value ?: return
        val days = held.days.toMutableList()
        val counts = held.counts.toMutableList()
        var changed = false
        for (day in counted) {
            // Newest first, so the comparison runs the other way.
            val at = days.binarySearch { day.day.compareTo(it) }
            when {
                at >= 0 && day.photos == 0 -> { days.removeAt(at); counts.removeAt(at) }
                at >= 0 && counts[at] != day.photos -> counts[at] = day.photos
                at < 0 && day.photos > 0 -> { days.add(-at - 1, day.day); counts.add(-at - 1, day.photos) }
                else -> continue
            }
            changed = true
        }
        if (changed) current.value = LibraryDays(days.toIntArray(), counts.toIntArray())
    }

    private suspend fun orCountAgainLater(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(tag = TAG, throwable = e) { "couldn't update the day index; counting again next time" }
            builtFor = null
        }
    }

    private companion object {
        const val TAG = "PhotoDayIndex"
        const val REBUILD_ABOVE = 200
    }
}

fun photoDayIndex(store: PhotoGalleryStore, changes: Flow<PhotoChange>, scope: CoroutineScope) = PhotoDayIndex(
    dayCounts = store::dayCounts,
    dayOf = store::dayOf,
    countedFor = store::daysCountedFor,
    changes = changes,
    scope = scope,
)
