package id.homebase.core.ui.screens.profile

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import id.homebase.core.ui.screens.card.CardCircle
import id.homebase.core.ui.screens.card.CardExpressiveTheme
import id.homebase.core.ui.theme.HomebaseTheme
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class ProfileEditCancelMotionTest {

    private val filled = ProfileEditUiState(
        isLoading = false,
        values = mapOf(
            ProfileField.EMAIL to "sam@bagend.me",
            ProfileField.PHONE to "+14155550123",
            ProfileField.PHONE_LABEL to "Mobile",
            ProfileField.CITY to "Hobbiton",
        ),
    )

    @Test
    fun cancellingOpenDetailsNeverSpringsTheInsetBelowZero() {
        for (label in listOf("Email", "Phone", "Birthday")) cancel(label)
    }

    @Test
    fun cancellingAnOpenDetailWithACustomLabel() {
        cancel("Email", filled.copy(values = filled.values + (ProfileField.EMAIL_LABEL to "Hobbit post")))
    }

    @Test
    fun deselectingACardSpringsTheFocusRingWithoutCrashing() = runDesktopComposeUiTest(width = 824, height = 1784) {
        mainClock.autoAdvance = false
        setContent { screen(filled.copy(circles = listOf(CardCircle("f", "Family")))) }
        mainClock.advanceTimeBy(1_500)
        mainClock.autoAdvance = true
        onAllNodesWithText("Family", useUnmergedTree = true).onFirst().also { runCatching { it.performScrollTo() } }.performClick()
        waitForIdle()
        mainClock.autoAdvance = false
        mainClock.advanceTimeBy(1_500)
        onAllNodesWithText("Show all", useUnmergedTree = true).onFirst().performClick()
        repeat(100) { mainClock.advanceTimeBy(16) }
    }

    @androidx.compose.runtime.Composable
    private fun screen(state: ProfileEditUiState) {
        CompositionLocalProvider(LocalDensity provides Density(2f, 1f)) {
            HomebaseTheme(darkTheme = false, updatesSystemChrome = false) {
                CardExpressiveTheme {
                    var s by remember { mutableStateOf(state) }
                    ProfileEditContent(
                        uiState = s,
                        avatarUiState = ProfileAvatarEditUiState(isLoading = false),
                        previewMode = false,
                        onTogglePreview = {},
                        onOpenCard = {},
                        onAction = { a ->
                            if (a is ProfileEditAction.FieldChanged) s = s.copy(values = s.values + (a.field to a.value))
                        },
                        onAvatarAction = {},
                        onPickAnonymousPhoto = {},
                        onPickOnlyMePhoto = {},
                        snackbarHostState = remember { SnackbarHostState() },
                    )
                }
            }
        }
    }

    private fun cancel(label: String, state: ProfileEditUiState = filled) = runDesktopComposeUiTest(width = 824, height = 1784) {
        mainClock.autoAdvance = false
        setContent { screen(state) }
        mainClock.advanceTimeBy(1_500)
        mainClock.autoAdvance = true
        onAllNodesWithText(label, useUnmergedTree = true).onFirst().also { runCatching { it.performScrollTo() } }.performClick()
        waitForIdle()
        mainClock.autoAdvance = false
        mainClock.advanceTimeBy(1_500)
        onAllNodesWithText("Cancel", useUnmergedTree = true).onFirst().performClick()
        repeat(100) { mainClock.advanceTimeBy(16) }
    }
}
