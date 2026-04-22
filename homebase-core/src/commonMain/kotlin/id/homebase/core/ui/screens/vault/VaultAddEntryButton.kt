package id.homebase.core.ui.screens.vault

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import id.homebase.resources.MR
import id.homebase.resources.vault_entry_add
import org.jetbrains.compose.resources.stringResource

private val CARD_WIDTH = 100.dp
private val CARD_HEIGHT = 120.dp
private val CARD_CORNER = 12.dp

@Composable
fun VaultAddEntryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val addLabel = stringResource(MR.string.vault_entry_add)
    val shape = RoundedCornerShape(CARD_CORNER)

    Surface(
        modifier = modifier
            .width(CARD_WIDTH)
            .height(CARD_HEIGHT)
            .clip(shape)
            .clickable(
                onClickLabel = addLabel,
                onClick = onClick,
            ),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = addLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
