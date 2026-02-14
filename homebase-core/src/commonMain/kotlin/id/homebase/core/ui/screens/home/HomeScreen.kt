package id.homebase.core.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichText
import id.homebase.core.ui.theme.HomebaseTheme

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onNavigateToChatList: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.uiEvent.collect { event ->
            when (event) {
                is HomeUiEvent.NavigateToChatList -> onNavigateToChatList()
            }
        }
    }


    HomeUi(
        uiState = uiState,
        onAction = viewModel::onAction
    )
}

@Composable
fun HomeUi(
    uiState: HomeUiState,
    onAction: (HomeUiAction) -> Unit
) {
    val scrollState = rememberScrollState()
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp).verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = uiState.appName,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(24.dp))

            NavigationButton("Chat Messages") { onAction(HomeUiAction.ChatListClicked) }

            Spacer(modifier = Modifier.height(24.dp))

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
}

@Composable
private fun NavigationButton(text: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(text) }
}

@Preview
@Composable
fun HomeUiPreview() {
    HomebaseTheme {
        HomeUi(
            uiState = HomeUiState(),
            onAction = {}
        )
    }
}
