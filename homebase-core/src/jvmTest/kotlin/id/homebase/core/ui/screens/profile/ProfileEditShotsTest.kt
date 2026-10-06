package id.homebase.core.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import id.homebase.api.client.profile.ProfileAttributeTypes
import id.homebase.core.ui.screens.card.CardCircle
import id.homebase.core.ui.screens.card.CardExpressiveTheme
import id.homebase.core.ui.theme.HomebaseTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders the profile editor's audience chrome in every state, light and dark, to PNGs for design review.
 * Set CARD_SHOTS_DIR to write the images; without it the states are only composed.
 */
@OptIn(ExperimentalTestApi::class)
class ProfileEditShotsTest {

    private val outDir: File? = System.getenv("CARD_SHOTS_DIR")?.let(::File)?.also { it.mkdirs() }

    private val family = CardCircle("f", "Family")
    private val friends = CardCircle("fr", "Friends")
    private val work = CardCircle("w", "Work")
    private val climbing = CardCircle("c", "Climbing partners from the Tuesday bouldering gym night")
    private val circles = listOf(family, friends, work)

    private val filled = ProfileEditUiState(
        isLoading = false,
        circles = circles,
        values = mapOf(
            ProfileField.GIVEN_NAME to "Samwise",
            ProfileField.SURNAME to "Gamgee",
            ProfileField.NICKNAME to "Sam",
            ProfileField.BIO to "Gardener, cook, and the most loyal friend in the Shire.",
            ProfileField.BIRTHDAY to "1980-04-06",
            ProfileField.EMAIL to "sam@bagend.me",
            ProfileField.PHONE to "+14155550123",
            ProfileField.CITY to "Hobbiton",
            ProfileField.COUNTRY to "The Shire",
            ProfileField.INSTAGRAM to "samwise",
        ),
        audiences = mapOf(
            ProfileAttributeTypes.NAME to ProfileAudience.Public,
            ProfileAttributeTypes.NICKNAME to ProfileAudience.Circles(setOf("f", "fr")),
            ProfileAttributeTypes.BIO_SUMMARY to ProfileAudience.Public,
            ProfileAttributeTypes.BIRTHDAY to ProfileAudience.OnlyMe,
            ProfileAttributeTypes.EMAIL to ProfileAudience.Circles(setOf("f", "fr", "w")),
            ProfileAttributeTypes.PHONE to ProfileAudience.Circles(setOf("f")),
            ProfileAttributeTypes.ADDRESS to ProfileAudience.Circles(setOf("fr", "w")),
            ProfileAttributeTypes.INSTAGRAM to ProfileAudience.Public,
        ),
        links = listOf(
            LinkDraft("l1", "Garden journal", "https://sam.garden", ProfileAudience.Public),
            LinkDraft("l2", "Recipes", "https://sam.kitchen", ProfileAudience.Circles(setOf("f"))),
        ),
    )

    private fun with(type: String, audience: ProfileAudience, state: ProfileEditUiState = filled) =
        state.copy(audiences = state.audiences + (type to audience))

    private val avatar = ProfileAvatarEditUiState(isLoading = false)

    private class Shot(
        val name: String,
        val state: ProfileEditUiState,
        val expand: String? = null,
        val fontScale: Float = 1f,
        val rtl: Boolean = false,
        val widthDp: Int = PHONE_W,
        val heightDp: Int = PHONE_H,
    )

    private val shots = listOf(
        Shot("k8-01-overview", filled),
        Shot("k8-02-phone-one-circle", filled, expand = "Phone"),
        Shot("k8-03-email-every-circle", filled, expand = "Email"),
        Shot("k8-04-bio-public", filled, expand = "Bio"),
        Shot("k8-05-birthday-only-me", filled, expand = "Birthday"),
        Shot("k8-06-no-circle-picked", with(ProfileAttributeTypes.PHONE, ProfileAudience.Circles(emptySet())), expand = "Phone"),
        Shot("k8-07-other-circles", with(ProfileAttributeTypes.PHONE, ProfileAudience.Circles(setOf("f"), setOf("x1", "x2"))), expand = "Phone"),
        Shot("k8-08-no-contacts-circles", with(ProfileAttributeTypes.PHONE, ProfileAudience.Circles(emptySet()), filled.copy(circles = emptyList())), expand = "Phone"),
        Shot("k8-09-long-circle", with(ProfileAttributeTypes.PHONE, ProfileAudience.Circles(setOf("c", "f")), filled.copy(circles = circles + climbing))),
        Shot("k8-09b-long-circle-open", with(ProfileAttributeTypes.PHONE, ProfileAudience.Circles(setOf("c", "f")), filled.copy(circles = circles + climbing)), expand = "Phone"),
        Shot("k8-10-link-open", filled, expand = "Recipes"),
        Shot("k8-11-empty-profile", ProfileEditUiState(isLoading = false, circles = circles)),
        Shot("k8-12-loading", ProfileEditUiState()),
        Shot("k8-13-load-failed", ProfileEditUiState(isLoading = false, loadFailed = true)),
        Shot("k8-14-font-scale", filled, fontScale = 1.6f),
        Shot("k8-15-font-scale-open", filled, expand = "Phone", fontScale = 1.6f),
        Shot("k8-16-rtl-open", filled, expand = "Phone", rtl = true),
        Shot("k8-17-small-open", filled, expand = "Email", widthDp = 360, heightDp = 640),
    )

