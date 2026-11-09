package id.homebase.chat.agentui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import id.homebase.core.ui.theme.HomebaseTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AgentUiReferenceScreenshotTest {
    private val outDir = File("../build/polish-shots").apply { mkdirs() }

    private val formJson = """
        {"root":"form","nodes":[
          {"id":"form","type":"column","spacing":"medium","children":["title","job","stats","notify","note","channel","send","warn"]},
          {"id":"title","type":"text","style":"title","markdown":"Weekly campaign"},
          {"id":"job","type":"progress","value":0.6,"label":"Rendering slides, 3 of 5"},
          {"id":"stats","type":"table","header":["Channel","Reach","Clicks"],"rows":[["X","12.4k","310"],["LinkedIn","8.1k","522"]]},
          {"id":"notify","type":"toggle","label":"Notify the team","value":true},
          {"id":"note","type":"textInput","label":"Note for the agent","value":"Lead with the team photo"},
          {"id":"channel","type":"select","label":"Post first on","value":"li","options":[{"id":"x","label":"X"},{"id":"li","label":"LinkedIn"},{"id":"ig","label":"Instagram"}]},
          {"id":"send","type":"button","label":"Send","style":"filled","action":{"id":"send","include":["notify","note","channel"]}},
          {"id":"warn","type":"card","tone":"error","children":["warn_t"]},
          {"id":"warn_t","type":"text","style":"body","markdown":"Instagram needs a square image."}
        ]}
    """.trimIndent()

    private fun decodeForm(): AgentUiTree =
        (AgentUiTree.decode(formJson.encodeToByteArray(), emptySet()) as AgentUiTreeResult.Valid).tree

    @Composable
    private fun Card(tree: AgentUiTree, pending: Set<String> = emptySet()) {
        AgentUiFrame(sentByYou = false) {
            val inputs = remember { AgentUiInputState(tree.inputs) }
            AgentUiTreeView(tree, inputs, { null }, true, pending, {})
        }
    }

    @Composable
    private fun AgentMessage(sender: String, time: String, content: @Composable () -> Unit) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiaryContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.SmartToy, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer) }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(sender, style = MaterialTheme.typography.labelLarge)
                    Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                content()
            }
        }
    }

    @Composable
    private fun OwnMessage(text: String) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(
                text,
                Modifier.padding(start = 64.dp)
                    .clip(RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    private fun ComposeUiTest.show(dark: Boolean) {
        val draft = AgentUiFixtures.decodeDraftPostCard()
        val form = decodeForm()
        val agent = "Campaign agent"
        val ask = "Draft the spring launch post and show me this week's numbers."
        setContent {
            HomebaseTheme(darkTheme = dark, followsSystemTheme = false) {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.widthIn(max = 840.dp).padding(16.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        OwnMessage(ask)
                        AgentMessage(agent, "09:41") { Card(draft, pending = setOf("approve")) }
                        AgentMessage(agent, "09:42") { Card(form) }
                    }
                }
            }
        }
        mainClock.advanceTimeBy(600)
        onNodeWithText("Approve").assertExists()
        onNodeWithText("Rendering slides, 3 of 5").assertExists()
    }

    private fun ComposeUiTest.save(name: String) {
        val image = onAllNodes(isRoot())[0].captureToImage().toAwtImage()
        assertTrue(image.width > 0 && image.height > 0)
        ImageIO.write(image, "png", File(outDir, "$name.png"))
    }

    @Test
    fun referenceCardsRenderAtPhoneAndWideWidthsInBothThemes() {
        for (dark in listOf(false, true)) {
            val theme = if (dark) "dark" else "light"
            runDesktopComposeUiTest(411, 1700) {
                show(dark)
                save("agent-ui-phone-$theme")
            }
            runDesktopComposeUiTest(1000, 1500) {
                show(dark)
                save("agent-ui-wide-$theme")
            }
        }
    }
}
