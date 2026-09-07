package id.homebase.auth.login

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.Disposable
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import id.homebase.api.common.OdinId
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A resolved preview swaps the mark for `PublicAvatar`, which pulls its Coil loader out of Koin —
 * a bare `runComposeUiTest` dies with "KoinApplication has not been started". This one refuses
 * every request, so the avatar lands on its initials fallback and dials nobody.
 */
private class OfflineImageLoader : ImageLoader {
    override val defaults: ImageRequest.Defaults = ImageRequest.Defaults.DEFAULT
    override val components: ComponentRegistry = ComponentRegistry()
    override val memoryCache: MemoryCache? = null
    override val diskCache: DiskCache? = null

    override fun enqueue(request: ImageRequest): Disposable = object : Disposable {
        override val job: Deferred<ImageResult> = CompletableDeferred(refuse(request))
        override val isDisposed: Boolean = true
        override fun dispose() = Unit
    }

    override suspend fun execute(request: ImageRequest): ImageResult = refuse(request)

    override fun shutdown() = Unit

    override fun newBuilder(): ImageLoader.Builder = throw UnsupportedOperationException()

    private fun refuse(request: ImageRequest) =
        ErrorResult(null, request, UnsupportedOperationException("offline test loader"))
}

private val TestKoin = koinApplication {
    modules(module { single<ImageLoader> { OfflineImageLoader() } })
}

@Composable
private fun WithImageLoader(content: @Composable () -> Unit) {
    KoinIsolatedContext(TestKoin) { content() }
}

private fun frodo(displayName: String? = null) =
    IdentityPreview(odinId = OdinId("frodo.digital"), displayName = displayName)

@OptIn(ExperimentalTestApi::class)
class LoginUiTest {

    /**
     * The headline is the one element every state shares and the only fixed point the eye has, so
     * it must not move as the state below it changes. It is pinned by [StateSlotMinHeight] reserving
     * the tallest state's height — a constant defended by nothing else, whose regression would be
     * invisible in every other test here.
     */
    @Test
    fun headline_holdsItsPositionAcrossEveryState() = runComposeUiTest {
        var state by mutableStateOf(LoginUiState())
        setContent {
            WithImageLoader {
                MaterialTheme { LoginUi(uiState = state, onAction = {}) }
            }
        }
        val states = listOf(
            "empty" to LoginUiState(),
            "error" to LoginUiState(error = LoginError.Message("Couldn't reach that identity")),
            "authenticating" to LoginUiState(homebaseId = "frodo.digital", isLoading = true),
            "resolved" to LoginUiState(
                homebaseId = "frodo.digital",
                identityPreview = frodo(displayName = "Frodo Baggins"),
            ),
            "last identity" to LoginUiState(
                homebaseId = "frodo.digital",
                lastIdentity = frodo(displayName = "Frodo Baggins"),
                showIdField = false,
            ),
            "success" to LoginUiState(isAuthenticated = true),
        )
        val tops = states.map { (name, next) ->
            state = next
            waitForIdle()
            name to onNodeWithTag("title_text").getBoundsInRoot().top
        }
        val first = tops.first().second
        assertTrue(
            tops.all { it.second == first },
            "the headline moved between states: " + tops.joinToString { "\${it.first}=\${it.second}" },
        )
    }

    @Test
    fun formState_showsSignInButton() = runComposeUiTest {
        setContent {
            MaterialTheme {
                LoginUi(
                    uiState = LoginUiState(
                        homebaseId = "",
                        isLoading = false,
                        isAuthenticated = false,
                        error = null,
                    ),
                    onAction = {},
                )
            }
        }
        onNodeWithTag("title_text").assertExists()
        onNodeWithTag("login_button").assertExists()
        onNodeWithTag("create_account_button").assertExists()
    }

    // Authenticating no longer tears the form down — it goes inert in place, so the id stays on
    // screen and nothing moves under the cursor.
    @Test
    fun loadingState_leavesTheFormInPlaceAndInert() = runComposeUiTest {
        setContent {
            MaterialTheme {
                LoginUi(
                    uiState = LoginUiState(
                        homebaseId = "",
                        isLoading = true,
                        isAuthenticated = false,
                        error = null,
                    ),
                    onAction = {},
                )
            }
        }
        onNodeWithTag("authenticating_text").assertExists()
        onNodeWithTag("login_button").assertExists()
        // The spinner carries the busy state; the button keeps its fill so the CTA stays visible.
        onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .assertCountEquals(1)
        // The status line takes the link's slot, so the column height does not change.
        onNodeWithTag("create_account_button").assertDoesNotExist()
    }

