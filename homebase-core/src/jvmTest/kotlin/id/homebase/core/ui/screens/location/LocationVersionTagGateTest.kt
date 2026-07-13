package id.homebase.core.ui.screens.location

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Locks the create-vs-update gate (#1077): an hour file is only *updatable* when it carries a
 * usable versionTag. A local index row whose CREATE never landed server-side (offline) has none;
 * updating it sends a null tag → 400 MissingVersionTag → permanent-drop/re-flush loop. Such a file
 * must be routed to create instead, which converges (create coalesces via replace=true and
 * self-heals to an update if the file does exist server-side).
 */
class LocationVersionTagGateTest {

    @Test
    fun nullTagIsNotUsable() {
        // The exact bug: optimistic local row, create never landed, versionTag still null.
        assertFalse(isUsableVersionTag(null))
    }

    @Test
    fun allZeroPlaceholderTagIsNotUsable() {
        assertFalse(isUsableVersionTag(Uuid.NIL))
    }

    @Test
    fun realTagIsUsable() {
        assertTrue(isUsableVersionTag(Uuid.random()))
    }
}