    @Test
    fun profileEditorRendersEveryState() {
        for (dark in listOf(false, true)) {
            for (shot in shots) render(shot, dark)
            renderDialog("k8-20-add-dialog", dark, circles)
            renderDialog("k8-21-add-dialog-no-circles", dark, emptyList())
        }
    }

    @Composable
    private fun themed(dark: Boolean, fontScale: Float = 1f, rtl: Boolean = false, content: @Composable () -> Unit) {
        CompositionLocalProvider(
            LocalDensity provides Density(SCALE, fontScale),
            LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
        ) {
            HomebaseTheme(darkTheme = dark, updatesSystemChrome = false) {
                CardExpressiveTheme(content)
            }
        }
    }

    private fun render(shot: Shot, dark: Boolean) = runDesktopComposeUiTest(
        width = (shot.widthDp * SCALE).toInt(),
        height = (shot.heightDp * SCALE).toInt(),
    ) {
        mainClock.autoAdvance = false
        setContent {
            themed(dark, shot.fontScale, shot.rtl) {
                var state by remember { mutableStateOf(shot.state) }
                ProfileEditContent(
                    uiState = state,
                    avatarUiState = avatar,
                    previewMode = false,
                    onTogglePreview = {},
                    onOpenCard = {},
                    onAction = { action ->
                        if (action is ProfileEditAction.AudienceChanged) {
                            state = state.copy(audiences = state.audiences + (action.type to action.audience))
                        }
                    },
                    onAvatarAction = {},
                    onPickAnonymousPhoto = {},
                    onPickOnlyMePhoto = {},
                    snackbarHostState = remember { SnackbarHostState() },
                )
            }
        }
        mainClock.advanceTimeBy(SETTLE_MS)
        shot.expand?.let { label ->
            // A scroll waits on its own animation frames, which a paused clock never delivers.
            mainClock.autoAdvance = true
            onAllNodesWithText(label).onFirst().performScrollTo().performClick()
            waitForIdle()
            onAllNodesWithText("Save").onFirst().performScrollTo()
            waitForIdle()
            mainClock.autoAdvance = false
            mainClock.advanceTimeBy(SETTLE_MS)
        }
        save(shot.name, dark)
    }

    private fun renderDialog(name: String, dark: Boolean, circles: List<CardCircle>) = runDesktopComposeUiTest(
        width = (PHONE_W * SCALE).toInt(),
        height = (PHONE_H * SCALE).toInt(),
    ) {
        mainClock.autoAdvance = false
        setContent {
            themed(dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
                AddAttributeDialog(
                    spec = ATTRIBUTE_SPECS.first { it.type == ProfileAttributeTypes.PHONE },
                    circles = circles,
                    onSave = { _, _ -> },
                    onDismiss = {},
                )
            }
        }
        mainClock.advanceTimeBy(SETTLE_MS)
        save(name, dark)
    }

    private fun ComposeUiTest.save(name: String, dark: Boolean) {
        val image = onAllNodes(isRoot()).onFirst().captureToImage()
        assertTrue(image.width > 0 && image.height > 0)
        outDir?.let { dir ->
            ImageIO.write(image.toAwtImage(), "png", File(dir, "$name-${if (dark) "dark" else "light"}.png"))
        }
    }

    private companion object {
        const val SCALE = 2f
        const val PHONE_W = 412
        const val PHONE_H = 892
        const val SETTLE_MS = 1_500L
    }
}
