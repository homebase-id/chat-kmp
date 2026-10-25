package id.homebase.core.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecureFlagRefCounterTest {
    @Test
    fun twoAcquiresThenOneReleaseKeepsTheFlag() {
        val c = SecureFlagRefCounter()
        assertTrue(c.acquire(flagAlreadySet = false), "first user sets it")
        assertFalse(c.acquire(flagAlreadySet = true), "second user does not set it again")
        assertFalse(c.release(), "one holder remains")
        assertTrue(c.isHeld)
    }

    @Test
    fun theLastReleaseClears() {
        val c = SecureFlagRefCounter()
        c.acquire(false)
        c.acquire(true)
        c.release()
        assertTrue(c.release())
        assertFalse(c.isHeld)
    }

    @Test
    fun aFlagThatWasAlreadyThereIsNeverCleared() {
        val c = SecureFlagRefCounter()
        assertFalse(c.acquire(flagAlreadySet = true))
        assertFalse(c.release())
    }

    @Test
    fun aStrayReleaseIsHarmlessAndTheCounterIsReusable() {
        val c = SecureFlagRefCounter()
        assertFalse(c.release())
        assertTrue(c.acquire(false))
        assertTrue(c.release())
        assertEquals(false, c.isHeld)
    }
}
