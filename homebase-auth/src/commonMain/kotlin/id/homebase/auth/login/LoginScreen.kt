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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.util.cleanDomain
import id.homebase.core.auth.BrowserLauncher
import id.homebase.core.util.InAppBrowser
import id.homebase.core.ui.assets.Homebase
import id.homebase.core.ui.assets.HomebaseIcons
import id.homebase.core.ui.auth.rememberAuthBrowserLauncher
import id.homebase.core.widget.HomebaseIdField
import id.homebase.core.widget.SquircleIcon
import id.homebase.resources.MR
import id.homebase.resources.login_error_details_copy
import id.homebase.resources.login_error_details_hide
import id.homebase.resources.login_error_details_show
import id.homebase.resources.done
import id.homebase.resources.failed
import id.homebase.resources.homebase_logo
import id.homebase.resources.loading
import id.homebase.resources.login_authenticating
import id.homebase.resources.login_continue_button
import id.homebase.resources.login_create_account_button
import id.homebase.resources.login_id_label
import id.homebase.resources.login_id_placeholder
import id.homebase.resources.login_popup_blocked
import id.homebase.resources.login_sign_in_button
import id.homebase.resources.login_sub_title
import id.homebase.resources.login_successful
import id.homebase.resources.login_title
import id.homebase.resources.login_try_again_button
import id.homebase.resources.number_of_records
import id.homebase.resources.timeout_in_seconds
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

@Composable
fun LoginScreen(
    viewModel: LoginViewModel,
    onNavigateHome: () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Get platform-specific browser launcher via Compose context
    val launchAuthBrowser = rememberAuthBrowserLauncher()

    // The auth window is opened only after the identity passes its format + ping checks (see
    // LoginViewModel.startLogin). On web that open can be blocked by the popup blocker because it
    // happens after the async ping — in that case we stash the URL and surface a "Continue" button
    // that re-opens it from a fresh click gesture. Always null / unused on native.
    var pendingAuthUrl by remember { mutableStateOf<String?>(null) }

    val openAuth: (String) -> Unit = { url ->
        if (launchAuthBrowser(url)) {
            pendingAuthUrl = null
            // Set up the callback listener now that the auth window is actually open.
            BrowserLauncher.onAuthBrowserOpened(url, viewModel::onCallbackUrl)
        } else {
            pendingAuthUrl = url
        }
    }

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
                // Sign-up is a plain web page, not an OAuth callback: no shared session or token
                // hand-back, just a page the user must be able to get back out of. That's
                // InAppBrowser, not the auth-callback launcher. Consume only after the open is
                // issued, never before.
                InAppBrowser.open(uiEvent.url)
                viewModel.eventConsumed()
            }

            is LoginUiEvent.OpenAuthUrl -> {
                viewModel.eventConsumed()
                // Open the auth window (platform-specific). Sets up the callback listener on
                // success; on a blocked web popup, surfaces the "Continue" button instead.
                openAuth(uiEvent.url)
            }

            null -> {}
        }
    }

    LoginUi(
        uiState = uiState,
        pendingAuthUrl = pendingAuthUrl,
        onContinueAuth = { pendingAuthUrl?.let { openAuth(it) } },
        onAction = viewModel::onAction
    )
}

