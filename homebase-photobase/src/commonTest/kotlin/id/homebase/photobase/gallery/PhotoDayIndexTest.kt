package id.homebase.photobase.gallery

import id.homebase.api.sync.database.DayCount
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class PhotoDayIndexTest {
    /** A library in a database: day -> photos, read the way the real queries read it. */
    private class Library {
        val days = mutableMapOf(20 to 3, 18 to 1, 15 to 7)
        var builds = 0
        var failDayOf = false
        val recounted = mutableListOf<Int>()
        var countedFor = "frodo UTC"
        var signedOut = false

        fun index(scope: TestScope, changes: MutableSharedFlow<PhotoChange>) = PhotoDayIndex(
            dayCounts = { builds++; days.entries.sortedByDescending { it.key }.map { DayCount(it.key, it.value) } },
            dayOf = { userDate ->
                check(!failDayOf) { "database closed" }
                if (userDate == NO_DAY) null else (userDate / DAY_MS).toInt()
            },
            photosOn = { day ->
                recounted += day
                days[day] ?: 0
            },
            countedFor = { check(!signedOut) { "no active credentials" }; countedFor },
            changes = changes,
            scope = scope.backgroundScope,
        )
    }

    private fun TestScope.setUp(): Triple<Library, MutableSharedFlow<PhotoChange>, PhotoDayIndex> {
        val library = Library()
        val changes = MutableSharedFlow<PhotoChange>()
        val index = library.index(this, changes)
        runCurrent()
        return Triple(library, changes, index)
    }

    @Test
    fun itCountsOnFirstLookAndNotAgainWhileNothingChanged() = runTest {
        val (library, _, index) = setUp()
        assertEquals(0, library.builds, "nothing is counted until the gallery wants it")

        val days = index.days.first()
        assertContentEquals(intArrayOf(20, 18, 15), days.days)
        assertContentEquals(intArrayOf(3, 1, 7), days.counts)
        assertEquals(11, days.photos)

        index.days.first()
        assertEquals(1, library.builds)
    }

    @Test
    fun whileNobodyLooksAChangeOnlyMarksItStale() = runTest {
        val (library, changes, index) = setUp()
        index.days.first()

        library.days[21] = 2
        changes.emit(PhotoChange.FilesArrived(listOf(21 * DAY_MS)))
        changes.emit(PhotoChange.RoundFinished)
        runCurrent()
        assertEquals(1, library.builds, "a reconnect or a background sync costs nothing")

        assertContentEquals(intArrayOf(21, 20, 18, 15), index.days.first().days)
        assertEquals(2, library.builds)
    }

    @Test
    fun whileItsShownAnArrivingPhotoRecountsItsDayOnly() = runTest {
        val (library, changes, index) = setUp()
        val shown = mutableListOf<LibraryDays>()
        val watching = launch { index.days.collect { shown += it } }
        runCurrent()

        library.days[19] = 1
        library.days[20] = 4
        library.days.remove(15)
        changes.emit(PhotoChange.FilesArrived(listOf(19 * DAY_MS + 5, 20 * DAY_MS, 15 * DAY_MS, 20 * DAY_MS)))
        runCurrent()

        assertContentEquals(intArrayOf(20, 19, 18), shown.last().days, "a new day goes in its place, an emptied one goes")
        assertContentEquals(intArrayOf(4, 1, 1), shown.last().counts)
        assertEquals(listOf(19, 20, 15), library.recounted, "each day counted once, however many photos arrived on it")
        assertEquals(1, library.builds)
        watching.cancel()
    }

    @Test
    fun whileItsShownAFinishedRoundCountsAgain() = runTest {
        val (library, changes, index) = setUp()
        val watching = launch { index.days.collect { } }
        runCurrent()

        changes.emit(PhotoChange.RoundFinished)
        runCurrent()
        assertEquals(2, library.builds)
        watching.cancel()
    }

    @Test
    fun anotherTimeZoneOrIdentityCountsAgain() = runTest {
        val (library, _, index) = setUp()
        index.days.first()

        library.countedFor = "frodo Europe/Copenhagen"
        index.days.first()
        assertEquals(2, library.builds)
    }

    @Test
    fun aFailedRecountCountsEverythingAgainOnTheNextChangeWhileShown() = runTest {
        val (library, changes, index) = setUp()
        val watching = launch { index.days.collect { } }
        runCurrent()

        library.failDayOf = true
        changes.emit(PhotoChange.FilesArrived(listOf(20 * DAY_MS)))
        runCurrent()
        library.failDayOf = false
        library.days[21] = 1
        changes.emit(PhotoChange.FilesArrived(listOf(21 * DAY_MS)))
        runCurrent()

        assertEquals(2, library.builds, "the screen mustn't keep stale days for the rest of the visit")
        assertContentEquals(intArrayOf(21, 20, 18, 15), index.days.first().days)
        watching.cancel()
    }

    @Test
    fun aChangeWhileSignedOutDoesntStopItFollowingChanges() = runTest {
        val (library, changes, index) = setUp()
        val watching = launch { index.days.collect { } }
        runCurrent()

        library.signedOut = true
        changes.emit(PhotoChange.RoundFinished)
        runCurrent()
        library.signedOut = false
        library.days[21] = 1
        changes.emit(PhotoChange.RoundFinished)
        runCurrent()

        assertContentEquals(intArrayOf(21, 20, 18, 15), index.days.first().days)
        watching.cancel()
    }

    @Test
    fun forgettingWhileShownShowsNoDays() = runTest {
        val (_, _, index) = setUp()
        val shown = mutableListOf<LibraryDays>()
        val watching = launch { index.days.collect { shown += it } }
        runCurrent()

        index.forget()
        runCurrent()

        assertEquals(0, shown.last().photos)
        watching.cancel()
    }

    @Test
    fun aNewTimeZoneWhileShownCountsEverythingAgain() = runTest {
        val (library, changes, index) = setUp()
        val watching = launch { index.days.collect { } }
        runCurrent()

        library.countedFor = "frodo Asia/Tokyo"
        changes.emit(PhotoChange.FilesArrived(listOf(20 * DAY_MS)))
        runCurrent()

        assertEquals(2, library.builds)
        assertEquals(emptyList(), library.recounted, "a day counted in another zone mustn't go in among the old ones")
        watching.cancel()
    }

    @Test
    fun aCatchUpOfManyPhotosCountsTheLibraryOnceInsteadOfDayByDay() = runTest {
        val (library, changes, index) = setUp()
        val watching = launch { index.days.collect { } }
        runCurrent()

        changes.emit(PhotoChange.FilesArrived(List(201) { it * DAY_MS }))
        runCurrent()

        assertEquals(2, library.builds)
        assertEquals(emptyList(), library.recounted)
        watching.cancel()
    }

    @Test
    fun aPhotoWithNoDayIsLeftOut() = runTest {
        val (library, changes, index) = setUp()
        val watching = launch { index.days.collect { } }
        runCurrent()
        library.days[19] = 1

        changes.emit(PhotoChange.FilesArrived(listOf(NO_DAY, 19 * DAY_MS)))
        runCurrent()

        assertContentEquals(intArrayOf(20, 19, 18, 15), index.days.first().days)
        watching.cancel()
    }

    @Test
    fun forgettingStartsFromScratch() = runTest {
        val (library, _, index) = setUp()
        index.days.first()
        index.forget()
        library.days.clear()

        assertEquals(0, index.days.first().photos)
        assertEquals(2, library.builds)
    }

    @Test
    fun aBackupsBurstOfPhotosCountsTheirDayOncePerGap() = runTest {
        val (library, changes, index) = setUp()
        val watching = launch { index.days.collect { } }
        runCurrent()

        repeat(5) {
            library.days[20] = 4 + it
            changes.emit(PhotoChange.FilesArrived(listOf(20 * DAY_MS + it)))
            runCurrent()
        }
        assertEquals(listOf(20), library.recounted, "the first photo counts at once; the rest wait for the gap")

        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(listOf(20, 20), library.recounted)
        assertEquals(8, index.days.first().counts[0])
        watching.cancel()
    }

    private companion object {
        const val DAY_MS = 86_400_000L
        const val NO_DAY = Long.MAX_VALUE
    }
}
