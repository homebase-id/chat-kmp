@file:OptIn(ExperimentalTestApi::class, ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.card

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import id.homebase.core.ui.theme.HomebaseTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class CardEditorDesignEntryTest {

    private val state = ProfileCardUiState(
        isCardReady = true,
        cards = listOf(
            ProfileCard(id = Uuid.random(), versionTag = Uuid.random(), audience = CardAudience.Public, design = CardDesign.BOARD),
            ProfileCard(id = Uuid.random(), versionTag = Uuid.random(), audience = CardAudience.Circle("c1", "Friends"), design = CardDesign.BOARD),
        ),
    )

    private fun openDesignFrom(width: Int, height: Int) {
        var opened = 0
        runDesktopComposeUiTest(width = width, height = height) {
            setContent {
                HomebaseTheme(darkTheme = false, updatesSystemChrome = false) {
                    ProfileCardEditorContent(
                        uiState = state,
                        mode = EditorMode.Content,
                        step = EditorStep.Design,
                        onStep = {},
                        onBack = {},
                        onSelect = {},
                        onOption = { _, _ -> },
                        onBlockOrder = {},
                        onSave = {},
                        onDesign = { opened++ },
                        snackbarHostState = remember { SnackbarHostState() },
                    ) { Box(Modifier) }
                }
            }
            onNodeWithText("Copy from…").assertDoesNotExist()
            onNodeWithContentDescription("Design").performClick()
        }
        assertEquals(1, opened)
    }

    @Test
    fun phoneHeaderOffersDesignAsAnIconButton() = openDesignFrom(412, 892)

    @Test
    fun wideLayoutOffersDesignAsAnIconButton() = openDesignFrom(1100, 800)

    @Test
    fun shortWindowOffersDesignAsAnIconButton() = openDesignFrom(412, 600)
}
