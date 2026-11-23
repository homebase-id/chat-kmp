package id.homebase.core.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Redeem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.StringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import id.homebase.core.ui.assets.Filesync
import id.homebase.core.ui.assets.BootstrapChatOutlined
import id.homebase.core.ui.assets.HomebaseIcons
import id.homebase.core.ui.assets.RssFeed
import id.homebase.core.widget.SettingsRow
import id.homebase.core.widget.SettingsRowAction
import id.homebase.core.widget.SettingsSectionHeader
import id.homebase.core.widget.SettingsTopBar
import id.homebase.resources.MR
import id.homebase.resources.nav_chats
import id.homebase.resources.settings_chat_desc
import id.homebase.resources.contactbook_settings_section
import id.homebase.resources.email_settings_section
import id.homebase.resources.filesync_home_subtitle
import id.homebase.resources.filesync_label
import id.homebase.resources.location_settings_section
import id.homebase.resources.moments_settings_section
import id.homebase.resources.nav_calls
import id.homebase.resources.nav_feed
import id.homebase.resources.settings_apps_section_pages
import id.homebase.resources.settings_contactbook_desc
import id.homebase.resources.settings_email_desc
import id.homebase.resources.settings_feed_desc
import id.homebase.resources.settings_location_desc
import id.homebase.resources.settings_moments_desc
import id.homebase.resources.settings_section_apps
import androidx.compose.material.icons.outlined.PhotoLibrary
import id.homebase.resources.photobase_home_subtitle
import id.homebase.resources.photobase_label
import id.homebase.resources.settings_vault_desc
import id.homebase.resources.vault_settings_section
import id.homebase.resources.webdrop_home_subtitle
import id.homebase.resources.webdrop_label
import org.jetbrains.compose.resources.stringResource

private class AppsRow(val page: AppsPage, val tag: String, val icon: ImageVector, val title: StringResource, val subtitle: StringResource)

private val AppsRows = listOf(
    AppsRow(AppsPage.Chat, "chatSettingsButton", BootstrapChatOutlined, MR.string.nav_chats, MR.string.settings_chat_desc),
    AppsRow(AppsPage.Photobase, "photobaseSettingsButton", Icons.Outlined.PhotoLibrary, MR.string.photobase_label, MR.string.photobase_home_subtitle),
    AppsRow(AppsPage.Filesync, "filesyncSettingsButton", HomebaseIcons.Filesync, MR.string.filesync_label, MR.string.filesync_home_subtitle),
    AppsRow(AppsPage.Email, "emailSettingsButton", Icons.Outlined.MailOutline, MR.string.email_settings_section, MR.string.settings_email_desc),
    AppsRow(AppsPage.Feed, "feedSettingsButton", HomebaseIcons.RssFeed, MR.string.nav_feed, MR.string.settings_feed_desc),
    AppsRow(AppsPage.Vault, "vaultSettingsButton", Icons.Outlined.Lock, MR.string.vault_settings_section, MR.string.settings_vault_desc),
    AppsRow(AppsPage.Location, "locationSettingsButton", Icons.Outlined.LocationOn, MR.string.location_settings_section, MR.string.settings_location_desc),
    AppsRow(AppsPage.Moments, "momentsSettingsButton", Icons.Outlined.AutoAwesome, MR.string.moments_settings_section, MR.string.settings_moments_desc),
    AppsRow(AppsPage.Contacts, "contactBookSettingsButton", Icons.Outlined.People, MR.string.contactbook_settings_section, MR.string.settings_contactbook_desc),
    AppsRow(AppsPage.WebDrop, "webDropButton", Icons.Outlined.Redeem, MR.string.webdrop_label, MR.string.webdrop_home_subtitle),
)

internal val AppsSettingsRowTags = AppsRows.map { it.tag }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppsSettingsScreen(actions: AppsSettingsActions) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            SettingsTopBar(
                title = stringResource(MR.string.settings_section_apps),
                navigationIconModifier = Modifier.testTag("appsSettingsBackButton"),
                onBack = actions.onBack,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        val rowColor = MaterialTheme.colorScheme.surfaceContainer
        Column(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .testTag("appsSettingsList"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = AppsContentMaxWidth)
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            ) {
                AppsGroup(stringResource(MR.string.settings_apps_section_pages)) {
                    AppsRows.forEach { row ->
                        SettingsRow(
                            modifier = Modifier.testTag(row.tag),
                            icon = row.icon,
                            title = stringResource(row.title),
                            supportingText = stringResource(row.subtitle),
                            containerColor = rowColor,
                            action = SettingsRowAction.Navigate { actions.onOpenPage(row.page) },
                        )
                    }
                }
            }
        }
    }
}

internal val AppsContentMaxWidth = 640.dp

// The heading's top inset centres the first one on the pane sidebar's first row.
@Composable
private fun AppsGroup(title: String, rows: @Composable ColumnScope.() -> Unit) {
    SettingsSectionHeader(
        title = title,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 26.dp, bottom = 8.dp),
    )
    Column(
        modifier = Modifier.clip(MaterialTheme.shapes.large),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        content = rows,
    )
}
