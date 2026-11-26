package id.homebase.photobase.social

import id.homebase.api.common.OdinId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class PendingCommentsTest {
    private val photo = Uuid.random()
    private val frodo = OdinId("frodo.test")

    private fun comment(at: Long, body: String = "c$at", photoId: Uuid = photo, by: OdinId? = null) =
        PhotoComment(Uuid.random(), Uuid.random(), photoId, by, body, at, globalTransitId = Uuid.random())

    @Test
    fun aPostedCommentShowsUntilTheOwnersDriveHasItThenOnlyItsCopy() {
        val pending = PendingComments()
        val theirs = comment(1, by = frodo)
        val mine = comment(2)
        pending.posted(mine)

        assertEquals(listOf(theirs, mine), pending.over(photo, listOf(theirs)), "not there yet")
        val stored = mine.copy(fileId = Uuid.random())
        assertEquals(listOf(theirs, stored), pending.over(photo, listOf(theirs, stored)), "arrived: the drive's copy")
        assertEquals(listOf(theirs), pending.over(photo, listOf(theirs)), "no longer held once it arrived")
        assertEquals(emptyList(), pending.over(Uuid.random(), emptyList()), "another photo's isn't shown")
    }

    @Test
    fun anEditShowsItsBodyUntilTheDriveHasIt() {
        val pending = PendingComments()
        val mine = comment(1, body = "Nice")
        pending.edited(mine.id, "Nice light")

        assertEquals(listOf(mine.copy(body = "Nice light")), pending.over(photo, listOf(mine)))
        assertEquals(listOf(mine.copy(body = "Nice light")), pending.over(photo, listOf(mine.copy(body = "Nice light"))))
        assertEquals(listOf(mine.copy(body = "Later")), pending.over(photo, listOf(mine.copy(body = "Later"))), "held no longer, so a later edit elsewhere shows")
    }

    @Test
    fun aDeletedCommentStaysHiddenWhileTheDriveStillHasIt() {
        val pending = PendingComments()
        val mine = comment(1)
        pending.deleted(mine.id)

        assertEquals(emptyList(), pending.over(photo, listOf(mine)))
        assertEquals(emptyList(), pending.over(photo, emptyList()))
    }

    @Test
    fun deletingACommentStillOnItsWayTakesItOutAtOnce() {
        val pending = PendingComments()
        val mine = comment(1)
        pending.posted(mine)
        pending.deleted(mine.id)

        assertEquals(emptyList(), pending.over(photo, emptyList()))
    }
}
