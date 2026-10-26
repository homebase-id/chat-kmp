@file:OptIn(ExperimentalTestApi::class)

package id.homebase.core.ui.screens.auth

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import id.homebase.core.vault.BiometricResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred

class BiometricUnlockTest {

    @Test
    fun unlockIsDisabledWhilePromptIsUpAndReenabledAfterFailure() = runComposeUiTest {
        val prompt = CompletableDeferred<BiometricResult>()
        var prompts = 0
        setContent {
            val unlock = rememberBiometricUnlock(
                initiallyAuthorized = false,
                title = "t",
                subtitle = "s",
                onAuthenticated = {},
                authenticate = { _, _ -> prompts++; prompt.await() },
            )
            Button(onClick = unlock::requestUnlock, enabled = !unlock.isAuthenticating, modifier = Modifier.testTag("unlock")) {
                Text("Unlock")
            }
        }
        waitForIdle()
        assertEquals(1, prompts)
        onNodeWithTag("unlock").assertIsNotEnabled()

        prompt.complete(BiometricResult.Failure)
        waitForIdle()
        onNodeWithTag("unlock").assertIsEnabled()

        onNodeWithTag("unlock").performClick()
        waitForIdle()
        assertEquals(2, prompts)
    }

    @Test
    fun requestUnlockWhilePromptIsUpDoesNotStackPrompts() = runComposeUiTest {
        val prompt = CompletableDeferred<BiometricResult>()
        var prompts = 0
        lateinit var unlock: BiometricUnlock
        setContent {
            unlock = rememberBiometricUnlock(false, "t", "s", {}, { _, _ -> prompts++; prompt.await() })
        }
        waitForIdle()
        unlock.requestUnlock()
        waitForIdle()
        assertEquals(1, prompts)
    }

    @Test
    fun successRecordsAuthThenOpensTheGate() = runComposeUiTest {
        var recorded = 0
        lateinit var unlock: BiometricUnlock
        setContent {
            unlock = rememberBiometricUnlock(false, "t", "s", { recorded++ }, { _, _ -> BiometricResult.Success })
        }
        waitForIdle()
        assertTrue(unlock.authorized)
        assertEquals(1, recorded)
    }

    @Test
    fun cancelledPromptDoesNotWedgeTheGate() = runComposeUiTest {
        var prompts = 0
        lateinit var unlock: BiometricUnlock
        setContent {
            unlock = rememberBiometricUnlock(false, "t", "s", {}, { _, _ ->
                prompts++
                if (prompts == 1) CompletableDeferred<BiometricResult>().await() else BiometricResult.Failure
            })
        }
        waitForIdle()
        assertTrue(unlock.isAuthenticating)
        unlock.attempt++
        waitForIdle()
        assertEquals(2, prompts)
        assertFalse(unlock.isAuthenticating)
    }

    @Test
    fun stopCoversUntilResumeOnlyWhenEnabled() = runComposeUiTest {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle get() = registry
        }
        owner.registry.currentState = Lifecycle.State.RESUMED
        var enabled = true
        var visible = false
        setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                visible = rememberStopPrivacyOverlay(enabled)
                Column { Text("x") }
            }
        }
        waitForIdle()
        assertFalse(visible)
        owner.registry.currentState = Lifecycle.State.CREATED
        waitForIdle()
        assertTrue(visible)
        owner.registry.currentState = Lifecycle.State.RESUMED
        waitForIdle()
        assertFalse(visible)
    }
}
