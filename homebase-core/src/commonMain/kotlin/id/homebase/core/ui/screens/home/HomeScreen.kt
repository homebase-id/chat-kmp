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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import id.homebase.core.ui.theme.HomebaseTheme

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onNavigateToChatList: () -> Unit,
    onNavigateToExamples: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.uiEvent.collect { event ->
            when (event) {
                is HomeUiEvent.NavigateToChatList -> onNavigateToChatList()
                is HomeUiEvent.NavigateToExample -> onNavigateToExamples()
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

            NavigationButton("RichText examples") { onAction(HomeUiAction.ExamplesClicked) }
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
