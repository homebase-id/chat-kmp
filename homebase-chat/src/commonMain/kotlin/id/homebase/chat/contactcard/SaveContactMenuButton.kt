package id.homebase.chat.contactcard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.homebase.core.contacts.DeviceContactSaver
import id.homebase.core.contacts.rememberDeviceContactSaver
import id.homebase.resources.MR
import id.homebase.resources.chat_contact_card_save_homebase
import id.homebase.resources.chat_contact_card_save_phone
import id.homebase.resources.chat_contact_card_save_phone_failed
import id.homebase.resources.chat_contact_card_update_homebase
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

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
    val onClick = {
        if (deviceSaver == null) onSaveToContacts(descriptor, alreadySaved) else expanded = true
    }
    Box(modifier) {
        if (filled) {
            Button(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                SaveButtonContent(alreadySaved)
            }
        } else {
            TextButton(
                onClick = onClick,
                contentPadding = ButtonDefaults.TextButtonWithIconContentPadding,
            ) { SaveButtonContent(alreadySaved) }
        }
        if (deviceSaver != null) {
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (alreadySaved) MR.string.chat_contact_card_update_homebase
                                else MR.string.chat_contact_card_save_homebase,
                            ),
                        )
                    },
                    leadingIcon = { Icon(saveActionIcon(alreadySaved), contentDescription = null) },
                    onClick = {
                        expanded = false
                        onSaveToContacts(descriptor, alreadySaved)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(MR.string.chat_contact_card_save_phone)) },
                    leadingIcon = { Icon(Icons.Outlined.PhoneAndroid, contentDescription = null) },
                    onClick = {
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
private fun SaveButtonContent(alreadySaved: Boolean) {
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
    )
}