@Composable
fun LoginUi(
    uiState: LoginUiState,
    onAction: (LoginUiAction )-> Unit,
    pendingAuthUrl: String? = null,
    onContinueAuth: () -> Unit = {},
) {
    val errorText: String? = uiState.error?.let { error ->
        when (error) {
            is LoginError.Res ->
                if (error.arg != null) stringResource(error.resource, error.arg)
                else stringResource(error.resource)
            is LoginError.Message -> error.text
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
                contentDescription = stringResource(MR.string.homebase_logo),
                modifier = Modifier.size(72.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            when {
                // Web popup was blocked — re-open it from this fresh click gesture.
                pendingAuthUrl != null -> LoginPopupBlocked(onContinue = onContinueAuth)
                uiState.isLoading -> LoginLoading(
                    driveProgresses = uiState.driveProgresses,
                    isPinging = uiState.isPinging
                )
                uiState.isAuthenticated -> LoginSuccess()
                else ->
                    LoginForm(
                        errorMessage = errorText,
                        errorDetails = uiState.errorDetails,
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

/**
 * A press-to-reveal block under a login error showing the raw technical cause (exception
 * type + message, or HTTP status) so a user can read and copy the exact error for support,
 * instead of only the friendly message. Collapsed by default.
 */
@Composable
private fun ErrorDetails(details: String) {
    var expanded by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    Spacer(modifier = Modifier.height(4.dp))
    TextButton(
        onClick = { expanded = !expanded },
        modifier = Modifier.testTag("error_details_toggle"),
    ) {
        Text(
            text = stringResource(
                if (expanded) MR.string.login_error_details_hide else MR.string.login_error_details_show
            ),
            style = MaterialTheme.typography.labelLarge,
        )
    }
    if (expanded) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                SelectionContainer {
                    Text(
                        text = details,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("error_details_text"),
                    )
                }
                TextButton(
                    onClick = { clipboard.setText(AnnotatedString(details)) },
                    modifier = Modifier.align(Alignment.End).testTag("error_details_copy"),
                ) {
                    Text(stringResource(MR.string.login_error_details_copy))
                }
            }
        }
    }
}

@Composable
private fun LoginPopupBlocked(onContinue: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(MR.string.login_popup_blocked),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("popup_blocked_text"),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth().testTag("continue_auth_button"),
        ) {
            Text(stringResource(MR.string.login_continue_button))
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
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("loading_text"),
        )
        Spacer(modifier = Modifier.height(16.dp))
        if (driveProgresses.isEmpty()) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(MR.string.login_authenticating),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("authenticating_text"),
            )
            if (isPinging) {
                var secondsLeft by remember { mutableIntStateOf(15) }
                LaunchedEffect(Unit) {
                    while (secondsLeft > 0) {
                        delay(1000)
                        secondsLeft--
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(MR.string.timeout_in_seconds, secondsLeft.toString()),
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
                modifier = Modifier.testTag("drive_name_${drive.driveId}"),
            )
            Spacer(modifier = Modifier.weight(1f))
            if (drive.count > 0 && !drive.completed) {
                Text(
                    text = stringResource(MR.string.number_of_records, drive.count.toString()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
            } else if (drive.completed) {
                Text(
                    text = stringResource(MR.string.number_of_records, drive.total.toString()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            when {
                drive.completed -> Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = stringResource(MR.string.done),
                    tint = successColor,
                    modifier = Modifier.size(20.dp),
                )
                drive.error != null -> Icon(
                    imageVector = Icons.Filled.Cancel,
                    contentDescription = stringResource(MR.string.failed),
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
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.testTag("success_text"),
    )
}

/* ---------- FORM ---------- */

@Composable
private fun LoginForm(
    errorMessage: String? = null,
    errorDetails: String? = null,
    homebaseId: String,
    onLoginClick: (homebaseId: String) -> Unit,
    onCreateAccountClick: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    var homebaseIdField by remember {
        mutableStateOf(TextFieldValue(homebaseId, selection = TextRange(homebaseId.length)))
    }

    // Focus the ID field once on first entry — not on every re-entry/recomposition, which kept
    // re-popping the keyboard (#1054).
    var didFocus by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!didFocus) {
            focusRequester.requestFocus()
            didFocus = true
        }
    }

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(MR.string.login_title),
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("title_text"),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(MR.string.login_sub_title),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("subtitle_text"),
        )
        Spacer(modifier = Modifier.height(48.dp))
        errorMessage?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("error_message"),
            )
            if (errorDetails != null) {
                ErrorDetails(details = errorDetails)
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
        HomebaseIdField(
            value = homebaseIdField,
            onValueChange = { homebaseIdField = it.copy(text = it.text.cleanDomain().replace(".", " ")) },
            label = { Text(stringResource(MR.string.login_id_label)) },
            placeholder = { Text(stringResource(MR.string.login_id_placeholder)) },
            focusRequester = focusRequester,
            imeAction = ImeAction.Done,
            onImeAction = { onLoginClick(homebaseIdField.text.cleanDomain(preserveTrailingDot = false, preserveTrailingDash = false)) },
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { onLoginClick(homebaseIdField.text.cleanDomain(preserveTrailingDot = false, preserveTrailingDash = false)) },
            modifier = Modifier.fillMaxWidth().testTag(if (errorMessage != null) "try_again_button" else "login_button"),
        ) {
            if (errorMessage != null) Text(stringResource(MR.string.login_try_again_button)) else Text(stringResource(MR.string.login_sign_in_button))
        }
        Spacer(modifier = Modifier.height(16.dp))
        TextButton(
            onClick = onCreateAccountClick,
            modifier = Modifier.testTag("create_account_button"),
        ) {
            Text(stringResource(MR.string.login_create_account_button))
        }
    }
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
                error = null
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
                error = null,
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
