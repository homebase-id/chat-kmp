package id.homebase.core.ui.screens.card

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.graphics.shapes.Morph
import id.homebase.core.widget.MorphShape
import id.homebase.resources.profile_card_nfc_android_only
import id.homebase.resources.profile_card_nfc_android_to_android
import id.homebase.resources.profile_card_nfc_instead
import id.homebase.resources.profile_card_nfc_iphone_to_android
import id.homebase.resources.profile_card_nfc_iphone_to_iphone
import id.homebase.resources.profile_card_nfc_live
import id.homebase.resources.profile_card_nfc_not_possible
import id.homebase.resources.profile_card_nfc_works
import id.homebase.resources.profile_card_share
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Nfc
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.client.auth.OwnerSession
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
import id.homebase.resources.profile_card_nfc_iphone_body
import id.homebase.resources.profile_card_nfc_iphone_title
import id.homebase.resources.profile_card_nfc_switch_desc
import id.homebase.resources.profile_card_nfc_title
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

interface CardNfc {
    /** False on iPhone: Apple gives apps no way to act as a tap tag, so only the explanation is shown. */
    val canShare: Boolean
    val isEnabled: Boolean
    fun openSettings()
}

/** Null where there is no NFC action at all (desktop, web, Android without card emulation). */
@Composable
expect fun rememberCardNfc(): CardNfc?

internal data class CardTapShareGate(
    val profileCardEnabled: Boolean,
    val tapShareEnabled: Boolean,
    val odinId: String?,
    val design: String?,
    val sheetOpen: Boolean = false,
) {
    val servedUrl: String?
        get() = if (profileCardEnabled && (tapShareEnabled || sheetOpen) && odinId != null) cardLinkUrl(odinId, design) else null
}

class CardTapShare internal constructor(
    developerPreferences: DeveloperPreferences,
    cardPreferences: CardPreferences,
    user: StateFlow<OwnerSession?>,
) {
    constructor(
        developerPreferences: DeveloperPreferences,
        cardPreferences: CardPreferences,
        ownerSessionRepository: OwnerSessionRepository,
    ) : this(developerPreferences, cardPreferences, ownerSessionRepository.user)

    private val scope = supervisedScope("card-tap-share", Dispatchers.Default)
    private val sheetOpen = MutableStateFlow(false)

    fun setSheetOpen(open: Boolean) {
        sheetOpen.value = open
    }

    val servedUrl: StateFlow<String?> = combine(
        developerPreferences.profileCardEnabled,
        cardPreferences.tapShareEnabled,
        user,
        cardPreferences.design,
        sheetOpen,
    ) { profileCardEnabled, tapShareEnabled, user, design, open ->
        CardTapShareGate(profileCardEnabled, tapShareEnabled, user?.odinId?.domainName, design, open).servedUrl
    }.stateIn(scope, SharingStarted.Eagerly, null)
}

internal enum class HceStep { Enable, Prefer, Unprefer, Disable }

/**
 * Keeps the HCE service registered with the NFC stack only while serving, so a backgrounded or
 * killed app never sits in the routing table. `unsetPreferredService` throws unless resumed.
 */
internal fun hceSteps(resumed: Boolean, servedUrl: String?, enabled: Boolean, preferred: Boolean): List<HceStep> =
    if (resumed && servedUrl != null) {
        listOfNotNull(HceStep.Enable.takeUnless { enabled }, HceStep.Prefer.takeUnless { preferred })
    } else {
        listOfNotNull(HceStep.Unprefer.takeIf { preferred && resumed }, HceStep.Disable.takeIf { enabled })
    }

/** Serves [CardTapShare] as an NFC tag while the app is in front; a no-op where there is no HCE. */
@Composable
expect fun CardTapShareDriver(onShared: () -> Unit)

