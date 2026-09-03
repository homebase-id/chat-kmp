package id.homebase.auth.login

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import id.homebase.resources.MR
import id.homebase.resources.done
import id.homebase.resources.failed
import id.homebase.resources.login_authenticating
import id.homebase.resources.login_continue_button
import id.homebase.resources.login_create_account_button
import id.homebase.resources.login_popup_blocked
import id.homebase.resources.login_sub_title
import id.homebase.resources.login_sync_title
import id.homebase.resources.login_title_lead
import id.homebase.resources.login_title_rest
import id.homebase.resources.login_welcome_back_rest
import id.homebase.resources.login_successful
import id.homebase.resources.login_use_different_id
import id.homebase.resources.login_waiting_for_browser
import id.homebase.resources.number_of_records
import kotlinx.collections.immutable.ImmutableList
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun LoginContent(
    uiState: LoginUiState,
    errorText: String?,
    onAction: (LoginUiAction) -> Unit,
    pendingAuthUrl: String?,
    onContinueAuth: () -> Unit,
    compact: Boolean,
    scale: Float = 1f,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            pendingAuthUrl == null && uiState.isLoading && uiState.driveProgresses.isNotEmpty() ->
                LoginDriveSync(
                    driveProgresses = uiState.driveProgresses,
                    compact = compact,
                    scale = scale,
                )
            else -> {
                val lastIdentity = uiState.lastIdentity
                val offeringLastIdentity = uiState.offeringLastIdentity
                LoginHeadline(
                    compact = compact,
                    scale = scale,
                    // The card names the identity right below, so the generic instruction under
                    // "Welcome back" is telling the user something they can already see. On
                    // expanded the brand panel and the field's own label already say it twice.
                    // The domain rides the subtitle slot rather than the headline's light half:
                    // as the second headline line it wrapped to two lines and moved the whole block
                    // 18px mid-crossfade, every time an identity resolved under the cursor.
                    subtitle = when {
                        !compact || offeringLastIdentity -> null
                        uiState.identityPreview?.displayName != null ->
                            uiState.identityPreview.odinId.domainName
                        else -> stringResource(MR.string.login_sub_title)
                    },
                    lead = when {
                        offeringLastIdentity -> stringResource(MR.string.login_title_lead)
                        // The brand panel already names the identity when it is showing.
                        compact -> uiState.identityPreview?.displayName
                            ?: stringResource(MR.string.login_title_lead)
                        else -> stringResource(MR.string.login_title_lead)
                    },
                    rest = when {
                        offeringLastIdentity -> stringResource(MR.string.login_welcome_back_rest)
                        compact && uiState.identityPreview?.displayName != null -> null
                        else -> stringResource(MR.string.login_title_rest)
                    },
                )
                Spacer(modifier = Modifier.height(if (compact) 40.dp else 32.dp * scale))
                StateSlot(scale = scale) {
                if (pendingAuthUrl != null) {
                    // The browser blocked the popup — re-open it from this fresh click gesture.
                    LoginPopupBlocked(onContinue = onContinueAuth, scale = scale)
                } else if (uiState.isAuthenticated) {
                    LoginSuccess(scale = scale)
                } else if (lastIdentity != null && offeringLastIdentity) {
                    LastIdentityCard(
                        identity = lastIdentity,
                        showName = compact,
                        onContinue = { onAction(LoginUiAction.ContinueAsLastIdentity) },
                    )
                    Spacer(modifier = Modifier.height(24.dp * scale))
                    TextButton(
                        onClick = { onAction(LoginUiAction.UseDifferentId) },
                        modifier = Modifier.testTag("use_different_id_button"),
                    ) {
                        Text(
                            text = stringResource(MR.string.login_use_different_id),
                            style =
                                if (scale >= 1.4f) MaterialTheme.typography.titleMedium
                                else LocalTextStyle.current,
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp * scale))
                    CreateAccountLink(onClick = { onAction(LoginUiAction.CreateAccount) }, scale = scale)
                } else {
                    LoginForm(
                        errorMessage = errorText,
                        errorDetails = uiState.errorDetails,
                        homebaseId = uiState.homebaseId,
                        statusText = if (uiState.isLoading) {
                            stringResource(
                                if (uiState.isAwaitingAuthConfirmation) MR.string.login_waiting_for_browser
                                else MR.string.login_authenticating
                            )
                        } else {
                            null
                        },
                        showCountdown = uiState.isPinging,
                        onIdentityInput = { onAction(LoginUiAction.IdentityInputChanged(it)) },
                        onLoginClick = { onAction(LoginUiAction.LoginClicked(it)) },
                        onCreateAccountClick = { onAction(LoginUiAction.CreateAccount) },
                        scale = scale,
                    )
                }
                }
            }
        }
    }
}

