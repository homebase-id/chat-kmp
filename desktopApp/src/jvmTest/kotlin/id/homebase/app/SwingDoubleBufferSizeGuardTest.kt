package id.homebase.app

import java.awt.Component
import java.awt.Dimension
import javax.swing.RepaintManager
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// The guard's whole effect is on RepaintManager state that has no public getter distinguishable
// from the headless default, so the assertions read two JDK-private members by reflection
// (see the --add-opens on the test task). If either lookup starts failing, re-read
// RepaintManager.displayChanged() on the new JDK before deleting this test.
class SwingDoubleBufferSizeGuardTest {

    @Test
    fun `a display change leaves the double buffer ceiling intact`() {
        installSwingDoubleBufferSizeGuard()
        val manager = RepaintManager.currentManager(null as Component?)

        RepaintManager::class.java.getDeclaredMethod("displayChanged")
            .apply { isAccessible = true }
            .invoke(manager)

        val ceiling = RepaintManager::class.java.getDeclaredField("doubleBufferMaxSize")
            .apply { isAccessible = true }
            .get(manager) as Dimension?

        assertNotNull(
            ceiling,
            "displayChanged() cleared the ceiling, so the next paint recomputes it from " +
                "GraphicsEnvironment.getScreenDevices() and can cache 0x0",
        )
        assertTrue(
            ceiling.width > 0 && ceiling.height > 0,
            "a 0-sized ceiling clamps every double-buffer request to 0x0: $ceiling",
        )
    }
}
