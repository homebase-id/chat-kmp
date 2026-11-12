package id.homebase.chat.contactcard

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import id.homebase.core.contacts.DeviceContactSaver
import id.homebase.core.contacts.rememberDeviceContactSaver
import id.homebase.resources.MR
import id.homebase.resources.chat_contact_card_save_homebase
import id.homebase.resources.chat_contact_card_save_homebase_supporting
import id.homebase.resources.chat_contact_card_save_phone
import id.homebase.resources.chat_contact_card_save_phone_failed
import id.homebase.resources.chat_contact_card_save_phone_supporting
import id.homebase.resources.chat_contact_card_update_homebase
import id.homebase.resources.chat_contact_card_update_homebase_supporting
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

internal val SaveContactMenuShape = RoundedCornerShape(24.dp)
internal val SaveContactMenuWidth = 336.dp
internal val SaveContactMenuShadow = 2.dp
private val SaveContactItemShape = RoundedCornerShape(16.dp)

@Composable
internal fun saveContactMenuBorder() = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)

/**
 * The card's Save button. With a [deviceSaver] it opens a menu (Homebase contacts / Phone
 * contacts); without one it saves straight to Homebase.
 */
@Composable
internal fun SaveContactMenuButton(
    descriptor: ContactCardDescriptor,
    alreadySaved: Boolean,
    onSaveToContacts: (ContactCardDescriptor, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    snackbarHostState: SnackbarHostState? = LocalContactCardSnackbar.current,
    deviceSaver: DeviceContactSaver? = rememberDeviceContactSaver(),
) {
    var expanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val failedMessage = stringResource(MR.string.chat_contact_card_save_phone_failed)
    val hasMenu = deviceSaver != null
    val onClick = {
        if (deviceSaver == null) onSaveToContacts(descriptor, alreadySaved) else expanded = true
    }
    Box(modifier) {
        if (filled) {
            Button(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                SaveButtonContent(alreadySaved, hasMenu, expanded)
            }
        } else {
            TextButton(
                onClick = onClick,
                contentPadding = ButtonDefaults.TextButtonWithIconContentPadding,
            ) { SaveButtonContent(alreadySaved, hasMenu, expanded) }
        }
        if (deviceSaver != null) {
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                offset = DpOffset(0.dp, 4.dp),
                shape = SaveContactMenuShape,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 0.dp,
                shadowElevation = SaveContactMenuShadow,
                border = saveContactMenuBorder(),
                modifier = Modifier.width(SaveContactMenuWidth),
            ) {
                SaveContactMenuItems(
                    alreadySaved = alreadySaved,
                    onHomebase = {
                        expanded = false
                        onSaveToContacts(descriptor, alreadySaved)
                    },
                    onPhone = {
                        expanded = false
                        if (!deviceSaver.open(descriptor.toDeviceContact())) {
                            snackbarHostState?.let { scope.launch { it.showSnackbar(failedMessage) } }
                        }
                    },
                )
            }
        }
    }
}

@Composable
internal fun ColumnScope.SaveContactMenuItems(
    alreadySaved: Boolean,
    onHomebase: () -> Unit,
    onPhone: () -> Unit,
) {
    SaveDestinationItem(
        icon = saveActionIcon(alreadySaved),
        title = stringResource(
            if (alreadySaved) MR.string.chat_contact_card_update_homebase
            else MR.string.chat_contact_card_save_homebase,
        ),
        supporting = stringResource(
            if (alreadySaved) MR.string.chat_contact_card_update_homebase_supporting
            else MR.string.chat_contact_card_save_homebase_supporting,
        ),
        emphasized = true,
        onClick = onHomebase,
    )
    SaveDestinationItem(
        icon = Icons.Outlined.PhoneAndroid,
        title = stringResource(MR.string.chat_contact_card_save_phone),
        supporting = stringResource(MR.string.chat_contact_card_save_phone_supporting),
        onClick = onPhone,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SaveDestinationItem(
    icon: ImageVector,
    title: String,
    supporting: String,
    onClick: () -> Unit,
    emphasized: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    DropdownMenuItem(
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        },
        leadingIcon = {
            Surface(
                shape = CircleShape,
                color = if (emphasized) colors.primaryContainer else colors.secondaryContainer,
                contentColor = if (emphasized) colors.onPrimaryContainer else colors.onSecondaryContainer,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                }
            }
        },
        onClick = onClick,
        contentPadding = PaddingValues(start = 8.dp, end = 16.dp),
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .heightIn(min = 64.dp)
            .clip(SaveContactItemShape),
    )
}

@Composable
private fun RowScope.SaveButtonContent(alreadySaved: Boolean, hasMenu: Boolean, expanded: Boolean) {
    Icon(
        imageVector = saveActionIcon(alreadySaved),
        contentDescription = null,
        modifier = Modifier.size(18.dp),
    )
    Spacer(Modifier.width(8.dp))
    Text(
        text = stringResource(saveActionLabel(alreadySaved)),
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f, fill = false),
    )
    if (hasMenu) {
        val turn by animateFloatAsState(
            targetValue = if (expanded) 180f else 0f,
            animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow),
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            imageVector = Icons.Outlined.ExpandMore,
            contentDescription = null,
            modifier = Modifier.size(18.dp).rotate(turn),
        )
    }
}
