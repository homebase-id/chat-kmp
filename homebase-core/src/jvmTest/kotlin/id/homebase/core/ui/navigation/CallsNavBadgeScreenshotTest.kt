package id.homebase.core.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import id.homebase.core.ui.theme.HomebaseTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class CallsNavBadgeScreenshotTest {
    private val outDir = File("build/calls-badge-shots").apply { mkdirs() }
    private val dot = hasContentDescription("Missed calls")

    private fun ComposeUiTest.save(name: String) {
        val image = onAllNodes(isRoot())[0].captureToImage().toAwtImage()
        val colors = HashSet<Int>()
        for (x in 0 until image.width step 5) for (y in 0 until image.height step 5) colors += image.getRGB(x, y)
        assertTrue(colors.size > 8, "$name rendered blank")
        ImageIO.write(image, "png", File(outDir, "$name.png"))
    }

    private fun ComposeUiTest.bottomBar(badge: Boolean, dark: Boolean = false) = setContent {
        HomebaseTheme(darkTheme = dark, followsSystemTheme = false) {
            ShortNavigationBar {
                listOf(TopLevelRoute.Chat, TopLevelRoute.Calls, TopLevelRoute.Feed).forEach { route ->
                    ShortNavigationBarItem(
                        icon = { TopLevelNavIcon(route, showMomentsBadge = false, showCallsBadge = badge) },
                        label = { Text("x") },
                        selected = route == TopLevelRoute.Chat,
                        onClick = {},
                    )
                }
            }
        }
    }

    private fun ComposeUiTest.rail(badge: Boolean) = setContent {
        HomebaseTheme(darkTheme = false, followsSystemTheme = false) {
            NavigationRail {
                Column {
                    listOf(TopLevelRoute.Chat, TopLevelRoute.Calls, TopLevelRoute.Feed).forEach { route ->
                        RailItem(route, selected = route == TopLevelRoute.Chat, showMomentsBadge = false, showCallsBadge = badge, showEmailBadge = false, showLocationBadge = false, onClick = {})
                    }
                }
            }
        }
    }

    @Test
    fun bottomBarShowsDotOnlyWhenMissed() {
        runDesktopComposeUiTest(411, 200) { bottomBar(badge = true); onNode(dot).assertExists(); save("bar-dot") }
        runDesktopComposeUiTest(411, 200) { bottomBar(badge = false); onNode(dot).assertDoesNotExist(); save("bar-nodot") }
        runDesktopComposeUiTest(411, 200) { bottomBar(badge = true, dark = true); onNode(dot).assertExists(); save("bar-dot-dark") }
    }

    @Test
    fun railShowsDotOnlyWhenMissed() {
        runDesktopComposeUiTest(200, 400) { rail(badge = true); onNode(dot).assertExists(); save("rail-dot") }
        runDesktopComposeUiTest(200, 400) { rail(badge = false); onNode(dot).assertDoesNotExist(); save("rail-nodot") }
    }
}
