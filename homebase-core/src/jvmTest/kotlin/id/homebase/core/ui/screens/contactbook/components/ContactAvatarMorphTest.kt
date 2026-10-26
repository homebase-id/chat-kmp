package id.homebase.core.ui.screens.contactbook.components

import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import id.homebase.core.ui.navigation.LocalNavAnimatedVisibilityScope
import id.homebase.core.ui.navigation.SharedTransitionHost
import id.homebase.core.ui.screens.contactbook.model.ContactBookEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class, ExperimentalUuidApi::class)
class ContactAvatarMorphTest {
    private val entry = ContactBookEntry(
        uniqueId = Uuid.random(),
        fileId = Uuid.random(),
        versionTag = null,
        displayName = "Sam Gamgee",
    )

    private fun runMorph(morph: Boolean): Pair<List<Float>, Float> {
        var midLefts = emptyList<Float>()
        var finalLeft = 0f
        runComposeUiTest {
            lateinit var nav: NavHostController
            setContent {
                nav = rememberNavController()
                MaterialTheme {
                    SharedTransitionHost {
                        NavHost(
                            navController = nav,
                            startDestination = "list",
                            enterTransition = { slideInHorizontally(tween(1000)) { it } },
                            exitTransition = { slideOutHorizontally(tween(1000)) { -it } },
                            popEnterTransition = { slideInHorizontally(tween(1000)) { -it } },
                            popExitTransition = { slideOutHorizontally(tween(1000)) { it } },
                        ) {
                            composable("list") {
                                CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                                    Box(Modifier.fillMaxSize().padding(16.dp)) {
                                        ContactBookAvatar(entry, size = 44.dp, morphAcrossNav = morph, modifier = Modifier.testTag("avatar"))
                                    }
                                }
                            }
                            composable("detail") {
                                CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                                    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopCenter) {
                                        ContactBookAvatar(entry, size = 88.dp, morphAcrossNav = morph, modifier = Modifier.testTag("avatar"))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            waitForIdle()
            val startLeft = onAllNodesWithTag("avatar").fetchSemanticsNodes().single().boundsInRoot.left

            mainClock.autoAdvance = false
            runOnIdle { nav.navigate("detail") }
            mainClock.advanceTimeBy(300)
            midLefts = onAllNodesWithTag("avatar").fetchSemanticsNodes()
                .filter { it.boundsInRoot.width > 0f }
                .map { it.boundsInRoot.left }

            mainClock.autoAdvance = true
            waitForIdle()
            finalLeft = onAllNodesWithTag("avatar").fetchSemanticsNodes().single().boundsInRoot.left
            assertTrue(startLeft < finalLeft)
        }
        return midLefts to finalLeft
    }

    @Test
    fun theAvatarMovesFromItsListSpotToItsDetailSpotInsteadOfSlidingIn() {
        val (mid, final) = runMorph(morph = true)
        assertTrue(mid.isNotEmpty())
        mid.forEach { assertTrue(it < final, "the detail avatar slid in from the edge: $it vs $final") }
    }

    @Test
    fun withoutTheMorphTheDetailAvatarSlidesInWithItsScreen() {
        val (mid, final) = runMorph(morph = false)
        assertTrue(mid.any { it > final }, "control: an unshared avatar rides the slide, $mid vs $final")
    }
}
