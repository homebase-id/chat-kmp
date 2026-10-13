package id.homebase.core.ui.screens.settings

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.runDesktopComposeUiTest
import id.homebase.api.client.auth.OwnerSession
import id.homebase.api.common.OdinId
import id.homebase.core.ui.theme.HomebaseTheme
import coil3.ImageLoader
import coil3.PlatformContext
import java.io.File
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import javax.imageio.ImageIO
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class SettingsShotsTest {

    private val outDir: File? = System.getenv("CARD_SHOTS_DIR")?.let(::File)?.also { it.mkdirs() }

    private val actions = SettingsActions(
        onBack = {}, onNotifications = {}, onAppearance = {}, onMedia = {}, onKeyboard = {}, onStorage = {},
        onHelp = {}, onMomentsSettings = {}, onVaultSettings = {}, onEmailSettings = {}, onOpenWebDrop = {},
        onLocation = {}, onContactBookSettings = {}, onProfileEdit = {}, onProfileAvatarEdit = {}, onProfileCard = {},
    )

    private val koin = koinApplication { modules(module { single<ImageLoader> { ImageLoader.Builder(PlatformContext.INSTANCE).build() } }) }

    private val demoOwner = OwnerSession(
        odinId = OdinId("frodo.baggins.demo.rocks"), displayName = "Frodo Baggins", firstName = "Frodo", surName = "Baggins",
        profileImageFileId = null, profileImageFileKey = null, profileImagePreviewThumbnail = null, status = null,
    )

    @Test
    fun settingsHeaderRendersLightAndDark() {
        for (dark in listOf(false, true)) {
            runDesktopComposeUiTest(width = 824, height = 1784) {
                setContent {
                    KoinIsolatedContext(koin) {
                        HomebaseTheme(darkTheme = dark, updatesSystemChrome = false) {
                            SettingsUi(uiState = SettingsUiState(ownerSession = demoOwner), onAction = {}, actions = actions)
                        }
                    }
                }
                waitForIdle()
                outDir?.let { ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", File(it, "L1-settings-${if (dark) "dark" else "light"}.png")) }
            }
        }
    }
}
