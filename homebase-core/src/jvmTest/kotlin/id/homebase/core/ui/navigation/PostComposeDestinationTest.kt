package id.homebase.core.ui.navigation

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class PostComposeDestinationTest {
    @Test
    fun `post composer is a pane destination so wide windows get a dialog`() {
        val navHost = File("src/commonMain/kotlin/id/homebase/core/ui/navigation/AppNavHost.kt").readText()
        assertTrue("paneDestination<Route.PostCompose>" in navHost)
        assertTrue("composable<Route.PostCompose>" !in navHost)
    }
}
