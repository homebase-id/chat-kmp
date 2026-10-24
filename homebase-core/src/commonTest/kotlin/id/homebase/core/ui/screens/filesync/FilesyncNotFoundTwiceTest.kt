package id.homebase.core.ui.screens.filesync

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

class FilesyncNotFoundTwiceTest {

    private var now = Instant.fromEpochSeconds(1_800_000_000)
    private val notFound = FilesyncNotFoundTwice(object : Clock {
        override fun now() = now
    })
    private val file = Uuid.random()
    private val version = Uuid.random()

    private fun after(time: Duration) {
        now += time
    }

    @Test
    fun aSecondNotFoundAMinuteToAnHourOnConfirmsIt() {
        assertFalse(notFound.confirms(file, version))
        after(30.seconds)
        assertFalse(notFound.confirms(file, version), "too soon")
        after(30.seconds)
        assertTrue(notFound.confirms(file, version))
    }

    @Test
    fun overAnHourOnItStartsAgain() {
        assertFalse(notFound.confirms(file, version))
        after(2.hours)
        assertFalse(notFound.confirms(file, version))
        after(1.minutes)
        assertTrue(notFound.confirms(file, version))
    }

    @Test
    fun anotherVersionOrAFoundHeaderClearsTheDoubt() {
        assertFalse(notFound.confirms(file, version))
        after(2.minutes)
        assertFalse(notFound.confirms(file, Uuid.random()), "another version")
        notFound.found(file)
        after(2.minutes)
        assertFalse(notFound.confirms(file, version), "found meanwhile")
    }
}
