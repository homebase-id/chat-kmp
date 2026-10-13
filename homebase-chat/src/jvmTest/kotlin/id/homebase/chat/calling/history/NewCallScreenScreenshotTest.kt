@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.chat.calling.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import id.homebase.api.common.OdinId
import id.homebase.chat.data.ContactUiModel
import id.homebase.chat.services.convo.contact.ContactConnectionState
import id.homebase.core.ui.theme.HomebaseTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class NewCallScreenScreenshotTest {
    private val outDir = File("build/calls-shots").apply { mkdirs() }

    private fun contact(name: String, state: ContactConnectionState) = ContactUiModel(
        id = Uuid.random(), odinId = OdinId("${name.lowercase()}.demo.rocks"), name = name,
        avatarInitials = name.take(1), connectionState = state,
    )

    private val state = NewCallUiState(
        groups = listOf(
            NewCallGroup("F", listOf(NewCallRow(contact("Frodo", ContactConnectionState.Connected), true))),
            NewCallGroup("M", listOf(NewCallRow(contact("Merry", ContactConnectionState.NotConnected), false))),
            NewCallGroup("S", listOf(NewCallRow(contact("Samwise", ContactConnectionState.Connected), true))),
        ),
        callable = true,
    )

    private val avatar: @Composable (NewCallRow) -> Unit = { r ->
        Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Text(r.contact.avatarInitials, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }

    private fun ComposeUiTest.show(
        search: String = "",
        dark: Boolean = false,
        actions: MutableList<NewCallAction> = mutableListOf(),
    ) = setContent {
        val searchState = TextFieldState().also { it.setTextAndPlaceCursorAtEnd(search) }
        HomebaseTheme(darkTheme = dark, followsSystemTheme = false) {
            NewCallContent(state, searchState, onAction = { actions += it }, onBack = {}, avatar = avatar)
        }
    }

    private fun ComposeUiTest.save(name: String) {
        val image = onAllNodes(isRoot())[0].captureToImage().toAwtImage()
        val colors = HashSet<Int>()
        for (x in 0 until image.width step 7) for (y in 0 until image.height step 7) colors += image.getRGB(x, y)
        assertTrue(colors.size > 8, "$name rendered blank")
        ImageIO.write(image, "png", File(outDir, "$name.png"))
    }

    @Test
    fun pickerShowsRowsDisablesUnconnectedAndStartsFromTheButtons() = runDesktopComposeUiTest(411, 800) {
        val actions = mutableListOf<NewCallAction>()
        show(actions = actions)
        onNodeWithText("Frodo").assertExists()
        onNodeWithText("Not connected").assertExists()
        val voice = onAllNodes(hasContentDescription("Voice call"))
        val video = onAllNodes(hasContentDescription("Video call"))
        voice[0].assertIsEnabled()
        voice[1].assertIsNotEnabled()
        video[1].assertIsNotEnabled()
        save("newcall-light")
        video[0].performClick()
        assertEquals(1, actions.size)
        assertEquals(true, (actions.single() as NewCallAction.Start).video)
    }

    @Test
    fun searchTextAndDarkThemeRender() {
        runDesktopComposeUiTest(411, 800) { show(search = "fro"); save("newcall-search") }
        runDesktopComposeUiTest(411, 800) { show(dark = true); save("newcall-dark") }
    }
}
