package id.homebase.chat.widget

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.runDesktopComposeUiTest
import id.homebase.api.client.drives.files.DescriptorContent
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.image.MediaQuality
import id.homebase.core.ui.theme.HomebaseTheme
import kotlin.test.Test

/**
 * Render-level lock on the badge policy: a photo carrying no quality must draw NOTHING,
 * exactly like one that explicitly recorded standard. Only HIGH draws the badge.
 */
@OptIn(ExperimentalTestApi::class)
class HdBadgeRenderTest {

    private val badgeDescription = "Sent in high quality"

    private fun image(descriptorContent: String?) = PayloadDescriptor(
        key = "chat_web0",
        contentType = "image/jpeg",
        descriptorContent = descriptorContent,
    )

    @Composable
    private fun BubbleFor(payload: PayloadDescriptor) {
        HomebaseTheme(darkTheme = false, followsSystemTheme = false) {
            Box(Modifier.fillMaxSize()) {
                if (payload.isHighQualityImage()) HdBadge()
            }
        }
    }

    private fun assertBadgeCount(payload: PayloadDescriptor, expected: Int) =
        runDesktopComposeUiTest(200, 200) {
            setContent { BubbleFor(payload) }
            onAllNodesWithContentDescription(badgeDescription).assertCountEquals(expected)
        }

    @Test
    fun highQualityPhoto_drawsTheBadge() {
        val wire = DescriptorContent.descriptorContentFromImage(
            isSticker = false,
            quality = MediaQuality.HIGH,
        )
        assertBadgeCount(image(wire), 1)
    }

    @Test
    fun explicitStandardPhoto_drawsNothing() {
        val wire = DescriptorContent.descriptorContentFromImage(
            isSticker = false,
            quality = MediaQuality.STANDARD,
        )
        assertBadgeCount(image(wire), 0)
    }

    @Test
    fun photoWithNoQualityRecorded_drawsNothing() {
        // The pre-flag corpus. Many of these really were HD; a badge here would be a guess
        // and a "standard" label would be a lie, so the surface stays empty.
        assertBadgeCount(image(""), 0)
        assertBadgeCount(image(null), 0)
    }
}
