package id.homebase.core.ui.navigation

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class PostComposeDestinationTest {
    private val navHost = File("src/commonMain/kotlin/id/homebase/core/ui/navigation/AppNavHost.kt").readText()

    @Test
    fun `post composer is a pane destination so wide windows get a dialog`() {
        assertTrue("paneDestination<Route.PostCompose>" in navHost)
    }

    @Test
    fun `post composer is not a plain full-screen composable on every platform`() {
        assertTrue("composable<Route.PostCompose>" !in navHost)
    }
}
