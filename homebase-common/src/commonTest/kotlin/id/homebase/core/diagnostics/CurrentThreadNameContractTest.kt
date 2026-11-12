package id.homebase.core.diagnostics

import kotlin.test.Test
import kotlin.test.assertTrue

class CurrentThreadNameContractTest {
    @Test
    fun threadNameIsNeverBlank() {
        assertTrue(currentThreadName().isNotBlank())
    }
}