internal enum class CardNfcPanel(val titleRes: StringResource, val bodyRes: StringResource) {
    AndroidReady(MR.string.profile_card_nfc_title, MR.string.profile_card_nfc_body),
    AndroidOff(MR.string.profile_card_nfc_title, MR.string.profile_card_nfc_body),
    Iphone(MR.string.profile_card_nfc_iphone_title, MR.string.profile_card_nfc_iphone_body),
}

internal fun cardNfcPanel(canShare: Boolean, nfcEnabled: Boolean): CardNfcPanel = when {
    !canShare -> CardNfcPanel.Iphone
    nfcEnabled -> CardNfcPanel.AndroidReady
    else -> CardNfcPanel.AndroidOff
}

/** [onShareInstead] is the card's own share, offered where tapping can't work; null when sharing is unavailable. */
@Composable
fun CardNfcAction(nfc: CardNfc, onShareInstead: (() -> Unit)? = null) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Outlined.Nfc, contentDescription = stringResource(MR.string.profile_card_nfc))
    }
    if (open) {
        CardNfcSheet(nfc = nfc, onShareInstead = onShareInstead, onDismiss = { open = false })
    }
}

@Composable
private fun CardNfcSheet(nfc: CardNfc, onShareInstead: (() -> Unit)?, onDismiss: () -> Unit) {
    val tapShare = koinInject<CardTapShare>()
    if (nfc.canShare) {
        DisposableEffect(tapShare) {
            tapShare.setSheetOpen(true)
            onDispose { tapShare.setSheetOpen(false) }
        }
    }
    AdaptiveSheet(onDismiss = onDismiss, expandFully = true) {
        CardNfcPanelContent(
            panel = cardNfcPanel(nfc.canShare, nfc.isEnabled),
            onOpenSettings = nfc::openSettings,
            onShareInstead = onShareInstead?.let { share -> { dismiss(share) } },
            onDialog = isDialog,
        )
    }
}

@Composable
internal fun CardNfcPanelContent(panel: CardNfcPanel, onOpenSettings: () -> Unit, onShareInstead: (() -> Unit)?, onDialog: Boolean = false) {
    // The wide dialog's elevated surface is as light as the highest container; the lowest one stands out from it instead.
    val raised = if (onDialog) MaterialTheme.colorScheme.surfaceContainerLowest else MaterialTheme.colorScheme.surfaceContainerHighest
    val active = panel == CardNfcPanel.AndroidReady
    val motion = MaterialTheme.motionScheme
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        NfcPulse(active = active, idle = raised)
        AnimatedVisibility(
            visible = active,
            enter = fadeIn(motion.defaultEffectsSpec()) + expandVertically(motion.defaultSpatialSpec()),
            exit = fadeOut(motion.fastEffectsSpec()) + shrinkVertically(motion.defaultSpatialSpec()),
        ) {
            LivePill(modifier = Modifier.padding(top = 4.dp, bottom = 20.dp))
        }
        if (panel == CardNfcPanel.AndroidOff) {
            Text(
                text = stringResource(MR.string.profile_card_nfc_off),
                style = MaterialTheme.typography.titleMediumEmphasized,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
            Button(
                onClick = onOpenSettings,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.padding(top = 24.dp).heightIn(min = ButtonDefaults.MediumContainerHeight),
                contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
            ) {
                Icon(Icons.Outlined.Settings, contentDescription = null, modifier = Modifier.size(ButtonDefaults.iconSizeFor(ButtonDefaults.MediumContainerHeight)))
                Spacer(Modifier.width(ButtonDefaults.iconSpacingFor(ButtonDefaults.MediumContainerHeight)))
                Text(stringResource(MR.string.profile_card_nfc_open_settings), style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight))
            }
            return@Column
        }
        Text(
            text = stringResource(panel.titleRes),
            style = MaterialTheme.typography.headlineSmallEmphasized,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(panel.bodyRes),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
        if (panel == CardNfcPanel.AndroidReady) {
            AndroidOnlyChip(modifier = Modifier.padding(top = 20.dp))
        } else {
            TapCompatibility(container = raised, modifier = Modifier.padding(top = 24.dp))
            Text(
                text = stringResource(MR.string.profile_card_nfc_instead),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 20.dp),
            )
            if (onShareInstead != null) {
                Button(
                    onClick = onShareInstead,
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.padding(top = 16.dp).heightIn(min = ButtonDefaults.MediumContainerHeight),
                    contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
                ) {
                    Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(ButtonDefaults.iconSizeFor(ButtonDefaults.MediumContainerHeight)))
                    Spacer(Modifier.width(ButtonDefaults.iconSpacingFor(ButtonDefaults.MediumContainerHeight)))
                    Text(stringResource(MR.string.profile_card_share), style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight))
                }
            }
        }
    }
}