    @Test
    fun authenticatedState_showsSuccessMessage() = runComposeUiTest {
        setContent {
            MaterialTheme {
                LoginUi(
                    uiState = LoginUiState(
                        homebaseId = "",
                        isLoading = false,
                        isAuthenticated = true,
                        error = null,
                    ),
                    onAction = {},
                )
            }
        }
        onNodeWithTag("success_text").assertExists()
        onNodeWithTag("login_button").assertDoesNotExist()
    }

    @Test
    fun errorState_showsErrorAndTryAgainButton() = runComposeUiTest {
        setContent {
            MaterialTheme {
                LoginUi(
                    uiState = LoginUiState(
                        homebaseId = "",
                        isLoading = false,
                        isAuthenticated = false,
                        error = LoginError.Message("Invalid identity"),
                    ),
                    onAction = {},
                )
            }
        }
        onNodeWithTag("error_message", useUnmergedTree = true).assertExists()
        // It lives in the field's supporting-text slot, so it merges into the field's own
        // semantics node — which is the point: a screen reader reads it with the input.
        onNodeWithText("Invalid identity").assertExists()
        onNodeWithTag("try_again_button").assertExists()
        // "Sign in" should be replaced by "Try again"
        onNodeWithTag("login_button").assertDoesNotExist()
    }

    @Test
    fun createAccountAction_fires() = runComposeUiTest {
        var actionFired = false
        setContent {
            MaterialTheme {
                LoginUi(
                    uiState = LoginUiState(
                        homebaseId = "",
                        isLoading = false,
                        isAuthenticated = false,
                        error = null,
                    ),
                    onAction = { action ->
                        if (action is LoginUiAction.CreateAccount) {
                            actionFired = true
                        }
                    },
                )
            }
        }
        onNodeWithTag("create_account_button").performClick()
        assertTrue(actionFired)
    }

    @Test
    fun popupBlockedState_showsContinueButtonAndFires() = runComposeUiTest {
        var continueFired = false
        setContent {
            MaterialTheme {
                LoginUi(
                    uiState = LoginUiState(
                        homebaseId = "frodo.dotyou.cloud",
                        isLoading = true,
                        isAuthenticated = false,
                        error = null,
                    ),
                    onAction = {},
                    pendingAuthUrl = "https://frodo.dotyou.cloud/api/owner/v1/youauth/authorize",
                    onContinueAuth = { continueFired = true },
                )
            }
        }
        // The blocked-popup prompt takes priority over the loading state.
        onNodeWithTag("popup_blocked_text").assertExists()
        onNodeWithTag("loading_text").assertDoesNotExist()
        onNodeWithTag("continue_auth_button").performClick()
        assertTrue(continueFired)
    }

    @Test
    fun loadingWithDriveProgresses_showsDriveNames() = runComposeUiTest {
        setContent {
            MaterialTheme {
                LoginUi(
                    uiState = LoginUiState(
                        homebaseId = "",
                        isLoading = true,
                        isAuthenticated = false,
                        error = null,
                        driveProgresses = persistentListOf(
                            DriveProgress(driveId = "1", name = "Chat", completed = true, total = 42, count = 42, progress = 1f),
                            DriveProgress(driveId = "2", name = "Feed", count = 17, total = 17),
                        ),
                    ),
                    onAction = {},
                )
            }
        }
        onNodeWithText("Chat").assertExists()
        onNodeWithText("Feed").assertExists()
        // When driveProgresses is non-empty, "Authenticating..." should not appear
        onNodeWithText("Authenticating\u2026").assertDoesNotExist()
    }

    // Pins what every other case here is actually rendering: the test window is wide enough that
    // isExpandedLayout() is true, so these all exercise the two-pane branch, not the compact one.
    @Test
    fun expandedWindow_showsTheBrandPanel() = runComposeUiTest {
        setContent {
            MaterialTheme {
                LoginUi(uiState = LoginUiState(homebaseId = ""), onAction = {})
            }
        }
        onNodeWithText("Your data, your server.").assertExists()
        onNodeWithTag("login_button").assertExists()
    }

    @Test
    fun lastIdentity_replacesTheFormWithTheCard() = runComposeUiTest {
        setContent {
            MaterialTheme {
                LoginUi(
                    uiState = LoginUiState(
                        homebaseId = "frodo.digital",
                        lastIdentity = frodo(),
                        showIdField = false,
                    ),
                    onAction = {},
                )
            }
        }
        onNodeWithTag("last_identity_card").assertExists()
        onNodeWithTag("use_different_id_button").assertExists()
        onNodeWithTag("create_account_button").assertExists()
        onNodeWithTag("title_text").assertTextEquals("welcome back")
        onNodeWithTag("login_button").assertDoesNotExist()
    }

