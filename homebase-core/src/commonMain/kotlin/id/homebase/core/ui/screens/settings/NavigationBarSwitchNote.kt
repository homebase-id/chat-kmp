package id.homebase.core.ui.screens.settings

import androidx.compose.runtime.Composable
import id.homebase.core.util.isExpandedLayout
import id.homebase.resources.MR
import id.homebase.resources.settings_navigation_bar_switch_wide
import org.jetbrains.compose.resources.stringResource

/** Beneath an app's navigation-bar switch on a wide layout, whose side bar shows every app anyway. */
@Composable
internal fun navigationBarSwitchNote(): String? =
    if (isExpandedLayout()) stringResource(MR.string.settings_navigation_bar_switch_wide) else null
