package id.homebase.core.ui.screens.account

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import id.homebase.api.client.storage.HostingProvider
import id.homebase.api.client.storage.StorageUsage
import id.homebase.core.test.setTestLocale
import kotlin.test.Test
import kotlin.test.assertEquals

/** Settings > Account: plan and storage, then log out, then delete account under Danger zone. */
@OptIn(ExperimentalTestApi::class)
class AccountSettingsUiTest {

    private val fired = mutableListOf<String>()

    private fun ComposeUiTest.account(uiState: AccountSettingsUiState = AccountSettingsUiState(isLoading = false)) {
        setTestLocale("en-US")
        setContent {
            MaterialTheme {
                AccountSettingsUi(
                    uiState = uiState,
                    onAction = { fired += it.toString() },
                    onLogout = { fired += "logout" },
                    onDeleteAccount = { fired += "delete" },
                    onBackClick = { fired += "back" },
                )
            }
        }
    }

    private fun ComposeUiTest.tap(tag: String) {
        onNodeWithTag(tag).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
    }

    @Test
    fun logoutAndDeleteAccountAreHere() = runComposeUiTest {
        account()
        tap("logoutButton")
        tap("deleteAccountButton")
        assertEquals(listOf("logout", "delete"), fired)
        onNodeWithText("Danger zone").assertExists()
    }

    /** Delete opens an in-app dialog, so it must not carry the external affordance's "Open externally" label. */
    @Test
    fun deleteAccountDoesNotClaimToLeaveTheApp() = runComposeUiTest {
        account()
        onNodeWithTag("deleteAccountButton").performScrollTo().assert(
            SemanticsMatcher("has no click label") { node ->
                node.config.getOrNull(SemanticsActions.OnClick)?.label == null
            }
        )
    }

    @Test
    fun showsThePlanAndWhereToGetMoreSpace() = runComposeUiTest {
        account(
            AccountSettingsUiState(
                usage = StorageUsage(planId = "veterans", usedBytes = 1024, quotaBytes = 4096, isOverQuota = false),
                isLoading = false,
            )
        )
        onNodeWithText("Plan: veterans", substring = true).assertExists()
        onNodeWithText("How to upgrade").performScrollTo().performClick()
        assertEquals(listOf(AccountSettingsUiAction.HowToUpgradeClicked.toString()), fired)
    }

    private val raven = HostingProvider(
        displayName = "Raven Hosting",
        upgradeUrl = "https://invites.example.com/upgrade?id=frodo.dotyou.cloud&host=provisioning.dotyou.cloud",
    )

    private fun withPlan(provider: HostingProvider?, dialog: Boolean = false) = AccountSettingsUiState(
        usage = StorageUsage(planId = "free", usedBytes = 1024, quotaBytes = 4096, hostingProvider = provider),
        isLoading = false,
        showUpgradeDialog = dialog,
    )

    @Test
    fun theUpgradeDialogSaysWhereTheOwnerIsGoing() = runComposeUiTest {
        account(withPlan(raven, dialog = true))
        onNodeWithText("We'll send you to Raven Hosting to upgrade your plan.").assertExists()
        onNodeWithText("Continue").performClick()
        onNodeWithText("Cancel").performClick()
        assertEquals(
            listOf(
                AccountSettingsUiAction.UpgradeContinueClicked.toString(),
                AccountSettingsUiAction.UpgradeDialogDismissed.toString(),
            ),
            fired,
        )
    }

    @Test
    fun withoutAHostingProviderTheDialogSaysWhoToAsk() = runComposeUiTest {
        account(withPlan(null, dialog = true))
        // On the page under Get more space, and in the dialog
        onAllNodesWithText("To get more space, contact whoever runs your Homebase server.").assertCountEquals(2)
        onNodeWithText("Continue").assertDoesNotExist()
        onNodeWithText("Ok").performClick()
        assertEquals(listOf(AccountSettingsUiAction.UpgradeDialogDismissed.toString()), fired)
    }

    @Test
    fun getMoreSpaceNamesTheHostingProvider() = runComposeUiTest {
        account(withPlan(raven))
        onNodeWithText("To get more space, upgrade your plan with Raven Hosting.").assertExists()
    }

    @Test
    fun continueOpensTheUpgradePageExactlyAsGiven() {
        val after = withPlan(raven, dialog = true).afterUpgradeContinue()
        assertEquals(AccountSettingsUiEvent.OpenUrl(raven.upgradeUrl!!), after.uiEvent)
        assertEquals(false, after.showUpgradeDialog)

        val nowhere = withPlan(raven.copy(upgradeUrl = null), dialog = true).afterUpgradeContinue()
        assertEquals(null, nowhere.uiEvent)
    }

    @Test
    fun saysSoWhenTheServerHasNoPlans() = runComposeUiTest {
        account()
        onNodeWithText("Storage information isn't available on this server.").assertExists()
        // Log out still works without a plan
        tap("logoutButton")
        assertEquals(listOf("logout"), fired)
    }
}
