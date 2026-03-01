package id.homebase.core.ui.screens.widget

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichText

@Composable
fun RichTextExample() {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .padding(16.dp)
            .verticalScroll(scrollState)
    ) {
        Text(
            text = "RichText examples",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )

        val testText = "<b>Html content<b/>\n*Markdown 👋 content*\nNormal text"
        var text by remember { mutableStateOf(testText) }
        val richTextNormal by remember {
            mutableStateOf(RichTextState().apply {
                config.listIndent = 0
                setText(text)
            })
        }
        val richTextHtml by remember {
            mutableStateOf(RichTextState().apply {
                config.listIndent = 0
                setHtml(text)
            })
        }
        val richTextMarkDown by remember {
            mutableStateOf(RichTextState().apply {
                config.listIndent = 0
                setMarkdown(text)
            })
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
        Text(
            "Input",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleLarge,
        )
        OutlinedTextField(text, onValueChange = {
            richTextNormal.setText(it)
            richTextHtml.setHtml(it)
            richTextMarkDown.setMarkdown(it)
            text = it
        })
        TextExample(
            mode = "setText",
            richTextState = richTextNormal
        )
        TextExample(
            mode = "HTML",
            richTextState = richTextHtml
        )
        TextExample(
            mode = "Markdown",
            richTextState = richTextMarkDown
        )
    }
}

@Composable
fun TextExample(mode: String, richTextState: RichTextState) {
    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
    DisplayBox("RichText ($mode)") {
        RichText(richTextState)
    }
    DisplayBox("Material3.Text (toMarkdown())") {
        Text(richTextState.toMarkdown())
    }
    DisplayBox("Material3.Text (toHtml())") {
        Text(richTextState.toHtml())
    }
    DisplayBox("Material3.Text (annotatedString)") {
        Text(richTextState.annotatedString)
    }
}

@Composable
fun DisplayBox(
    title: String,
    content: @Composable () -> Unit
) {
    Spacer(Modifier.height(16.dp))
    Text(
        title,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.titleLarge,
    )
    Box(modifier = Modifier.padding(top = 8.dp).border(1.dp, Color.Blue).padding(16.dp)) {
        content()
    }
}
