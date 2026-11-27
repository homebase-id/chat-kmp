package id.homebase.core.location.map

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PinAddressTest {
    @Test
    fun onlyWhereThePinRestsIsLookedUp() = runTest {
        val asked = mutableListOf<Pair<Double, Double>>()
        val pin = PinAddress(backgroundScope) { lat, lon -> asked += lat to lon; "Tokyo" }

        pin.moved(35.0, 139.0)
        pin.moved(35.5, 139.5)
        assertEquals(true, pin.state.value.resolving)
        settle()

        assertEquals(listOf(35.5 to 139.5), asked)
        assertEquals(PinAddress.State("Tokyo", resolving = false), pin.state.value)
    }

    @Test
    fun aMoveOfAFewMetersIsntLookedUpAgain() = runTest {
        var asked = 0
        val pin = PinAddress(backgroundScope) { _, _ -> asked++; null }

        pin.moved(35.0, 139.0)
        settle()
        pin.moved(35.0001, 139.0)
        settle()

        assertEquals(1, asked)
        assertEquals("35, 139", pin.state.value.address, "no address: its coordinates")
    }

    // advanceUntilIdle leaves backgroundScope's work alone.
    private fun TestScope.settle() {
        advanceTimeBy(1_000)
        runCurrent()
    }

    @Test
    fun coordinatesNearZeroAreNotWrittenAsExponents() = runTest {
        val pin = PinAddress(backgroundScope) { _, _ -> null }

        pin.moved(0.00001, 139.0)
        settle()

        assertEquals("0.00001, 139", pin.state.value.address)
    }
}
