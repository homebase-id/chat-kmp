package id.homebase.photobase.ui

import id.homebase.api.common.OdinId
import kotlin.test.Test
import kotlin.test.assertEquals

class MergeSharersTest {
    private val sam = OdinId("sam.test")
    private val pippin = OdinId("pippin.test")
    private val merry = OdinId("merry.test")
    private val now = 1_000_000L
    private val maxAge = 300_000L

    @Test
    fun aRefreshKeepsWhatsBeenReadAndReadsOnlyTheNewAndTheUnread() {
        val read = SharerAlbums(sam, albums = emptyList(), readAtMs = now - 1_000)
        val failed = SharerAlbums(pippin, problem = SharedProblem.Failed)

        val (shown, toRead) = mergeSharers(listOf(read, failed), listOf(merry, sam, pippin), now, maxAge)

        assertEquals(listOf(SharerAlbums(merry), read, failed), shown, "in the sharers' order, sam's albums kept")
        assertEquals(listOf(merry, pippin), toRead)
    }

    @Test
    fun oneReadAWhileAgoIsReadAgainForWhatTheyveSharedSince() {
        val old = SharerAlbums(sam, albums = emptyList(), readAtMs = now - maxAge - 1)

        val (shown, toRead) = mergeSharers(listOf(old), listOf(sam), now, maxAge)

        assertEquals(listOf(old), shown, "shown as it was until the new read lands")
        assertEquals(listOf(sam), toRead)
    }

    @Test
    fun theFirstReadReadsEveryone() {
        assertEquals(listOf(sam, pippin), mergeSharers(null, listOf(sam, pippin), now, maxAge).second)
    }
}
