package id.homebase.app.diagnostics

import java.awt.EventQueue
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EdtHitchMonitorTest {

    @Test
    fun reportsAHitchAfterFocusGainButNotOneBelowTheThreshold() {
        val logged = Collections.synchronizedList(mutableListOf<String>())
        EdtHitchMonitor(log = { logged += it }).start()
        Thread.sleep(300)

        EventQueue.invokeAndWait { Thread.sleep(200) }
        Thread.sleep(600)
        assertEquals(emptyList(), logged.toList())

        EdtHitchMonitor.onFocusGained()
        EventQueue.invokeAndWait { Thread.sleep(1_000) }
        Thread.sleep(600)

        assertEquals(1, logged.size, logged.joinToString("\n---\n"))
        val warn = logged.single()
        val hitchMs = Regex("""EDT hitch: (\d+)ms""").find(warn)!!.groupValues[1].toLong()
        assertTrue(hitchMs >= 500, warn)
        assertTrue("after focus gain" in warn, warn)
        assertTrue("AWT-EventQueue" in warn, warn)
        assertTrue("EdtHitchMonitorTest" in warn, warn)
    }
}
