package id.homebase.core.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.PlatformContext
import id.homebase.api.client.auth.OwnerSession
import id.homebase.api.client.contacts.AvatarResolver
import id.homebase.api.common.OdinId
import id.homebase.core.avatars.AppConnectionStatus
import id.homebase.core.avatars.OwnerAvatarState
import id.homebase.core.ui.navigation.FloatingPaneContainer
import id.homebase.core.ui.navigation.RailItem
import id.homebase.core.ui.navigation.RailOwnerAvatar
import id.homebase.core.ui.navigation.TopLevelNavIcon
import id.homebase.core.ui.navigation.TopLevelRoute
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.core.widget.ProvideSettingsChrome
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module

@OptIn(ExperimentalTestApi::class)
class AppsEverywhereScreenshotTest {
    // Tests run from the module directory; the shots are for the worktree's ignored build/ dir.
    private val outDir = File("../build/polish-shots").apply { mkdirs() }

    private val koin = koinApplication {
        modules(module {
            single { AvatarResolver(MutableStateFlow(emptyList())) }
            single<ImageLoader> { ImageLoader.Builder(PlatformContext.INSTANCE).build() }
        })
    }

    private val owner = OwnerSession(
        odinId = OdinId("frodo.baggins.demo.rocks"), displayName = "Frodo Baggins", firstName = "Frodo", surName = "Baggins",
        profileImageFileId = null, profileImageFileKey = null, profileImagePreviewThumbnail = null, status = null,
    )

    private val hubActions = SettingsActions(
        onBack = {}, onApps = {}, onNotifications = {}, onAppearance = {}, onMedia = {}, onKeyboard = {}, onStorage = {},
        onAccount = {}, onHelp = {}, onProfileEdit = {}, onProfileAvatarEdit = {}, onProfileCard = {},
    )
    private val appsActions = AppsSettingsActions(
        onBack = {}, onOpenPage = {}, onOpenWebDrop = {}, onLocation = {}, photobaseAvailable = true,
    )

    private val state = SettingsUiState(notificationStatus = NotificationVerificationStatus.OK, ownerSession = owner)

    private fun shot(name: String, width: Int, height: Int, content: @Composable () -> Unit) {
        for (dark in listOf(false, true)) {
            runDesktopComposeUiTest(width, height) {
                setContent {
                    KoinIsolatedContext(koin) {
                        HomebaseTheme(darkTheme = dark, updatesSystemChrome = false) {
                            Surface(Modifier.fillMaxSize(), content = content)
                        }
                    }
                }
                waitForIdle()
                val image = onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage()
                ImageIO.write(image, "png", File(outDir, "$name-${if (dark) "dark" else "light"}.png"))
            }
        }
    }

    @Composable
    private fun Rail(avatar: OwnerAvatarState, tab: TopLevelRoute? = TopLevelRoute.Apps) {
        val routes = listOf(TopLevelRoute.Chat, TopLevelRoute.Feed, TopLevelRoute.Vault, TopLevelRoute.Apps)
        Row {
            NavigationRail(
                modifier = Modifier.width(64.dp),
                header = {
                    Spacer(Modifier.height(8.dp))
                    RailOwnerAvatar(session = owner, state = avatar, onNavigate = {}, selected = tab == null)
                },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                routes.forEach {
                    RailItem(it, it == tab, showMomentsBadge = false, showCallsBadge = false, showEmailBadge = false, showLocationBadge = false, onClick = {})
                }
            }
            VerticalDivider()
        }
    }

    @Composable
    private fun BottomBar(selected: TopLevelRoute) {
        Box(contentAlignment = Alignment.BottomCenter) {
            ShortNavigationBar {
                listOf(TopLevelRoute.Chat, TopLevelRoute.Feed, TopLevelRoute.Vault, TopLevelRoute.Apps).forEach { route ->
                    ShortNavigationBarItem(
                        icon = { TopLevelNavIcon(route, selected = route == selected, showMomentsBadge = false, showCallsBadge = false) },
                        label = { Text(stringResource(route.labelRes)) },
                        selected = route == selected,
                        onClick = {},
                    )
                }
            }
        }
    }

