package id.homebase.core.ui.screens.card

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Nfc
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.client.auth.OwnerSessionRepository
import id.homebase.api.coroutines.supervisedScope
import id.homebase.core.settings.DeveloperPreferences
import id.homebase.core.widget.AdaptiveSheet
import id.homebase.core.widget.SettingsRow
import id.homebase.core.widget.SettingsRowAction
import id.homebase.resources.MR
import id.homebase.resources.profile_card_nfc
import id.homebase.resources.profile_card_nfc_body
import id.homebase.resources.profile_card_nfc_off
import id.homebase.resources.profile_card_nfc_open_settings
import id.homebase.resources.profile_card_nfc_switch
import id.homebase.resources.profile_card_nfc_switch_desc
import id.homebase.resources.profile_card_nfc_title
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

interface CardNfc {
    val isEnabled: Boolean
    fun openSettings()
}

/** Null where this device can't act as an NFC tag (no HCE, iOS, desktop, web). */
@Composable
expect fun rememberCardNfc(): CardNfc?

internal data class CardTapShareGate(
    val profileCardEnabled: Boolean,
    val tapShareEnabled: Boolean,
    val odinId: String?,
    val design: String?,
) {
    val servedUrl: String?
        get() = if (profileCardEnabled && tapShareEnabled && odinId != null) cardLinkUrl(odinId, design) else null
}

class CardTapShare(
    developerPreferences: DeveloperPreferences,
    cardPreferences: CardPreferences,
    ownerSessionRepository: OwnerSessionRepository,
) {
    private val scope = supervisedScope("card-tap-share", Dispatchers.Default)

    val servedUrl: StateFlow<String?> = combine(
        developerPreferences.profileCardEnabled,
        cardPreferences.tapShareEnabled,
        ownerSessionRepository.user,
        cardPreferences.design,
    ) { profileCardEnabled, tapShareEnabled, user, design ->
        CardTapShareGate(profileCardEnabled, tapShareEnabled, user?.odinId?.domainName, design).servedUrl
    }.stateIn(scope, SharingStarted.Eagerly, null)
}

/** Serves [CardTapShare] as an NFC tag while the app is in front; a no-op where there is no HCE. */
@Composable
expect fun CardTapShareDriver(onShared: () -> Unit)

@Composable
fun CardNfcAction(nfc: CardNfc) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Outlined.Nfc, contentDescription = stringResource(MR.string.profile_card_nfc))
    }
    if (open) {
        CardNfcSheet(nfc = nfc, onDismiss = { open = false })
    }
}

@Composable
private fun CardNfcSheet(nfc: CardNfc, onDismiss: () -> Unit) {
    val toggle = rememberTapShareToggle()
    val active = nfc.isEnabled && toggle.checked
    AdaptiveSheet(onDismiss = onDismiss, expandFully = true) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            NfcPulse(active = active)
            if (active) {
                Text(
                    text = stringResource(MR.string.profile_card_nfc_title),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(MR.string.profile_card_nfc_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (!nfc.isEnabled) {
                Text(
                    text = stringResource(MR.string.profile_card_nfc_off),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                FilledTonalButton(onClick = nfc::openSettings) {
                    Text(stringResource(MR.string.profile_card_nfc_open_settings))
                }
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth(),
            ) {
                TapShareRow(toggle = toggle, supportingText = stringResource(MR.string.profile_card_nfc_switch_desc))
            }
        }
    }
}

@Composable
fun CardTapShareSettingsRow() {
    val nfc = rememberCardNfc() ?: return
    TapShareRow(
        toggle = rememberTapShareToggle(),
        supportingText = stringResource(if (nfc.isEnabled) MR.string.profile_card_nfc_switch_desc else MR.string.profile_card_nfc_off),
    )
}

@Composable
private fun rememberTapShareToggle(): SettingsRowAction.Toggle {
    val preferences = koinInject<CardPreferences>()
    val enabled by preferences.tapShareEnabled.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    return SettingsRowAction.Toggle(enabled) { scope.launch { preferences.setTapShareEnabled(it) } }
}

@Composable
private fun TapShareRow(toggle: SettingsRowAction.Toggle, supportingText: String) {
    SettingsRow(
        icon = Icons.Outlined.Nfc,
        title = stringResource(MR.string.profile_card_nfc_switch),
        supportingText = supportingText,
        action = toggle,
    )
}

@Composable
private fun NfcPulse(active: Boolean) {
    val transition = rememberInfiniteTransition()
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
    )
    val ringColor = MaterialTheme.colorScheme.primary
    Box(modifier = Modifier.size(160.dp), contentAlignment = Alignment.Center) {
        if (active) {
            listOf(0f, 0.5f).forEach { phase ->
                Surface(
                    shape = CircleShape,
                    color = ringColor,
                    modifier = Modifier
                        .size(160.dp)
                        .graphicsLayer {
                            val p = (progress + phase) % 1f
                            scaleX = 0.45f + 0.55f * p
                            scaleY = 0.45f + 0.55f * p
                            alpha = (1f - p) * 0.35f
                        },
                ) {}
            }
        }
        Surface(
            shape = CircleShape,
            color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(80.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Nfc, contentDescription = null, modifier = Modifier.size(40.dp))
            }
        }
    }
}
