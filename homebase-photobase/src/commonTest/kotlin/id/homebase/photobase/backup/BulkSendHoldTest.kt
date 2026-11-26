package id.homebase.photobase.backup

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class BulkSendHoldTest {
    private class NetworkOnlyProbe(private val unmetered: Boolean) : BackupDeviceProbe {
        override suspend fun state(): DeviceState = error("the hold must not read battery or storage")
        override fun libraryAccess() = LibraryAccess.Full
        override suspend fun isUnmetered() = unmetered
    }

    @Test
    fun wifiOnlyHoldsOnAMeteredNetworkAskingOnlyTheNetwork() = runTest {
        assertEquals(true, NetworkOnlyProbe(unmetered = false).holdsBulkSends(BackupSettings(wifiOnly = true)))
        assertEquals(false, NetworkOnlyProbe(unmetered = true).holdsBulkSends(BackupSettings(wifiOnly = true)))
        assertEquals(false, NetworkOnlyProbe(unmetered = false).holdsBulkSends(BackupSettings(wifiOnly = false)))
    }
}
