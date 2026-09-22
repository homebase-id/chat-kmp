package id.homebase.core.session

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.koin.compose.koinInject
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.scope.Scope
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Stands in for any root-registered dependency an authenticated screen injects. */
private class ChatService

/** One pass through [NavHostShapedShell]; a new instance means the shell actually recomposed. */
private class ShellPass(val chatService: ChatService, val gateOpen: Boolean)

/**
 * `AppNavHost` in miniature: the gate's own `koinInject` — itself a `currentKoinScope()` read,
 * sitting *above* the flag it computes — then the ungated body resolutions, then the flag.
 */
@Composable
private fun NavHostShapedShell(redraw: MutableState<Int>): ShellPass {
    redraw.value
    val gateSource = koinInject<IdentitySessionScope>()
    val chatService = koinInject<ChatService>()
    return ShellPass(chatService, gateSource.scopeOrNull?.closed == false)
}

/**
 * #1373: logout destroys the Koin identity scope while the composition still points at it.
 *
 * The composition learns about the teardown through `collectAsState`, which resumes on a later
 * main-thread continuation; `Scope.closed` flips synchronously inside `close()`. In between,
 * every `koinInject` / `koinViewModel` below [IdentityScopeProvider] calls `currentKoinScope()`
 * against a destroyed scope.
 *
 * These tests hold [IdentityScope]'s `published` argument at that stale value on purpose: that
 * is not a contrivance, it is the state the composition is in for the whole interval, and it
 * is why gating the resolutions on `isAuthenticated` cannot fix this on its own.
 */
@OptIn(ExperimentalTestApi::class)
class IdentityScopeTeardownTest {

    private lateinit var koin: Koin
    private lateinit var session: IdentitySessionScope

    @BeforeTest
    fun setUp() {
        koin = startKoin { modules(module { single { ChatService() } }) }.koin
        session = IdentitySessionScope(koin)
        koin.loadModules(listOf(module { single { session } }))
    }

    @AfterTest
    fun tearDown() = stopKoin()

    @Test
    fun `a shell recomposing after an off-main logout resolves instead of crashing`() = runComposeUiTest {
        val published = mutableStateOf<Scope?>(session.open("frodo.dotyou.cloud"))
        val redraw = mutableStateOf(0)
        var shell: ShellPass? = null

        setContent {
            IdentityScope(published.value, session::scopeOrNull) { shell = NavHostShapedShell(redraw) }
        }
        waitForIdle()
        val first = assertNotNull(shell)
        assertTrue(first.gateOpen, "the session is open, so the shell should see its scope")

        // #1349's dead-token logout ran here, off the main dispatcher.
        runBlocking(Dispatchers.Default) { session.close() }
        // …and something unrelated to the scope invalidates the shell in the same window: the
        // auth guard's navigate(Login) moving the back stack, a permission flag, anything.
        redraw.value++
        waitForIdle()

        val after = assertNotNull(shell)
        assertNotSame(first, after, "the shell must have recomposed, or this proves nothing")
        assertFalse(after.gateOpen, "the session is gone")
        assertSame(koin.get<ChatService>(), after.chatService, "resolution should fall back to root")
    }

    @Test
    fun `an identity switch redirects resolution to the new session, not the dead one`() = runComposeUiTest {
        val published = mutableStateOf<Scope?>(session.open("frodo.dotyou.cloud"))
        val redraw = mutableStateOf(0)
        var shell: ShellPass? = null

        setContent {
            IdentityScope(published.value, session::scopeOrNull) { shell = NavHostShapedShell(redraw) }
        }
        waitForIdle()
        val first = assertNotNull(shell)

        // A switch destroys the previous scope while the gate still reads open, so hardening
        // `identityScope != null` to `closed == false` lets this straight through either way.
        val next = runBlocking(Dispatchers.Default) { session.open("sam.dotyou.cloud") }
        redraw.value++
        waitForIdle()

        val after = assertNotNull(shell)
        assertNotSame(first, after, "the shell must have recomposed, or this proves nothing")
        assertSame(next, session.scopeOrNull)
        assertTrue(after.gateOpen, "resolution should have followed the session to its new scope")
    }
}
