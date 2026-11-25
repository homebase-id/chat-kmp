package id.homebase.core.widget

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow
import id.homebase.resources.MR
import id.homebase.resources.clear_selection
import id.homebase.resources.selected_count
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** The phone's top bar while items are picked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopBar(count: Int, onClear: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(pluralStringResource(MR.plurals.selected_count, count, count), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onClear) {
                Icon(Icons.Default.Close, contentDescription = stringResource(MR.string.clear_selection))
            }
        },
        actions = actions,
    )
}
