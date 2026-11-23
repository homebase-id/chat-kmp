package id.homebase.core.ui.screens.card

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.resources.MR
import id.homebase.resources.profile_card_nfc_android_only
import id.homebase.resources.profile_card_nfc_instead
import id.homebase.resources.profile_card_nfc_iphone_title
import id.homebase.resources.profile_card_nfc_iphone_to_iphone
import id.homebase.resources.profile_card_nfc_not_possible
import id.homebase.resources.profile_card_nfc_open_settings
import id.homebase.resources.profile_card_share
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class CardNfcPanelTest {

    private fun text(res: StringResource) = runBlocking { getString(res) }

    private fun render(panel: CardNfcPanel, onShareInstead: (() -> Unit)? = {}, check: androidx.compose.ui.test.ComposeUiTest.() -> Unit) =
        runDesktopComposeUiTest(width = 800, height = 1600) {
            mainClock.autoAdvance = false
            setContent {
                HomebaseTheme(darkTheme = false, updatesSystemChrome = false) {
                    CardExpressiveTheme {
                        CardNfcPanelContent(panel = panel, onOpenSettings = {}, onShareInstead = onShareInstead)
                    }
                }
            }
            mainClock.advanceTimeBy(1_500)
            check()
        }

    @Test
    fun androidReadyNamesAndroidOnlyAndHasNoIphoneRows() = render(CardNfcPanel.AndroidReady) {
        onNodeWithText(text(MR.string.profile_card_nfc_android_only)).assertExists()
        onNodeWithText(text(MR.string.profile_card_nfc_iphone_to_iphone)).assertDoesNotExist()
        onNodeWithText(text(MR.string.profile_card_nfc_iphone_title)).assertDoesNotExist()
        onNodeWithText(text(MR.string.profile_card_share)).assertDoesNotExist()
    }

    @Test
    fun androidOffOffersNfcSettings() = render(CardNfcPanel.AndroidOff) {
        onNodeWithText(text(MR.string.profile_card_nfc_open_settings)).assertExists()
        onNodeWithText(text(MR.string.profile_card_nfc_android_only)).assertDoesNotExist()
    }

    @Test
    fun iphoneExplainsAndOffersShareInstead() = render(CardNfcPanel.Iphone) {
        onNodeWithText(text(MR.string.profile_card_nfc_iphone_title)).assertExists()
        onNodeWithText(text(MR.string.profile_card_nfc_iphone_to_iphone)).assertExists()
        onAllNodesWithText(text(MR.string.profile_card_nfc_not_possible), substring = true).assertCountEquals(2)
        onNodeWithText(text(MR.string.profile_card_nfc_instead)).assertExists()
        onNodeWithText(text(MR.string.profile_card_share)).assertExists()
        onNodeWithText(text(MR.string.profile_card_nfc_android_only)).assertDoesNotExist()
    }

    @Test
    fun iphoneShareButtonIsGoneWithoutAShareAction() = render(CardNfcPanel.Iphone, onShareInstead = null) {
        onNodeWithText(text(MR.string.profile_card_nfc_instead)).assertExists()
        onNodeWithText(text(MR.string.profile_card_share)).assertDoesNotExist()
    }
}
