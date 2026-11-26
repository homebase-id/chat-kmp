package id.homebase.photobase.ui

import id.homebase.api.common.OdinId
import kotlin.test.Test
import kotlin.test.assertEquals

class MergeSharersTest {
    private val sam = OdinId("sam.test")
    private val pippin = OdinId("pippin.test")
    private val merry = OdinId("merry.test")

    @Test
    fun aRefreshKeepsWhatsBeenReadAndReadsOnlyTheNewAndTheUnread() {
        val read = SharerAlbums(sam, albums = emptyList())
        val failed = SharerAlbums(pippin, problem = SharedProblem.Failed)

        val (shown, toRead) = mergeSharers(listOf(read, failed), listOf(merry, sam, pippin))

        assertEquals(listOf(SharerAlbums(merry), read, failed), shown, "in the sharers' order, sam's albums kept")
        assertEquals(listOf(merry, pippin), toRead)
    }

    @Test
    fun theFirstReadReadsEveryone() {
        assertEquals(listOf(sam, pippin), mergeSharers(null, listOf(sam, pippin)).second)
    }
}
