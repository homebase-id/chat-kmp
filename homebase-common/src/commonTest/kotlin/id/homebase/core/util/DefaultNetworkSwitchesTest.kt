package id.homebase.core.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

class DefaultNetworkSwitchesTest {
    private suspend fun switches(vararg networks: String?) = flowOf(*networks).defaultNetworkSwitches().toList().size

    @Test
    fun theNetworkAtCollectionTimeDoesNotEmit() = runTest {
        assertEquals(0, switches("wifi"))
        assertEquals(0, switches("wifi", "wifi"))
    }

    @Test
    fun eachMoveToAnotherNetworkEmits() = runTest {
        assertEquals(2, switches("wifi", "cell", "wifi"))
    }

    @Test
    fun losingTheNetworkDoesNotEmitButANetworkAfterNoneDoes() = runTest {
        assertEquals(0, switches("wifi", null))
        assertEquals(1, switches("wifi", null, "wifi"))
        assertEquals(1, switches(null, "cell"))
    }
}
