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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import id.homebase.api.util.cleanDomain
import id.homebase.core.auth.BrowserLauncher
import id.homebase.core.ui.assets.Homebase
import id.homebase.core.ui.assets.HomebaseIcons
import id.homebase.core.ui.auth.rememberAuthBrowserLauncher
import id.homebase.core.widget.SquircleIcon
import id.homebase.resources.MR
import id.homebase.resources.login_authenticating
import id.homebase.resources.login_create_account_button
import id.homebase.resources.login_id_label
import id.homebase.resources.login_id_placeholder
import id.homebase.resources.login_sign_in_button
import id.homebase.resources.login_sub_title
import id.homebase.resources.login_successful
import id.homebase.resources.login_title
import id.homebase.resources.login_try_again_button
import org.jetbrains.compose.resources.stringResource

@Composable
fun LoginScreen(
    viewModel: LoginViewModel,
    onNavigateHome: () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsState()

    // Get platform-specific browser launcher via Compose context
    val launchAuthBrowser = rememberAuthBrowserLauncher()

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
                launchAuthBrowser(uiEvent.url)
            }

            is LoginUiEvent.OpenAuthUrl -> {
                viewModel.eventConsumed()
                // Launch browser via Compose context (platform-specific)
                launchAuthBrowser(uiEvent.url)
                // Notify BrowserLauncher for callback setup (JVM needs server, iOS launches here)
                BrowserLauncher.onAuthBrowserOpened(uiEvent.url, viewModel::onCallbackUrl)
            }

            null -> {}
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surfaceContainerLowest
    ) {
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .padding(horizontal = 24.dp)
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            SquircleIcon(
                imageVector = HomebaseIcons.Homebase,
                contentDescription = "Homebase Logo",
                modifier = Modifier.size(72.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(MR.string.login_title),
                style = MaterialTheme.typography.headlineLarge,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(MR.string.login_sub_title),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(48.dp))

            when {
                uiState.isLoading -> LoginLoading()
                uiState.isAuthenticated -> LoginSuccess()
                uiState.errorMessage != null ->
                    LoginForm(
                        errorMessage = uiState.errorMessage ?: "",
                        homebaseId = uiState.homebaseId,
                        onLoginClick = {
                            viewModel.onAction(LoginUiAction.LoginClicked(it))
                        },
                        onCreateAccountClick = {
                            viewModel.onAction(LoginUiAction.CreateAccount)
                        },
                    )

                else ->
                    LoginForm(
                        homebaseId = uiState.homebaseId,
                        onLoginClick = {
                            viewModel.onAction(LoginUiAction.LoginClicked(it))
                        },
                        onCreateAccountClick = {
                            viewModel.onAction(LoginUiAction.CreateAccount)
                        },
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
            text = stringResource(MR.string.login_authenticating),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LoginSuccess() {
    Text(
        text = stringResource(MR.string.login_successful),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.primary
    )
}

/* ---------- FORM ---------- */

@Composable
private fun LoginForm(
    errorMessage: String? = null,
    homebaseId: String,
    onLoginClick: (homebaseId: String) -> Unit,
    onCreateAccountClick: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    var homebaseId by remember { mutableStateOf(homebaseId) }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        errorMessage?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
        HomebaseIdField(
            value = homebaseId,
            onValueChange = { homebaseId = it.cleanDomain() },
            focusRequester = focusRequester,
            onDone = { onLoginClick(homebaseId.cleanDomain(preserveTrailingDot = false)) }
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(onClick = { onLoginClick(homebaseId.cleanDomain(preserveTrailingDot = false)) }, modifier = Modifier.fillMaxWidth()) {
            if (errorMessage != null) Text(stringResource(MR.string.login_try_again_button)) else Text(stringResource(MR.string.login_sign_in_button))
        }
        Spacer(modifier = Modifier.height(16.dp))
        TextButton(
            onClick = onCreateAccountClick
        ) {
            Text(stringResource(MR.string.login_create_account_button))
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
        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
        placeholder = { Text(stringResource(MR.string.login_id_placeholder)) },
        label = { Text(stringResource(MR.string.login_id_label)) },
        singleLine = true,
        shape = RoundedCornerShape(24.dp),
        keyboardOptions =
            KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                capitalization = KeyboardCapitalization.None,
                imeAction = ImeAction.Done,
            ),
        keyboardActions = KeyboardActions(onDone = { onDone() })
    )
}
