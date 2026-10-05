package id.homebase.core.updater

import dev.hydraulic.conveyor.control.SoftwareUpdateController
import dev.hydraulic.conveyor.control.SoftwareUpdateController.Availability
import dev.hydraulic.conveyor.control.SoftwareUpdateController.Version
import id.homebase.core.util.PlatformInfo
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import java.awt.EventQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JvmUpdateAppManagerEdtTest {

    private data class FakeVersion(private val v: String) : Version {
        override fun getVersion() = v
        override fun getRevision() = 0
        override fun compareTo(other: Version) = v.compareTo(other.version)
    }

    private class SlowRepositoryController : SoftwareUpdateController {
        @Volatile var repositoryThread: String? = null
        override fun triggerUpdateCheckUI() = Unit
        override fun getCurrentVersion(): Version = FakeVersion("1.0.0")
        override fun getCurrentVersionFromRepository(): Version {
            repositoryThread = Thread.currentThread().name
            Thread.sleep(2_000)
            return FakeVersion("1.0.1")
        }
        override fun canTriggerUpdateCheckUI() = Availability.AVAILABLE
    }

    private val platformInfo = object : PlatformInfo {
        override val versionName = "1.0.0"
        override val versionCode = 1
        override val supportsBackgroundWake = false
    }

    @Test
    fun repositoryCheckCalledFromTheEdtDoesNotBlockTheEdt() {
        val controller = SlowRepositoryController()
        val manager = JvmUpdateAppManager(
            httpClient = HttpClient(MockEngine { respondError(HttpStatusCode.NotFound) }),
            platformInfo = platformInfo,
            controller = controller,
            isLinux = false,
        )

        // Toolkit start-up on the first post takes ~300ms; keep it out of the measurement.
        EventQueue.invokeAndWait {}
        var sentinelLatencyMs = -1L
        val prober = thread {
            Thread.sleep(200)
            val ran = CountDownLatch(1)
            val postedAt = System.nanoTime()
            EventQueue.invokeLater { ran.countDown() }
            ran.await(5, TimeUnit.SECONDS)
            sentinelLatencyMs = (System.nanoTime() - postedAt) / 1_000_000
        }

        val result = runBlocking(Dispatchers.Swing) { manager.checkForUpdate() }
        prober.join()

        assertFalse(
            controller.repositoryThread.orEmpty().startsWith("AWT-EventQueue"),
            "repository read ran on ${controller.repositoryThread}",
        )
        assertTrue(sentinelLatencyMs in 0 until 100, "EDT blocked for ${sentinelLatencyMs}ms")
        assertTrue(result.updateAvailable)
        assertEquals("1.0.1", result.versionName)
    }
}
