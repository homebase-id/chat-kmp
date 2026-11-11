package id.homebase.photobase

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PhotobaseUninstallCleanupTest {
    private var forgets = 0

    private fun kotlinx.coroutines.test.TestScope.cleanup(isInstalled: suspend () -> Boolean) =
        PhotobaseUninstallCleanup(isInstalled, { forgets++ }, backgroundScope)

    @Test
    fun notRegisteredForgetsThePhonesCopy() = runTest {
        cleanup { false }.forgetIfUninstalled()
        assertEquals(1, forgets)
    }

    @Test
    fun stillRegisteredKeepsIt() = runTest {
        cleanup { true }.forgetIfUninstalled()
        assertEquals(0, forgets)
    }

    @Test
    fun aCheckThatFailsKeepsIt() = runTest {
        cleanup { error("offline") }.forgetIfUninstalled()
        assertEquals(0, forgets)
    }
}
