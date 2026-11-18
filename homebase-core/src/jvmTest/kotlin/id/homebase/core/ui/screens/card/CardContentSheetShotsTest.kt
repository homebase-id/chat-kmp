@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.card

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import id.homebase.api.client.profile.ProfileAttributeTypes
import id.homebase.core.ui.screens.profile.ProfileAudience
import id.homebase.core.ui.theme.HomebaseTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Renders the card content sheet over a stand-in card, light and dark, to PNGs under CARD_SHOTS_DIR for design review. */
@OptIn(ExperimentalTestApi::class)
class CardContentSheetShotsTest {

    private val outDir: File? = System.getenv("CARD_SHOTS_DIR")?.let(::File)?.also { it.mkdirs() }

    private val circles = listOf(CardCircle(FRIENDS_CIRCLE_ID, "Friends"), CardCircle(FAMILY_CIRCLE_ID, "Family"))
    private val longCircles = circles + CardCircle("c-long", "Climbing partners from the Tuesday bouldering gym night")
    private val friendsCard = CardAudience.Circle(FRIENDS_CIRCLE_ID, "Friends")
    private val discord = mapOf(ProfileAttributeTypes.KEY_DISCORD to "maniac_fighter")

    private class Shot(
        val name: String,
        val type: String,
        val data: Map<String, String>? = null,
        val circles: List<CardCircle>? = null,
        val card: CardAudience? = null,
        val fontScale: Float = 1f,
        val rtl: Boolean = false,
        val act: ComposeUiTest.() -> Unit = {},
    )

    private val shots = listOf(
        Shot("k19-01-discord-untouched", ProfileAttributeTypes.DISCORD, discord),
        Shot("k19-02-discord-audience-changed", ProfileAttributeTypes.DISCORD, discord, act = { pill("Family") }),
        Shot("k19-03-twitter-untouched", ProfileAttributeTypes.TWITTER, mapOf(ProfileAttributeTypes.KEY_TWITTER to "frodo")),
        Shot("k19-04-phone-spaced-untouched", ProfileAttributeTypes.PHONE, mapOf(ProfileAttributeTypes.KEY_PHONE to "+91 965 436 7034")),
        Shot("k19-05-discord-add", ProfileAttributeTypes.DISCORD),
        Shot("k19-06-twitter-add", ProfileAttributeTypes.TWITTER),
        Shot("k19-07-discord-long-circles", ProfileAttributeTypes.DISCORD, discord, circles = longCircles),
        Shot("k19-08-discord-font-scale", ProfileAttributeTypes.DISCORD, discord, fontScale = 1.6f),
        Shot("k19-09-discord-rtl", ProfileAttributeTypes.DISCORD, discord, rtl = true),
        Shot("k19-10-discord-public-card", ProfileAttributeTypes.DISCORD, discord, card = CardAudience.Public),
        Shot("k19-11-discord-no-circles", ProfileAttributeTypes.DISCORD, discord, circles = emptyList()),
    )

    private fun ComposeUiTest.pill(label: String) {
        onNodeWithTag("audience_pill_$label").performSemanticsAction(SemanticsActions.OnClick)
        mainClock.advanceTimeBy(SETTLE_MS)
    }

    @Test
    fun contentSheetRendersEveryState() {
        val only = System.getenv("CARD_SHOTS_ONLY")
        for (dark in listOf(false, true)) {
            for (shot in shots) if (only == null || shot.name.contains(only)) render(shot, dark)
        }
    }

    private fun render(shot: Shot, dark: Boolean) = runDesktopComposeUiTest(
        width = (REDMI_W * SCALE).toInt(),
        height = (REDMI_H * SCALE).toInt(),
    ) {
        mainClock.autoAdvance = false
        val editing = shot.data?.let {
            CardContentItem(
                id = Uuid.random(),
                type = shot.type,
                text = it.values.first(),
                audience = ProfileAudience.Circles(setOf(FRIENDS_CIRCLE_ID)),
                shown = true,
                locked = false,
                data = it,
            )
        }
        setContent {
            themed(dark, shot.fontScale, shot.rtl) {
                Box(Modifier.fillMaxSize().background(Color(CardDesign.baseArgb(CardDesign.BOARD))))
                ContentSheet(
                    type = shot.type,
                    card = shot.card ?: friendsCard,
                    circles = shot.circles ?: circles,
                    editing = editing,
                    onDismiss = {},
                    onDelete = {},
                )
            }
        }
        mainClock.advanceTimeBy(SETTLE_MS)
        shot.act(this)
        save(shot.name, dark)
    }

    @Composable
    private fun themed(dark: Boolean, fontScale: Float, rtl: Boolean, content: @Composable () -> Unit) {
        CompositionLocalProvider(
            LocalDensity provides Density(SCALE, fontScale),
            LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
        ) {
            HomebaseTheme(darkTheme = dark, updatesSystemChrome = false) {
                CardExpressiveTheme(content)
            }
        }
    }

    private fun ComposeUiTest.save(name: String, dark: Boolean) {
        val image = onAllNodes(isRoot()).onFirst().captureToImage()
        assertTrue(image.width > 0 && image.height > 0)
        outDir?.let { ImageIO.write(image.toAwtImage(), "png", File(it, "$name-${if (dark) "dark" else "light"}.png")) }
    }

    private companion object {
        const val SCALE = 2f
        const val REDMI_W = 393
        const val REDMI_H = 800
        const val SETTLE_MS = 1_500L
    }
}
