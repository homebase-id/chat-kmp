package id.homebase.api.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/** Pinned values: if these change on any platform, every backed-up asset would upload a second time. */
class PhotoUniqueIdTest {
    private val still = ByteArray(32) { it.toByte() }
    private val video = ByteArray(32) { (255 - it).toByte() }

    @Test
    fun aSingleFileIdIsPinned() {
        assertEquals(PINNED_SINGLE, PhotoUniqueId.fromContentDigests(listOf(still)).toString())
    }

    @Test
    fun aLivePhotoIdIsPinnedAndDerivedFromBothParts() {
        val live = PhotoUniqueId.fromContentDigests(listOf(still, video))
        assertEquals(PINNED_LIVE, live.toString())
        assertNotEquals(PhotoUniqueId.fromContentDigests(listOf(still)), live)
        assertNotEquals(PhotoUniqueId.fromContentDigests(listOf(video, still)), live)
    }

    @Test
    fun onlyDigestsAreAccepted() {
        assertFailsWith<IllegalArgumentException> { PhotoUniqueId.fromContentDigests(emptyList()) }
        assertFailsWith<IllegalArgumentException> { PhotoUniqueId.fromContentDigests(listOf(ByteArray(16))) }
    }

    private companion object {
        const val PINNED_SINGLE = "de2fc777-0426-6522-b75a-e7353c9f1a5b"
        const val PINNED_LIVE = "50d23d77-98c8-f3af-b7dc-04b27c01a553"
    }
}
