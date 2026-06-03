package id.homebase.core.haptics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class FakeHaptics : Haptics {
    val received = mutableListOf<HapticEvent>()
    override fun perform(event: HapticEvent) {
        received.add(event)
    }
}

class GatedHapticsTest {

    @Test
    fun forwardsEventWhenEnabled() {
        val fake = FakeHaptics()
        val haptics = GatedHaptics(delegate = fake, enabled = { true })

        haptics.perform(HapticEvent.LongPress)

        assertEquals(listOf(HapticEvent.LongPress), fake.received)
    }

    @Test
    fun suppressesEventWhenDisabled() {
        val fake = FakeHaptics()
        val haptics = GatedHaptics(delegate = fake, enabled = { false })

        haptics.perform(HapticEvent.Confirm)

        assertTrue(fake.received.isEmpty())
    }

    @Test
    fun reEvaluatesEnabledOnEachPerform() {
        val fake = FakeHaptics()
        var on = false
        val haptics = GatedHaptics(delegate = fake, enabled = { on })

        haptics.perform(HapticEvent.Selection)   // off -> suppressed
        on = true
        haptics.perform(HapticEvent.Selection)   // on -> forwarded

        assertEquals(listOf(HapticEvent.Selection), fake.received)
    }
}
