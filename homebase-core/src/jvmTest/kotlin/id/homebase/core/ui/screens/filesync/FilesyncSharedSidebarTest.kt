package id.homebase.core.ui.screens.filesync

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import id.homebase.core.ui.navigation.LocalNavAnimatedVisibilityScope
import id.homebase.core.ui.navigation.LocalSharedTransitionScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The routes slide here on purpose: an unshared sidebar would slide with them.
@OptIn(ExperimentalTestApi::class, ExperimentalSharedTransitionApi::class)
class FilesyncSharedSidebarTest {

    @Test
    fun theSidebarStaysPutWhileThePlaceChanges() = runComposeUiTest {
        lateinit var nav: NavHostController
        val places = FilesyncPlaces(onFiles = {}, onRecent = { nav.navigate("recent") }, onTrash = {})
        setContent {
            nav = rememberNavController()
            val snackbar = remember { SnackbarHostState() }
            MaterialTheme {
                SharedTransitionLayout {
                    CompositionLocalProvider(LocalSharedTransitionScope provides this) {
                        NavHost(
                            navController = nav,
                            startDestination = "files",
                            enterTransition = { slideInHorizontally(tween(1000)) { it } },
                            exitTransition = { slideOutHorizontally(tween(1000)) { -it } },
                        ) {
                            composable("files") {
                                CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                                    FilesyncShell(FilesyncPlace.Files, places, snackbar, title = { Text("Files body") }) { Text("files") }
                                }
                            }
                            composable("recent") {
                                CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                                    FilesyncShell(FilesyncPlace.Recent, places, snackbar, title = { Text("Recent body") }) { Text("recent") }
                                }
                            }
                        }
                    }
                }
            }
        }
        waitForIdle()
        val before = onAllNodesWithText("All files").fetchSemanticsNodes().single().boundsInRoot

        mainClock.autoAdvance = false
        runOnIdle { places.onRecent() }
        mainClock.advanceTimeBy(400)

        // The copy the shared element hides has no size; the one drawn must be where it was.
        val drawn = onAllNodesWithText("All files").fetchSemanticsNodes().filter { it.boundsInRoot.width > 0f }
        assertTrue(drawn.isNotEmpty())
        drawn.forEach { assertEquals(before, it.boundsInRoot, "the sidebar moved mid-transition") }
    }
}
