package id.homebase.core.ui.screens.widget

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichText

@Composable
fun RichTextExample() {
    Column(
        modifier = Modifier.padding(16.dp)
    ) {
        Text(
            text = "Richtext examples",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )

        val testText = "<b>Html content<b/>\n*Markdown content*\nNormal text"
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
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            "material3.Text",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleLarge,
        )
        Text(text, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider()
        Text(
            "RichText (setText)",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleLarge,
        )
        RichText(richTextNormal, textAlign = TextAlign.Center)
        Text(
            "Output (MD, HTML, AnnotatedString)",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleLarge,
        )
        Text(richTextNormal.toMarkdown())
        Text(richTextNormal.toHtml())
        Text(richTextNormal.annotatedString)
        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider()
        Text(
            "RichText (setHtml)",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleLarge,
        )
        RichText(richTextHtml, textAlign = TextAlign.Center)
        Text(
            "Output (MD, HTML, AnnotatedString)",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleLarge,
        )
        Text(richTextHtml.toMarkdown())
        Text(richTextHtml.toHtml())
        Text(richTextHtml.annotatedString)
        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider()
        Text(
            "RichText (setMarkdown)",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleLarge,
        )
        RichText(richTextMarkDown, textAlign = TextAlign.Center)
        Text(
            "Output (MD, HTML, AnnotatedString)",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleLarge,
        )
        Text(richTextMarkDown.toMarkdown())
        Text(richTextMarkDown.toHtml())
        Text(richTextMarkDown.annotatedString)
    }
}