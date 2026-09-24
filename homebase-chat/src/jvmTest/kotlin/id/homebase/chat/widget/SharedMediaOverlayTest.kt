package id.homebase.chat.widget

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.chat.conversationsettings.SharedMediaItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class SharedMediaOverlayTest {

    private val item = SharedMediaItem(
        fileId = Uuid.random(),
        messageId = Uuid.random(),
        payload = PayloadDescriptor(key = "chat_web0", contentType = "image/jpeg"),
        keyHeader = KeyHeader.empty(),
        previewThumbnail = null,
        isSticker = false,
        date = Instant.fromEpochMilliseconds(0),
    )

    private var open by mutableStateOf<SharedMediaItem?>(null)

    private fun SkikoComposeUiTest.showGrid() {
        setContent {
            MaterialTheme {
                SharedMediaOverlay(
                    item = open,
                    modifier = Modifier.fillMaxSize(),
                    content = { hero ->
                        Row {
                            hero.Tile(item, Modifier.size(80.dp)) { _, _ ->
                                Box(Modifier.testTag(TILE).fillMaxSize())
                            }
                            Box(Modifier.testTag(NEXT).size(80.dp))
                        }
                    },
                    viewer = { _, _, _ -> Box(Modifier.testTag(VIEWER).fillMaxSize()) },
                )
            }
        }
        waitForIdle()
    }

    private fun SkikoComposeUiTest.midTransition(change: () -> Unit) {
        mainClock.autoAdvance = false
        change()
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
    }

    private fun SkikoComposeUiTest.settle() {
        mainClock.autoAdvance = true
        waitForIdle()
    }

    @Test
    fun `opening crossfades the tile into the viewer`() = runSkikoComposeUiTest {
        showGrid()
        val nextBefore = onNodeWithTag(NEXT).getBoundsInRoot()

        midTransition { open = item }
        onNodeWithTag(VIEWER).assertExists()
        onNodeWithTag(TILE).assertExists()

        settle()
        onNodeWithTag(VIEWER).assertExists()
        onNodeWithTag(TILE).assertDoesNotExist()
        assertEquals(nextBefore, onNodeWithTag(NEXT).getBoundsInRoot())
    }

    @Test
    fun `closing keeps the viewer on screen while it fades out`() = runSkikoComposeUiTest {
        showGrid()
        open = item
        waitForIdle()

        midTransition { open = null }
        onNodeWithTag(VIEWER).assertExists()
        onNodeWithTag(TILE).assertExists()

        settle()
        onNodeWithTag(VIEWER).assertDoesNotExist()
        onNodeWithTag(TILE).assertExists()
    }

    private companion object {
        const val TILE = "tile"
        const val NEXT = "next"
        const val VIEWER = "viewer"
    }
}
