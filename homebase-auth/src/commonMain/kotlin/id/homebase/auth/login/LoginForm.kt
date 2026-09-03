package id.homebase.auth.login

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import id.homebase.api.util.cleanDomain
import id.homebase.core.widget.HomebaseIdField
import id.homebase.resources.MR
import id.homebase.resources.login_error_details_copy
import id.homebase.resources.login_error_details_hide
import id.homebase.resources.login_error_details_show
import id.homebase.resources.login_id_label
import id.homebase.resources.login_id_placeholder
import id.homebase.resources.login_sign_in_button
import id.homebase.resources.login_try_again_button
import id.homebase.resources.timeout_in_seconds
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun LoginForm(
    errorMessage: String? = null,
    errorDetails: String? = null,
    homebaseId: String,
    // Non-null puts the form in its waiting state instead of replacing it: the field goes inert and
    // the submit button holds the spinner, so nothing on screen moves while auth runs.
    statusText: String? = null,
    showCountdown: Boolean = false,
    onIdentityInput: (String) -> Unit,
    onLoginClick: (homebaseId: String) -> Unit,
    onCreateAccountClick: () -> Unit,
    scale: Float = 1f,
) {
    val focusRequester = remember { FocusRequester() }
    var homebaseIdField by remember {
        mutableStateOf(TextFieldValue(homebaseId, selection = TextRange(homebaseId.length)))
    }

    // The field owns what the user types, so it seeds from state rather than reading it. Re-seed
    // when a new value does arrive: sign-up hands back the domain it created while this screen is
    // already composed, and a once-only seed would drop it. Typing must never write back into
    // homebaseId or this effect re-fires and drags the caret to the end on every keystroke.
    LaunchedEffect(homebaseId) {
        if (homebaseId.isNotBlank() && homebaseId != homebaseIdField.text) {
            homebaseIdField = TextFieldValue(homebaseId, selection = TextRange(homebaseId.length))
        }
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

    val busy = statusText != null

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        HomebaseIdField(
            value = homebaseIdField,
            onValueChange = {
                homebaseIdField = it.copy(text = it.text.cleanDomain().replace(".", " "))
                onIdentityInput(
                    homebaseIdField.text.cleanDomain(
                        preserveTrailingDot = false,
                        preserveTrailingDash = false,
                    )
                )
            },
            modifier = Modifier.heightIn(min = 56.dp * scale),
            textStyle =
                if (scale >= 1.4f) MaterialTheme.typography.titleLarge
                else LocalTextStyle.current,
            label = {
                // The label, not the placeholder, is what an unfocused field shows — and growing
                // the container via textStyle without this widened the mismatch it meant to close.
                Text(
                    text = stringResource(MR.string.login_id_label),
                    style =
                        if (scale >= 1.4f) MaterialTheme.typography.titleLarge
                        else LocalTextStyle.current,
                )
            },
            placeholder = {
                Text(
                    text = stringResource(MR.string.login_id_placeholder),
                    style =
                        if (scale >= 1.4f) MaterialTheme.typography.titleLarge
                        else LocalTextStyle.current,
                )
            },
            isError = errorMessage != null,
            // Read-only, not disabled: this is the control naming the identity being signed in, and
            // the disabled fill dropped it to 1.10:1 against the pane.
            readOnly = busy,
            // Always a slot, never a conditional one: the column is vertically centred, so a field
            // that changes height when the error arrives shifts the whole form under the cursor.
            supportingText = {
                errorMessage?.let {
                    Text(
                        text = it,
                        style =
                            if (scale >= 1.4f) MaterialTheme.typography.bodyLarge
                            else LocalTextStyle.current,
                        modifier = Modifier.testTag("error_message"),
                    )
                }
            },
            focusRequester = focusRequester,
            imeAction = ImeAction.Done,
            onImeAction = {
                onLoginClick(
                    homebaseIdField.text.cleanDomain(
                        preserveTrailingDot = false,
                        preserveTrailingDash = false,
                    )
                )
            },
        )
        if (errorMessage != null && errorDetails != null) {
            ErrorDetails(details = errorDetails)
        }

        Spacer(modifier = Modifier.height(16.dp * scale))

        Button(
            // Not disabled: a greyed primary is 1.3:1 against the pane, so the CTA vanished at the
            // one moment the user needs to see the app is working. The spinner carries the state.
            onClick = {
                if (!busy) {
                    onLoginClick(
                        homebaseIdField.text.cleanDomain(
                            preserveTrailingDot = false,
                            preserveTrailingDash = false,
                        )
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
                .heightIn(min = 56.dp * scale)
                .testTag(if (errorMessage != null) "try_again_button" else "login_button"),
        ) {
            when {
                busy -> CircularProgressIndicator(
                    modifier = Modifier.size(20.dp * scale),
                    strokeWidth = 2.dp * scale,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                errorMessage != null -> Text(
                    text = stringResource(MR.string.login_try_again_button),
                    style = if (scale >= 1.4f) MaterialTheme.typography.titleMedium else LocalTextStyle.current,
                )
                else -> Text(
                    text = stringResource(MR.string.login_sign_in_button),
                    style = if (scale >= 1.4f) MaterialTheme.typography.titleMedium else LocalTextStyle.current,
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp * scale))
        if (statusText != null) {
            // Matches the TextButton it stands in for, so the column keeps its height and the
            // heading does not shift when the form goes busy.
            Box(
                modifier = Modifier.heightIn(min = 40.dp * scale),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = statusText,
                    style =
                        if (scale >= 1.4f) MaterialTheme.typography.titleMedium
                        else MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("authenticating_text"),
                )
            }
            if (showCountdown) {
                var secondsLeft by remember { mutableIntStateOf(15) }
                LaunchedEffect(Unit) {
                    while (secondsLeft > 0) {
                        delay(1000)
                        secondsLeft--
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(MR.string.timeout_in_seconds, secondsLeft.toString()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            CreateAccountLink(onClick = onCreateAccountClick, scale = scale)
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