    @Test
    fun phone() {
        shot("phone-settings", 412, 900) { SettingsUi(uiState = state, onAction = {}, actions = hubActions) }
        shot("phone-apps", 412, 900) { AppsSettingsScreen(appsActions) }
        shot("phone-bottom-bar", 412, 120) { BottomBar(TopLevelRoute.Apps) }
        shot("phone-bottom-bar-chats", 412, 120) { BottomBar(TopLevelRoute.Chat) }
    }

    // As the app shows it: the pane floats over the dimmed rail, with its own header.
    @Test
    fun wide() {
        shot("wide-pane-apps", 1100, 760) {
            Box(Modifier.fillMaxSize()) {
                Row {
                    Rail(OwnerAvatarState(connectionStatus = AppConnectionStatus.Connected), tab = null)
                    Spacer(Modifier.weight(1f))
                }
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)), contentAlignment = Alignment.Center) {
                    val pane: @Composable () -> Unit = {
                        Column {
                            SettingsPaneHeader(onDismiss = {})
                            Row {
                                SettingsSidebar(selected = SettingsCategory.Apps, onSelect = {})
                                VerticalDivider()
                                Box(Modifier.weight(1f).fillMaxHeight()) {
                                    ProvideSettingsChrome(embedded = true) { AppsSettingsScreen(appsActions) }
                                }
                            }
                        }
                    }
                    FloatingPaneContainer(onDismiss = {}, paneContent = pane, content = pane)
                }
            }
        }
        shot("wide-apps-page", 1100, 760) { AppsSettingsScreen(appsActions) }
        val states = listOf(
            OwnerAvatarState(connectionStatus = AppConnectionStatus.Connected),
            OwnerAvatarState(connectionStatus = AppConnectionStatus.Connected, driveIsSyncing = true),
            OwnerAvatarState(connectionStatus = AppConnectionStatus.Connected, hasDriveError = true),
            OwnerAvatarState(connectionStatus = AppConnectionStatus.Connecting),
            OwnerAvatarState(connectionStatus = AppConnectionStatus.Disconnected),
        )
        shot("wide-rail-states", 340, 420) {
            Row { states.forEach { Column(Modifier.background(MaterialTheme.colorScheme.surface)) { Rail(it, tab = TopLevelRoute.Apps.takeIf { _ -> it !== states[0] }) } } }
        }
    }
    // The hub's identity header, section headers and rows sit on one surface.
    @Test
    fun widePaneHubIsOneSurface() {
        for (dark in listOf(false, true)) {
            runDesktopComposeUiTest(1100, 760) {
                setContent {
                    KoinIsolatedContext(koin) {
                        HomebaseTheme(darkTheme = dark, updatesSystemChrome = false) {
                            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)), contentAlignment = Alignment.Center) {
                                val pane: @Composable () -> Unit = {
                                    Column {
                                        SettingsPaneHeader(onDismiss = {})
                                        Row {
                                            SettingsSidebar(selected = SettingsCategory.General, onSelect = {})
                                            VerticalDivider()
                                            Box(Modifier.weight(1f).fillMaxHeight()) {
                                                ProvideSettingsChrome(embedded = true) { SettingsUi(uiState = state, onAction = {}, actions = hubActions) }
                                            }
                                        }
                                    }
                                }
                                FloatingPaneContainer(onDismiss = {}, paneContent = pane, content = pane)
                            }
                        }
                    }
                }
                waitForIdle()
                val image = onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage()
                ImageIO.write(image, "png", File(outDir, "wide-pane-hub-${if (dark) "dark" else "light"}.png"))
                val list = onNodeWithTag("settingsList").getBoundsInRoot()
                val account = onNodeWithTag("accountButton").getBoundsInRoot()
                val notifications = onNodeWithTag("notificationsButton").getBoundsInRoot()
                val x = with(density) { (list.right - 6.dp).roundToPx() }
                fun at(y: Dp) = image.getRGB(x, with(density) { y.roundToPx() })
                val samples = mapOf(
                    "header band" to at((account.bottom + notifications.top) / 2),
                    "pane header" to at(list.top - 6.dp),
                    "account row" to at((account.top + account.bottom) / 2),
                    "notifications row" to at((notifications.top + notifications.bottom) / 2),
                    "list top" to at(list.top + 4.dp),
                ).mapValues { "%08x".format(it.value) }
                assertEquals(1, samples.values.toSet().size, "dark=$dark $samples")
            }
        }
    }
}
