package id.homebase.auth.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import id.homebase.core.auth.BrowserLauncher
import id.homebase.core.ui.assets.Homebase
import id.homebase.core.ui.assets.HomebaseIcons

@Composable
fun LoginScreen(
    viewModel: LoginViewModel,
    onNavigateHome: () -> Unit,
) {
    val uriHandler = id.homebase.core.util.getUriHandler()
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsState()

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onAction(LoginUiAction.AppResumed)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(uiState.uiEvent) {
        when (val uiEvent = uiState.uiEvent) {
            is LoginUiEvent.NavigateToHome -> {
                viewModel.eventConsumed()
                onNavigateHome()
            }

            is LoginUiEvent.ShowError -> {
                viewModel.eventConsumed()

                // TODO: Show snackbar
            }

            is LoginUiEvent.OpenUrl -> {
                viewModel.eventConsumed()
                BrowserLauncher.launchAuthBrowser(
                    url = uiEvent.url,
                    scope = viewModel.viewModelScope,
                    onOpenUrl = {
                        uriHandler.openUrl(it)
                    })
            }

            null -> {}
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surfaceContainerLowest
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState())
                .imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = HomebaseIcons.Homebase,
                contentDescription = "Homebase Logo",
                tint = Color.Unspecified,
                modifier = Modifier.size(72.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Welcome to Homebase",
                style = MaterialTheme.typography.headlineLarge,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Sign in with your Homebase ID",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(48.dp))

            when {
                uiState.isLoading -> LoginLoading()
                uiState.isAuthenticated -> LoginSuccess()
                uiState.errorMessage != null -> LoginError(
                    message = uiState.errorMessage ?: "",
                    homebaseId = uiState.homebaseId,
                    onRetryClick = { viewModel.onAction(LoginUiAction.RetryClicked(it)) }
                )

                else -> LoginForm(
                    onLoginClick = { viewModel.onAction(LoginUiAction.LoginClicked(it)) }
                )
            }
        }
    }
}

/* ---------- STATES ---------- */

@Composable
private fun LoginLoading() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Authenticating…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LoginSuccess() {
    Text(
        text = "Login successful",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.primary
    )
}

/* ---------- FORM ---------- */

@Composable
private fun LoginForm(
    onLoginClick: (homebaseId: String) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    var homebaseId by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        HomebaseIdField(
            value = homebaseId,
            onValueChange = {
                homebaseId = it
            },
            focusRequester = focusRequester,
            onDone = { onLoginClick(homebaseId) }
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { onLoginClick(homebaseId) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Sign In")
        }
    }
}

@Composable
private fun LoginError(
    message: String,
    homebaseId: String,
    onRetryClick: (homebaseId: String) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    var homebaseId by remember { mutableStateOf(homebaseId) }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        HomebaseIdField(
            value = homebaseId,
            onValueChange = {
                homebaseId = it
            },
            focusRequester = focusRequester,
            onDone = { onRetryClick(homebaseId) }
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { onRetryClick(homebaseId) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Try Again")
        }
    }
}

/* ---------- SHARED FIELD ---------- */

@Composable
private fun HomebaseIdField(
    value: String,
    onValueChange: (String) -> Unit,
    focusRequester: FocusRequester,
    onDone: () -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it) },
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
        placeholder = { Text("your.identity.id") },
        label = { Text("Homebase ID") },
        singleLine = true,
        shape = RoundedCornerShape(24.dp),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Uri,
            capitalization = KeyboardCapitalization.None,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() })
    )
}
