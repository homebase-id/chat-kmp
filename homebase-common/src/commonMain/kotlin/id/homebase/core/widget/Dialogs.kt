package id.homebase.core.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun DialogCard(
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 16.dp,
    buttons: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollState = rememberScrollState()
    Card(
        modifier = modifier
            .fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 16.dp, bottom = bottomPadding),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(scrollState)
            ) {
                content()
            }
            if (buttons != null) {
                Spacer(modifier = Modifier.height(16.dp))
                buttons()
            }
        }
    }
}

@Composable
fun DialogButtons(
    onPrimaryClick: (() -> Unit)? = null,
    primaryText: String? = null,
    onSecondaryClick: (() -> Unit)? = null,
    secondaryText: String? = null,
    onTertiaryClick: (() -> Unit)? = null,
    tertiaryText: String? = null,
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (onTertiaryClick != null && tertiaryText != null) {
            TextButton(
                onClick = onTertiaryClick,
            ) {
                Text(tertiaryText)
            }
            Spacer(modifier = Modifier.width(8.dp))
        }
        if (onSecondaryClick != null && secondaryText != null) {
            TextButton(
                onClick = onSecondaryClick,
            ) {
                Text(secondaryText)
            }
            Spacer(modifier = Modifier.width(8.dp))
        }
        if (onPrimaryClick != null && primaryText != null) {
            TextButton(
                onClick = onPrimaryClick,
            ) {
                Text(primaryText)
            }
            Spacer(modifier = Modifier.width(8.dp))
        }
    }
}
