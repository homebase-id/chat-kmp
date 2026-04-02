package id.homebase.auth.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
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
import id.homebase.resources.loading
import id.homebase.resources.login_authenticating
import id.homebase.resources.login_create_account_button
import id.homebase.resources.login_id_label
import id.homebase.resources.login_id_placeholder
import id.homebase.resources.login_sign_in_button
import id.homebase.resources.login_sub_title
import id.homebase.resources.login_successful
import id.homebase.resources.login_title
import id.homebase.resources.login_try_again_button
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.delay
import kotlinx.collections.immutable.persistentListOf
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

    LoginUi(
        uiState = uiState,
        onAction = viewModel::onAction
    )
}

@Composable
fun LoginUi(
    uiState: LoginUiState,
    onAction: (LoginUiAction )-> Unit,
) {
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

            when {
                uiState.isLoading -> LoginLoading(
                    driveProgresses = uiState.driveProgresses,
                    isPinging = uiState.isPinging
                )
                uiState.isAuthenticated -> LoginSuccess()
                uiState.errorMessage != null ->
                    LoginForm(
                        errorMessage = uiState.errorMessage,
                        homebaseId = uiState.homebaseId,
                        onLoginClick = {
                            onAction(LoginUiAction.LoginClicked(it))
                        },
                        onCreateAccountClick = {
                            onAction(LoginUiAction.CreateAccount)
                        },
                    )

                else ->
                    LoginForm(
                        homebaseId = uiState.homebaseId,
                        onLoginClick = {
                            onAction(LoginUiAction.LoginClicked(it))
                        },
                        onCreateAccountClick = {
                            onAction(LoginUiAction.CreateAccount)
                        },
                    )
            }
        }
    }
}

/* ---------- STATES ---------- */

@Composable
private fun LoginLoading(driveProgresses: ImmutableList<DriveProgress>, isPinging: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(MR.string.loading),
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        if (driveProgresses.isEmpty()) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(MR.string.login_authenticating),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (isPinging) {
                var secondsLeft by remember { mutableIntStateOf(10) }
                LaunchedEffect(Unit) {
                    while (secondsLeft > 0) {
                        delay(1000)
                        secondsLeft--
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Timeout in ${secondsLeft}s",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            driveProgresses.forEach { drive ->
                DriveProgressRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    drive = drive
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun DriveProgressRow(
    modifier: Modifier = Modifier,
    drive: DriveProgress
) {
    val successColor = Color(0xFF4CAF50)
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().height(36.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = drive.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (drive.count > 0 && !drive.completed) {
                Text(
                    text = "${drive.count} records",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
            } else if (drive.completed) {
                Text(
                    text = "${drive.total} records",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            when {
                drive.completed -> Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = "Done",
                    tint = successColor,
                    modifier = Modifier.size(20.dp),
                )
                drive.error != null -> Icon(
                    imageVector = Icons.Filled.Cancel,
                    contentDescription = "Failed",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        when {
            drive.completed -> LinearProgressIndicator(
                progress = { 1f },
                modifier = Modifier.fillMaxWidth(),
                color = successColor,
                trackColor = ProgressIndicatorDefaults.linearTrackColor,
                strokeCap = ProgressIndicatorDefaults.LinearStrokeCap,
            )
            drive.error != null -> if (drive.progress != null) {
                LinearProgressIndicator(
                    progress = { drive.progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.error,
                    trackColor = ProgressIndicatorDefaults.linearTrackColor,
                    strokeCap = ProgressIndicatorDefaults.LinearStrokeCap,
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.error,
                    trackColor = ProgressIndicatorDefaults.linearTrackColor,
                    strokeCap = ProgressIndicatorDefaults.LinearStrokeCap,
                )
            }
            else -> LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                strokeCap = ProgressIndicatorDefaults.LinearStrokeCap,
            )
        }
        if (drive.error != null) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = drive.error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
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
    var homebaseIdField by remember {
        mutableStateOf(TextFieldValue(homebaseId, selection = TextRange(homebaseId.length)))
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
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
            value = homebaseIdField,
            onValueChange = { homebaseIdField = it.copy(text = it.text.cleanDomain().replace(".", " ")) },
            focusRequester = focusRequester,
            onDone = { onLoginClick(homebaseIdField.text.cleanDomain(preserveTrailingDot = false)) }
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(onClick = { onLoginClick(homebaseIdField.text.cleanDomain(preserveTrailingDot = false)) }, modifier = Modifier.fillMaxWidth()) {
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
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    focusRequester: FocusRequester,
    onDone: () -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
        placeholder = { Text(stringResource(MR.string.login_id_placeholder)) },
        label = { Text(stringResource(MR.string.login_id_label)) },
        singleLine = true,
        shape = RoundedCornerShape(24.dp),
        visualTransformation = remember {
            VisualTransformation { text ->
                TransformedText(
                    text = AnnotatedString(text.text.replace(' ', '.')),
                    offsetMapping = OffsetMapping.Identity
                )
            }
        },
        keyboardOptions =
            KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                capitalization = KeyboardCapitalization.None,
                imeAction = ImeAction.Done,
            ),
        keyboardActions = KeyboardActions(onDone = { onDone() })
    )
}

@Preview(device = Devices.PIXEL_8)
@Composable
fun LoginUiFormPreview() {
    MaterialTheme {
        LoginUi(
            uiState = LoginUiState(
                homebaseId = "example.homebase.id",
                isLoading = false,
                isAuthenticated = false,
                errorMessage = null
            ),
            onAction = {}
        )
    }
}

@Preview(device = Devices.PIXEL_8)
@Composable
fun LoginUiLoadingPreview() {
    MaterialTheme {
        LoginUi(
            uiState = LoginUiState(
                homebaseId = "example.homebase.id",
                isLoading = true,
                isAuthenticated = false,
                errorMessage = null,
                driveProgresses = persistentListOf(
                    DriveProgress(driveId = "uuid-chat", name = "Chat", completed = true, total = 42, count = 42, progress = 1f),
                    DriveProgress(driveId = "uuid-feed", name = "Feed", count = 17, total = 17, progress = null),
                    DriveProgress(driveId = "uuid-contact", name = "Contact", error = "Network error"),
                )
            ),
            onAction = {}
        )
    }
}
