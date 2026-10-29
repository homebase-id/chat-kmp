package id.homebase.core.ui.screens.privatedrive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.homebase.core.auth.BrowserLauncher
import id.homebase.core.ui.auth.rememberAuthBrowserLauncher
import id.homebase.resources.MR
import id.homebase.resources.sign_in_again_button
import id.homebase.resources.sign_in_again_continue
import org.jetbrains.compose.resources.stringResource

/** Opens the sign-in browser; [blockedUrl] is set when the browser blocked the window, so a fresh click can open it. */
@Stable
class SignInAgainLauncher internal constructor(
    private val launch: (String) -> Boolean,
    private val onCallback: (String) -> Unit,
) {
    var blockedUrl by mutableStateOf<String?>(null)
        private set

    fun open(url: String) {
        if (launch(url)) {
            blockedUrl = null
            BrowserLauncher.onAuthBrowserOpened(url, onCallback)
        } else {
            blockedUrl = url
        }
    }
}

@Composable
fun rememberSignInAgainLauncher(onCallback: (String) -> Unit): SignInAgainLauncher {
    val launch = rememberAuthBrowserLauncher()
    val callback by rememberUpdatedState(onCallback)
    return remember(launch) { SignInAgainLauncher(launch) { callback(it) } }
}

/** Both banners a mini-app on a private-capable drive shows above its content, as [state] asks. */
@Composable
fun PrivateDriveBanners(
    state: PrivateDriveState,
    makePrivateText: String,
    signInTitle: String,
    signInBody: String,
    signIn: SignInAgainLauncher,
    onMakePrivate: () -> Unit,
    onSignInAgain: () -> Unit,
) {
    if (state.offerMakePrivate) {
        MakePrivateBanner(text = makePrivateText, onClick = onMakePrivate)
    }
    if (state.needsSignIn) {
        SignInAgainBanner(
            title = signInTitle,
            body = signInBody,
            blocked = signIn.blockedUrl != null,
            onSignIn = { signIn.blockedUrl?.let(signIn::open) ?: onSignInAgain() },
        )
    }
}

@Composable
private fun MakePrivateBanner(text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(imageVector = Icons.Outlined.Lock, contentDescription = null)
            Text(text = text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SignInAgainBanner(title: String, body: String, blocked: Boolean, onSignIn: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(imageVector = Icons.Outlined.Lock, contentDescription = null)
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                Text(text = body, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onSignIn) {
                Text(stringResource(if (blocked) MR.string.sign_in_again_continue else MR.string.sign_in_again_button))
            }
        }
    }
}
