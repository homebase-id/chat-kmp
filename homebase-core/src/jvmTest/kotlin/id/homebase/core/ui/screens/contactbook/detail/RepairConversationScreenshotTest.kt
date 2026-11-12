@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.contactbook.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import id.homebase.api.client.connections.ConnectionStatus
import id.homebase.core.ui.screens.contactbook.model.ContactBookEntry
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.resources.MR
import id.homebase.resources.contactbook_action_conversation_rebuild_not_needed
import id.homebase.resources.contactbook_action_conversation_rebuild_queued
import id.homebase.resources.contactbook_action_conversation_rebuilt
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalTestApi::class)
class RepairConversationScreenshotTest {
    private val outDir = File("../build/polish-shots").apply { mkdirs() }

    private val state = ContactDetailUiState(
        entry = ContactBookEntry(
            uniqueId = Uuid.random(),
            fileId = Uuid.random(),
            versionTag = null,
            displayName = "Samwise Gamgee",
            odinId = "samwise.gamgee.demo.rocks",
        ),
        connectionStatus = ConnectionStatus.Connected,
        conversationId = Uuid.random(),
    )

    private fun ComposeUiTest.shoot(name: String, width: Int, dark: Boolean) {
        setContent {
            HomebaseTheme(darkTheme = dark, followsSystemTheme = false) {
                Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().padding(16.dp)) {
                        Column(Modifier.align(Alignment.TopEnd).width(280.dp)) {
                            ManagementMenuGroups(state) {}
                        }
                        Column(
                            Modifier.align(Alignment.BottomCenter).width(minOf(width - 32, 560).dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            listOf(
                                MR.string.contactbook_action_conversation_rebuilt,
                                MR.string.contactbook_action_conversation_rebuild_not_needed,
                                MR.string.contactbook_action_conversation_rebuild_queued,
                            ).forEach { Snackbar { Text(stringResource(it)) } }
                        }
                    }
                }
            }
        }
        onNodeWithText("Repair conversation").assertExists()
        val image = onAllNodes(isRoot())[0].captureToImage().toAwtImage()
        ImageIO.write(image, "png", File(outDir, "repair-$name.png"))
    }

    @Test
    fun repairMenuAndOutcomes() {
        for ((label, width) in listOf("phone" to 411, "wide" to 1000)) {
            for (dark in listOf(false, true)) {
                runDesktopComposeUiTest(width, 800) {
                    shoot("$label-${if (dark) "dark" else "light"}", width, dark)
                }
            }
        }
    }
}
