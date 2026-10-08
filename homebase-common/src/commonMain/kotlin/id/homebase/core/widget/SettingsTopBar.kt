package id.homebase.core.widget

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import id.homebase.resources.MR
import id.homebase.resources.menu_back
import org.jetbrains.compose.resources.stringResource

// True while a settings screen renders inside the desktop settings pane, whose own header owns
// both the page title and dismissal — so the screen's bar would be a second title and a back
// arrow pointing nowhere.
internal val LocalSettingsPaneEmbedded: ProvidableCompositionLocal<Boolean> =
    compositionLocalOf { false }

@Composable
fun ProvideSettingsChrome(embedded: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalSettingsPaneEmbedded provides embedded, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    titleModifier: Modifier = Modifier,
    navigationIconModifier: Modifier = Modifier,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors(),
    actions: @Composable RowScope.() -> Unit = {},
) {
    if (LocalSettingsPaneEmbedded.current) return
    TopAppBar(
        title = { Text(title, modifier = titleModifier) },
        modifier = modifier,
        navigationIcon = {
            IconButton(onClick = onBack, modifier = navigationIconModifier) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(MR.string.menu_back),
                )
            }
        },
        actions = actions,
        scrollBehavior = scrollBehavior,
        colors = colors,
    )
}

/** [SettingsTopBar]'s Expressive large form: an emphasised headline that collapses on scroll. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsLargeTopBar(
    title: String,
    onBack: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    if (LocalSettingsPaneEmbedded.current) return
    LargeFlexibleTopAppBar(
        title = { Text(title, fontWeight = MaterialTheme.typography.headlineMediumEmphasized.fontWeight) },
        subtitle = subtitle?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        modifier = modifier,
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(MR.string.menu_back),
                )
            }
        },
        actions = actions,
        scrollBehavior = scrollBehavior,
    )
}
