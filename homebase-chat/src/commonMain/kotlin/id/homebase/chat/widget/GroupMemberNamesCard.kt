package id.homebase.chat.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.People
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import id.homebase.resources.MR
import id.homebase.resources.chat_group_members_list
import id.homebase.resources.chat_group_members_list_many
import kotlinx.collections.immutable.ImmutableList
import org.jetbrains.compose.resources.stringResource

@Composable
fun GroupMemberNamesCard(
    modifier: Modifier = Modifier,
    participantNames: ImmutableList<String>,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center
    ) {
        OutlinedCard(
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier.padding(
                    horizontal = 16.dp,
                    vertical = 8.dp
                ), verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.People, contentDescription = null)
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = buildParticipantsString(participantNames),
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun buildParticipantsString(participantNames: ImmutableList<String>): String {
    return if (participantNames.size > 4) {
        val participants = participantNames.take(3)
        val remaining = participantNames.size - 3
        stringResource(MR.string.chat_group_members_list_many, participants.joinToString(), remaining.toString())
    } else {
        stringResource(MR.string.chat_group_members_list, participantNames.joinToString())
    }
}