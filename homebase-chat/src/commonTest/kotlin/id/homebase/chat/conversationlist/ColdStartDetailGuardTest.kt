package id.homebase.chat.conversationlist

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldDestinationItem
import androidx.compose.material3.adaptive.navigation.ThreePaneScaffoldNavigator
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.uuid.Uuid

/**
 * Compose UI tests for [ColdStartDetailGuard].
 *
 * These tests drive `mainClock.advanceTimeBy(...)` to advance both Compose
 * frames and the LaunchedEffect's `delay(...)` together — `runComposeUiTest`'s
 * test dispatcher is tied to `mainClock`, so a single advance moves both.
 *
 * The regression target for this whole branch is the `unrelatedConvsAddedDuringWait_stillPopsAfterGrace`
 * case: an earlier version keyed the LaunchedEffect on the entire activeConversations
 * list, so each drive-sync chunk reset the timer and the pop never fired during
 * a typical cold-start.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalMaterial3AdaptiveApi::class)
class ColdStartDetailGuardTest {

    private val convX = Uuid.parse("aaaaaaaa-0000-0000-0000-000000000001")
    private val convY = Uuid.parse("aaaaaaaa-0000-0000-0000-000000000002")
    private val convZ = Uuid.parse("aaaaaaaa-0000-0000-0000-000000000003")
    private val convW = Uuid.parse("aaaaaaaa-0000-0000-0000-000000000004")

    private fun compactDirective() = PaneScaffoldDirective(
        maxHorizontalPartitions = 1,
        horizontalPartitionSpacerSize = 0.dp,
        maxVerticalPartitions = 1,
        verticalPartitionSpacerSize = 0.dp,
        defaultPanePreferredWidth = 360.dp,
        excludedBounds = emptyList(),
    )

    private fun expandedDirective() = PaneScaffoldDirective(
        maxHorizontalPartitions = 2,
        horizontalPartitionSpacerSize = 0.dp,
        maxVerticalPartitions = 1,
        verticalPartitionSpacerSize = 0.dp,
        defaultPanePreferredWidth = 360.dp,
        excludedBounds = emptyList(),
    )

    /**
     * Test host. Mirrors the production wiring: rememberListDetailPaneScaffoldNavigator
     * starts at Detail(initialDetailKey) so we simulate the rememberSaveable
     * restoration of a Detail destination across process death without actually
     * tearing down a process. Exposes the navigator via [navigatorRef] for
     * assertion.
     */
    @Composable
    private fun TestHost(
        directive: PaneScaffoldDirective,
        initialDetailKey: Uuid?,
        loadedIds: MutableState<Set<Uuid>>,
        navigatorRef: MutableState<ThreePaneScaffoldNavigator<Uuid>?>,
    ) {
        val initialHistory = remember {
            buildList {
                add(ThreePaneScaffoldDestinationItem(ListDetailPaneScaffoldRole.List))
                if (initialDetailKey != null) {
                    add(
                        ThreePaneScaffoldDestinationItem(
                            ListDetailPaneScaffoldRole.Detail,
                            initialDetailKey,
                        )
                    )
                }
            }
        }
        val scaffoldNavigator = rememberListDetailPaneScaffoldNavigator<Uuid>(
            scaffoldDirective = directive,
            initialDestinationHistory = initialHistory,
        )
        navigatorRef.value = scaffoldNavigator

        ColdStartDetailGuard(
            scaffoldNavigator = scaffoldNavigator,
            loadedConversationIds = loadedIds.value,
            maxHorizontalPartitions = directive.maxHorizontalPartitions,
        )

        ListDetailPaneScaffold(
            directive = directive,
            scaffoldState = scaffoldNavigator.scaffoldState,
            listPane = { AnimatedPane { Text("list") } },
            detailPane = {
                AnimatedPane {
                    val key = scaffoldNavigator.currentDestination?.contentKey
                    Text("detail=${key?.toString() ?: "null"}")
                }
            },
        )
    }

    @Test
    fun unloadedConv_popsAfterGrace() = runComposeUiTest {
        mainClock.autoAdvance = false
        val loaded = mutableStateOf<Set<Uuid>>(emptySet())
        val navigatorRef = mutableStateOf<ThreePaneScaffoldNavigator<Uuid>?>(null)

        setContent {
            MaterialTheme {
                TestHost(
                    directive = compactDirective(),
                    initialDetailKey = convX,
                    loadedIds = loaded,
                    navigatorRef = navigatorRef,
                )
            }
        }
        mainClock.advanceTimeBy(50)  // initial composition + LaunchedEffect launch

        // Sanity: scaffold is on Detail before the grace expires.
        assertEquals(
            ListDetailPaneScaffoldRole.Detail,
            navigatorRef.value?.currentDestination?.pane,
        )

        // Cross the 800 ms threshold; effect should pop back to List.
        mainClock.advanceTimeBy(900)
        assertNotEquals(
            ListDetailPaneScaffoldRole.Detail,
            navigatorRef.value?.currentDestination?.pane,
            "guard should have popped back to List after grace",
        )
    }

    @Test
    fun convLoadsBeforeGrace_doesNotPop() = runComposeUiTest {
        mainClock.autoAdvance = false
        val loaded = mutableStateOf<Set<Uuid>>(emptySet())
        val navigatorRef = mutableStateOf<ThreePaneScaffoldNavigator<Uuid>?>(null)

        setContent {
            MaterialTheme {
                TestHost(
                    directive = compactDirective(),
                    initialDetailKey = convX,
                    loadedIds = loaded,
                    navigatorRef = navigatorRef,
                )
            }
        }
        mainClock.advanceTimeBy(400)  // partway through the grace

        // Drive sync lands convX → keying flips, in-flight delay cancels.
        loaded.value = setOf(convX)
        mainClock.advanceTimeBy(1000)  // well past grace; nothing should pop

        assertEquals(
            ListDetailPaneScaffoldRole.Detail,
            navigatorRef.value?.currentDestination?.pane,
            "loading the target conversation must cancel the pop",
        )
        assertEquals(
            convX,
            navigatorRef.value?.currentDestination?.contentKey,
        )
    }

    /**
     * Regression test for the keying bug fixed in this commit: keying directly
     * on `activeConversations` reset the 800 ms timer every time drive sync
     * landed an unrelated conversation. With chunks arriving every ~250 ms the
     * timer would never elapse. Keying on the derived `isRestoredConvLoaded`
     * Boolean means unrelated conversations don't reset the timer.
     */
    @Test
    fun unrelatedConvsAddedDuringWait_stillPopsAfterGrace() = runComposeUiTest {
        mainClock.autoAdvance = false
        val loaded = mutableStateOf<Set<Uuid>>(emptySet())
        val navigatorRef = mutableStateOf<ThreePaneScaffoldNavigator<Uuid>?>(null)

        setContent {
            MaterialTheme {
                TestHost(
                    directive = compactDirective(),
                    initialDetailKey = convX,  // never loaded
                    loadedIds = loaded,
                    navigatorRef = navigatorRef,
                )
            }
        }
        mainClock.advanceTimeBy(50)

        // Three drive-sync chunks within the grace window, none of them convX.
        mainClock.advanceTimeBy(200); loaded.value = setOf(convY)
        mainClock.advanceTimeBy(200); loaded.value = setOf(convY, convZ)
        mainClock.advanceTimeBy(200); loaded.value = setOf(convY, convZ, convW)
        // Cumulative ~650 ms; nothing should have popped yet.
        assertEquals(
            ListDetailPaneScaffoldRole.Detail,
            navigatorRef.value?.currentDestination?.pane,
        )

        // Cross the 800 ms boundary. Pre-fix this would not have popped because
        // each chunk reset the timer. Post-fix the timer kept ticking.
        mainClock.advanceTimeBy(300)
        assertNotEquals(
            ListDetailPaneScaffoldRole.Detail,
            navigatorRef.value?.currentDestination?.pane,
            "unrelated conv chunks must not reset the grace timer",
        )
    }

    @Test
    fun twoPaneMode_doesNotPop() = runComposeUiTest {
        mainClock.autoAdvance = false
        val loaded = mutableStateOf<Set<Uuid>>(emptySet())
        val navigatorRef = mutableStateOf<ThreePaneScaffoldNavigator<Uuid>?>(null)

        setContent {
            MaterialTheme {
                TestHost(
                    directive = expandedDirective(),
                    initialDetailKey = convX,
                    loadedIds = loaded,
                    navigatorRef = navigatorRef,
                )
            }
        }
        mainClock.advanceTimeBy(2_000)  // way past grace

        // Two-pane mode legitimately shows Detail without a matching conversation.
        assertEquals(
            ListDetailPaneScaffoldRole.Detail,
            navigatorRef.value?.currentDestination?.pane,
            "two-pane mode must not pop the empty Detail",
        )
    }

}
