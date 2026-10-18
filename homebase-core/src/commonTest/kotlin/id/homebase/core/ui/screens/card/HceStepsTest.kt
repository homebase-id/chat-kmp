package id.homebase.core.ui.screens.card

import id.homebase.core.ui.screens.card.HceStep.Disable
import id.homebase.core.ui.screens.card.HceStep.Enable
import id.homebase.core.ui.screens.card.HceStep.Prefer
import id.homebase.core.ui.screens.card.HceStep.Unprefer
import kotlin.test.Test
import kotlin.test.assertEquals

class HceStepsTest {
    private val url = "https://frodo.dotyou.cloud/card"

    @Test
    fun resumedWithTapShareEnablesBeforePreferring() {
        assertEquals(listOf(Enable, Prefer), hceSteps(resumed = true, servedUrl = url, enabled = false, preferred = false))
    }

    @Test
    fun alreadyServingDoesNothing() {
        assertEquals(emptyList(), hceSteps(resumed = true, servedUrl = url, enabled = true, preferred = true))
    }

    @Test
    fun pausingDisablesWithoutUnpreferring() {
        assertEquals(listOf(Disable), hceSteps(resumed = false, servedUrl = url, enabled = true, preferred = true))
    }

    @Test
    fun tapShareOffWhileResumedUnprefersThenDisables() {
        assertEquals(listOf(Unprefer, Disable), hceSteps(resumed = true, servedUrl = null, enabled = true, preferred = true))
    }

    @Test
    fun leftoverEnabledServiceIsDisabledWhenNotServing() {
        assertEquals(listOf(Disable), hceSteps(resumed = true, servedUrl = null, enabled = true, preferred = false))
    }

    @Test
    fun backgroundedAndDisabledDoesNothing() {
        assertEquals(emptyList(), hceSteps(resumed = false, servedUrl = url, enabled = false, preferred = false))
    }
}
