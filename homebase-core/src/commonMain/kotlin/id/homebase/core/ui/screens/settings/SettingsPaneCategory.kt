package id.homebase.core.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.PermMedia
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onPlaced
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import id.homebase.core.ui.theme.Dimens
import id.homebase.core.ui.theme.NavigationIndicatorShape
import id.homebase.resources.MR
import id.homebase.resources.contactbook_settings_section
import id.homebase.resources.email_settings_section
import id.homebase.resources.moments_settings_section
import id.homebase.resources.settings_appearance
import id.homebase.resources.settings_category_general
import id.homebase.resources.settings_data_storage
import id.homebase.resources.settings_help
import id.homebase.resources.settings_keyboard
import id.homebase.resources.settings_media
import id.homebase.resources.settings_notifications
import id.homebase.resources.vault_settings_section
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

internal val SettingsSidebarWidth = 220.dp

internal enum class SettingsCategory(
    val label: StringResource,
    val icon: ImageVector,
) {
    General(MR.string.settings_category_general, Icons.Outlined.Tune),
    Notifications(MR.string.settings_notifications, Icons.Outlined.Notifications),
    Appearance(MR.string.settings_appearance, Icons.Outlined.Brightness6),
    Media(MR.string.settings_media, Icons.Outlined.PermMedia),
    Keyboard(MR.string.settings_keyboard, Icons.Outlined.Keyboard),
    Moments(MR.string.moments_settings_section, Icons.Outlined.AutoAwesome),
    Vault(MR.string.vault_settings_section, Icons.Outlined.Lock),
    Email(MR.string.email_settings_section, Icons.Outlined.MailOutline),
    Contacts(MR.string.contactbook_settings_section, Icons.Outlined.People),
    Storage(MR.string.settings_data_storage, Icons.Outlined.Storage),
    Help(MR.string.settings_help, Icons.AutoMirrored.Outlined.HelpOutline),
}

@Composable
internal fun SettingsSidebar(
    selected: SettingsCategory,
    onSelect: (SettingsCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rowBounds = remember { mutableStateMapOf<SettingsCategory, Rect>() }
    val target = rowBounds[selected]
    val pillTop = remember { Animatable(0f) }
    val pillHeight = remember { Animatable(0f) }
    var pillPlaced by remember { mutableStateOf(false) }
    val spec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    LaunchedEffect(target) {
        if (target == null) return@LaunchedEffect
        if (pillPlaced) {
            launch { pillTop.animateTo(target.top, spec) }
            launch { pillHeight.animateTo(target.height, spec) }
        } else {
            pillTop.snapTo(target.top)
            pillHeight.snapTo(target.height)
            pillPlaced = true
        }
    }
    val pillColor = MaterialTheme.colorScheme.primaryContainer

    Column(
        modifier = modifier
            .width(SettingsSidebarWidth)
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(vertical = Dimens.Spacing.item)
            .drawBehind {
                val bounds = target
                if (pillPlaced && bounds != null) {
                    val outline = NavigationIndicatorShape.createOutline(
                        Size(bounds.width, pillHeight.value),
                        layoutDirection,
                        this,
                    )
                    translate(bounds.left, pillTop.value) { drawOutline(outline, pillColor) }
                }
            },
    ) {
        SettingsCategory.entries.forEach { category ->
            NavigationDrawerItem(
                label = { Text(stringResource(category.label)) },
                icon = { Icon(category.icon, contentDescription = null) },
                selected = category == selected,
                onClick = { onSelect(category) },
                modifier = Modifier
                    .padding(horizontal = Dimens.Spacing.item)
                    .onPlaced { rowBounds[category] = it.boundsInParent() },
                shape = NavigationIndicatorShape,
                colors = NavigationDrawerItemDefaults.colors(
                    selectedContainerColor = Color.Transparent,
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        }
    }
}
