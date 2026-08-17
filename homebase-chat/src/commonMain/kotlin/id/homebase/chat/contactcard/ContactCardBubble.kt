package id.homebase.chat.contactcard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.ContactPage
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.homebase.core.util.initials
import id.homebase.resources.MR
import id.homebase.resources.chat_contact_unparseable
import id.homebase.resources.contactbook_edit_email
import id.homebase.resources.contactbook_edit_phone
import id.homebase.resources.contactbook_edit_organization
import org.jetbrains.compose.resources.stringResource

/**
 * In-stream bubble for a shared [ContactCardDescriptor]. Header-only — the whole card comes
 * with the message index, no payload fetch.
 */
@Composable
fun ContactCardBubble(
    descriptor: ContactCardDescriptor?,
    modifier: Modifier = Modifier,
) {
    val contentColor = MaterialTheme.colorScheme.onSurface

    if (descriptor == null || !descriptor.isValid()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.ContactPage,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(MR.string.chat_contact_unparseable),
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
            )
        }
        return
    }

    Column(
        modifier = modifier.widthIn(max = 280.dp).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                val initials = descriptor.summaryLine().initials()
                if (initials.isBlank()) {
                    Icon(
                        imageVector = Icons.Outlined.ContactPage,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                } else {
                    Text(
                        text = initials,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = descriptor.summaryLine(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (descriptor.organization.isNotBlank()) {
            DetailRow(
                icon = Icons.Outlined.Business,
                label = stringResource(MR.string.contactbook_edit_organization),
                value = descriptor.organization,
            )
        }
        val phoneLabel = stringResource(MR.string.contactbook_edit_phone)
        for (phone in descriptor.phones) {
            DetailRow(icon = Icons.Outlined.Phone, label = phoneLabel, value = phone)
        }
        val emailLabel = stringResource(MR.string.contactbook_edit_email)
        for (email in descriptor.emails) {
            DetailRow(icon = Icons.Outlined.AlternateEmail, label = emailLabel, value = email)
        }
    }
}

@Composable
private fun DetailRow(icon: ImageVector, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