@Composable
private fun LivePill(modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(MR.string.profile_card_nfc_live), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun AndroidOnlyChip(modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.heightIn(min = 48.dp).padding(start = 16.dp, end = 20.dp),
        ) {
            Icon(Icons.Outlined.PhoneAndroid, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(MR.string.profile_card_nfc_android_only), style = MaterialTheme.typography.titleSmall)
        }
    }
}

private val IPHONE_TAP_ROUTES = listOf(
    MR.string.profile_card_nfc_iphone_to_iphone to false,
    MR.string.profile_card_nfc_iphone_to_android to false,
    MR.string.profile_card_nfc_android_to_android to true,
)

@Composable
private fun TapCompatibility(container: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        IPHONE_TAP_ROUTES.forEachIndexed { index, (route, works) ->
            val verdict = stringResource(if (works) MR.string.profile_card_nfc_works else MR.string.profile_card_nfc_not_possible)
            // A plain background: a Surface in `surface` colour would pick up the dialog's tonal elevation and vanish into it.
            Box(Modifier.fillMaxWidth().background(container, groupedShape(index, IPHONE_TAP_ROUTES.size))) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp).semantics(mergeDescendants = true) {},
                ) {
                    val colors = MaterialTheme.colorScheme
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .then(
                                if (works) Modifier.background(colors.secondary, MaterialShapes.Cookie9Sided.toShape())
                                else Modifier.border(1.5.dp, colors.outline, CircleShape),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (works) Icons.Outlined.Check else Icons.Outlined.Close,
                            contentDescription = null,
                            tint = if (works) colors.onSecondary else colors.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = stringResource(route), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = verdict,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (works) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CardTapShareSettingsRow() {
    val nfc = rememberCardNfc()?.takeIf { it.canShare } ?: return
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
private fun NfcPulse(active: Boolean, idle: Color) {
    val motion = MaterialTheme.motionScheme
    val colors = MaterialTheme.colorScheme
    val morph = remember { Morph(MaterialShapes.Circle, MaterialShapes.Cookie9Sided) }
    val progress by animateFloatAsState(if (active) 1f else 0f, motion.defaultSpatialSpec())
    val fill by animateColorAsState(if (active) colors.primaryContainer else idle, motion.defaultEffectsSpec())
    val tint by animateColorAsState(if (active) colors.onPrimaryContainer else colors.onSurfaceVariant, motion.defaultEffectsSpec())
    Box(modifier = Modifier.size(if (active) 160.dp else 120.dp), contentAlignment = Alignment.Center) {
        if (active) NfcPulseRings()
        Box(
            modifier = Modifier.size(88.dp).background(fill, MorphShape(morph, progress.coerceIn(0f, 1f))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Nfc, contentDescription = null, tint = tint, modifier = Modifier.size(40.dp))
        }
    }
}

// Its own composable so the infinite transition only runs while the rings are shown.
@Composable
private fun NfcPulseRings() {
    val transition = rememberInfiniteTransition()
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
    )
    val ringColor = MaterialTheme.colorScheme.primary
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