    @Test
    fun useDifferentIdAction_fires() = runComposeUiTest {
        var actionFired = false
        setContent {
            MaterialTheme {
                LoginUi(
                    uiState = LoginUiState(
                        homebaseId = "frodo.digital",
                        lastIdentity = frodo(),
                        showIdField = false,
                    ),
                    onAction = { action ->
                        if (action is LoginUiAction.UseDifferentId) {
                            actionFired = true
                        }
                    },
                )
            }
        }
        onNodeWithTag("use_different_id_button").performClick()
        assertTrue(actionFired)
    }

    @Test
    fun showIdField_keepsTheFormEvenWithALastIdentity() = runComposeUiTest {
        setContent {
            MaterialTheme {
                LoginUi(
                    uiState = LoginUiState(
                        homebaseId = "frodo.digital",
                        lastIdentity = frodo(),
                        showIdField = true,
                    ),
                    onAction = {},
                )
            }
        }
        onNodeWithTag("login_button").assertExists()
        onNodeWithTag("last_identity_card").assertDoesNotExist()
    }

    // Expanded: the brand panel owns the identity, so the form must not repeat it.
    // Declining the saved identity must stop the brand panel painting it: the panel's fallback and
    // the card are the same offer, so they have to switch off together.
    @Test
    fun declinedLastIdentity_leavesTheBrandPanel() = runComposeUiTest {
        setContent {
            WithImageLoader {
                MaterialTheme {
                    LoginUi(
                        uiState = LoginUiState(
                            homebaseId = "",
                            lastIdentity = frodo(displayName = "Frodo Baggins"),
                            showIdField = true,
                        ),
                        onAction = {},
                    )
                }
            }
        }
        onNodeWithTag("brand_identity_name").assertDoesNotExist()
        onAllNodesWithText("Frodo Baggins", useUnmergedTree = true).assertCountEquals(0)
        onNodeWithTag("login_button").assertExists()
    }

    // ...but while the card is still on offer, the panel must name it.
    @Test
    fun offeredLastIdentity_namesItInTheBrandPanel() = runComposeUiTest {
        setContent {
            WithImageLoader {
                MaterialTheme {
                    LoginUi(
                        uiState = LoginUiState(
                            homebaseId = "frodo.digital",
                            lastIdentity = frodo(displayName = "Frodo Baggins"),
                            showIdField = false,
                        ),
                        onAction = {},
                    )
                }
            }
        }
        onNodeWithTag("brand_identity_name").assertTextEquals("Frodo Baggins")
    }

    // A newly typed identity still reaches the panel after the old one was declined.
    @Test
    fun newPreviewAfterDecline_reachesTheBrandPanel() = runComposeUiTest {
        setContent {
            WithImageLoader {
                MaterialTheme {
                    LoginUi(
                        uiState = LoginUiState(
                            homebaseId = "sam.digital",
                            identityPreview = IdentityPreview(OdinId("sam.digital"), "Samwise Gamgee"),
                            lastIdentity = frodo(displayName = "Frodo Baggins"),
                            showIdField = true,
                        ),
                        onAction = {},
                    )
                }
            }
        }
        onNodeWithTag("brand_identity_name").assertTextEquals("Samwise Gamgee")
        onAllNodesWithText("Frodo Baggins", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun identityPreview_rendersTheDisplayNameOnceOnExpanded() = runComposeUiTest {
        setContent {
            WithImageLoader {
                MaterialTheme {
                    LoginUi(
                        uiState = LoginUiState(
                            homebaseId = "frodo.digital",
                            identityPreview = frodo(displayName = "Frodo Baggins"),
                        ),
                        onAction = {},
                    )
                }
            }
        }
        onNodeWithTag("brand_identity_name").assertTextEquals("Frodo Baggins")
        // The whole point: the name is on screen exactly once, wherever it is rendered from.
        onAllNodesWithText("Frodo Baggins", useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun unresolvedIdentity_showsNoErrorAndNoSpinner() = runComposeUiTest {
        setContent {
            MaterialTheme {
                LoginUi(
                    uiState = LoginUiState(
                        homebaseId = "frodo.digital",
                        identityPreview = null,
                    ),
                    onAction = {},
                )
            }
        }
        onNodeWithTag("error_message").assertDoesNotExist()
        onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .assertCountEquals(0)
    }
}