/**
 * Holds the height the tallest state needs so the headline above it cannot move: the column is
 * vertically centred, so any state that changes height shifts everything, headline included.
 */
@Composable
private fun StateSlot(scale: Float, content: @Composable ColumnScope.() -> Unit) {
    Box(
        // Centred, not top-aligned: the reserve's unfilled remainder all sat below the content and
        // pushed every state's ink above the pane's optical centre.
        modifier = Modifier.fillMaxWidth().heightIn(min = StateSlotMinHeight * scale),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}

// Measured: 232 pins the headline across every state, 216 lets it drift 2px — which AnimatedContent
// renders as a wobble mid-crossfade. It also leaves the form states' ink ~10px above geometric
// centre in an 800px pane, which is where it belongs: that ~1% rise is optical centring. The short
// states (success, popup-blocked) sit ~34px high instead — the correct consequence of hanging every
// state from one pinned headline rather than recentring each on its own height.
private val StateSlotMinHeight = 232.dp
private val CompactSubtitleHeight = 24.dp

/** One affordance for one action: the same weight wherever this link appears. */
@Composable
internal fun CreateAccountLink(onClick: () -> Unit, scale: Float = 1f) {
    TextButton(onClick = onClick, modifier = Modifier.testTag("create_account_button")) {
        Text(
            text = stringResource(MR.string.login_create_account_button),
            style =
                if (scale >= 1.4f) MaterialTheme.typography.titleMedium
                else LocalTextStyle.current,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LoginHeadline(lead: String, rest: String?, subtitle: String?, compact: Boolean, scale: Float) {
    // headlineLarge is a phone size; on a desktop pane it reads as shouting.
    val style = when {
        // headlineMedium, not Large: Montserrat Alternates at 32sp wraps "welcome to homebase" on a
        // 412dp phone, and the one-line "welcome back" state then sits 34px lower. Only a phone is
        // that narrow; a scaled portrait form is as wide as the expanded pane's.
        compact && scale < 1.15f -> MaterialTheme.typography.headlineMedium
        scale >= 1.4f -> MaterialTheme.typography.displayMedium
        scale >= 1.15f -> MaterialTheme.typography.displaySmall
        else -> MaterialTheme.typography.headlineMedium
    }
    // Manual p12: web headlines are lowercase, bold lead phrase then light continuation.
    val headline = buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(lead) }
        if (rest != null) {
            withStyle(SpanStyle(fontWeight = FontWeight.Light)) { append(' ').append(rest) }
        }
    }
    AnimatedContent(
        targetState = headline,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
    ) { text ->
        Text(
            text = text,
            style = style,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("title_text"),
        )
    }
    // Reserved even when absent: the last-identity state drops this line deliberately, and without
    // the reserve its headline sat 14px below every other compact state.
    val line = @Composable {
        subtitle?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Light,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("subtitle_text"),
            )
        }
    }
    if (compact) {
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier.heightIn(min = CompactSubtitleHeight),
            contentAlignment = Alignment.TopCenter,
        ) { line() }
    } else if (subtitle != null) {
        Spacer(modifier = Modifier.height(8.dp))
        line()
    }
}

@Composable
private fun LoginPopupBlocked(onContinue: () -> Unit, scale: Float) {
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
        Spacer(modifier = Modifier.height(16.dp * scale))
        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth()
                .heightIn(min = 56.dp * scale)
                .testTag("continue_auth_button"),
        ) {
            Text(stringResource(MR.string.login_continue_button))
        }
    }
}

@Composable
private fun LoginDriveSync(
    driveProgresses: ImmutableList<DriveProgress>,
    compact: Boolean,
    scale: Float = 1f,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(MR.string.login_sync_title),
            style = when {
                compact && scale < 1.15f -> MaterialTheme.typography.headlineLarge
                scale >= 1.4f -> MaterialTheme.typography.displayMedium
                scale >= 1.15f -> MaterialTheme.typography.displaySmall
                else -> MaterialTheme.typography.headlineMedium
            },
            // Montserrat Alternates ships Light and Bold; an unweighted headline matches neither.
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("loading_text"),
        )
        Spacer(modifier = Modifier.height(32.dp * scale))
        driveProgresses.forEach { drive ->
            DriveProgressRow(modifier = Modifier.fillMaxWidth(), drive = drive, scale = scale)
            Spacer(modifier = Modifier.height(12.dp * scale))
        }
    }
}

@Composable
private fun DriveProgressRow(
    modifier: Modifier = Modifier,
    drive: DriveProgress,
    scale: Float = 1f,
) {
    val rowText =
        if (scale >= 1.4f) MaterialTheme.typography.titleMedium
        else MaterialTheme.typography.bodyMedium
    val countText =
        if (scale >= 1.4f) MaterialTheme.typography.bodyMedium
        else MaterialTheme.typography.bodySmall
    val successColor = MaterialTheme.colorScheme.primary
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().height(36.dp * scale),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = drive.name,
                style = rowText,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag("drive_name_${drive.driveId}"),
            )
            Spacer(modifier = Modifier.weight(1f))
            if (drive.count > 0 && !drive.completed) {
                Text(
                    text = stringResource(MR.string.number_of_records, drive.count.toString()),
                    style = countText,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
            } else if (drive.completed) {
                Text(
                    text = stringResource(MR.string.number_of_records, drive.total.toString()),
                    style = countText,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            when {
                drive.completed -> Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = stringResource(MR.string.done),
                    tint = successColor,
                    modifier = Modifier.size(20.dp * scale),
                )
                drive.error != null -> Icon(
                    imageVector = Icons.Filled.Cancel,
                    contentDescription = stringResource(MR.string.failed),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp * scale),
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp * scale))
        when {
            drive.completed -> LinearProgressIndicator(
                progress = { 1f },
                modifier = Modifier.fillMaxWidth().height(4.dp * scale),
                color = successColor,
                trackColor = ProgressIndicatorDefaults.linearTrackColor,
                strokeCap = ProgressIndicatorDefaults.LinearStrokeCap,
            )
            drive.error != null -> if (drive.progress != null) {
                LinearProgressIndicator(
                    progress = { drive.progress },
                    modifier = Modifier.fillMaxWidth().height(4.dp * scale),
                    color = MaterialTheme.colorScheme.error,
                    trackColor = ProgressIndicatorDefaults.linearTrackColor,
                    strokeCap = ProgressIndicatorDefaults.LinearStrokeCap,
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(4.dp * scale),
                    color = MaterialTheme.colorScheme.error,
                    trackColor = ProgressIndicatorDefaults.linearTrackColor,
                    strokeCap = ProgressIndicatorDefaults.LinearStrokeCap,
                )
            }
            else -> LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(4.dp * scale),
                strokeCap = ProgressIndicatorDefaults.LinearStrokeCap,
            )
        }
        if (drive.error != null) {
            Spacer(modifier = Modifier.height(2.dp * scale))
            Text(
                text = drive.error,
                style = countText,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun LoginSuccess(scale: Float) {
    Icon(
        imageVector = Icons.Filled.CheckCircle,
        // The line below says the same thing; announcing it twice helps nobody.
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(48.dp * scale),
    )
    Spacer(modifier = Modifier.height(16.dp * scale))
    Text(
        text = stringResource(MR.string.login_successful),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.testTag("success_text"),
    )
}
