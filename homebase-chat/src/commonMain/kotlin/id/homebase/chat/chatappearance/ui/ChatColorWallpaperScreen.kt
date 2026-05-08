package id.homebase.chat.chatappearance.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.homebase.chat.chatappearance.model.ChatColor
import id.homebase.chat.chatappearance.ui.components.ColorCircleItem
import id.homebase.chat.chatappearance.ui.components.PhoneFramePreview
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.chat_color_reset
import id.homebase.resources.chat_color_reset_confirm
import id.homebase.resources.chat_color_title
import id.homebase.resources.chat_color_wallpaper_title
import id.homebase.resources.chat_wallpaper_dark_dims
import id.homebase.resources.chat_wallpaper_reset
import id.homebase.resources.chat_wallpaper_reset_confirm
import id.homebase.resources.chat_wallpaper_section
import id.homebase.resources.chat_wallpaper_set
import id.homebase.resources.menu_back
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatColorWallpaperScreen(
    uiState: ChatColorWallpaperViewModel.UiState,
    onNavigateBack: () -> Unit,
    onNavigateToChatColorPicker: () -> Unit,
    onNavigateToWallpaperPicker: () -> Unit,
    onDimInDarkThemeChanged: (Boolean) -> Unit,
    onResetChatColors: () -> Unit,
    onResetWallpapers: () -> Unit,
) {
    var showResetColorsDialog by remember { mutableStateOf(false) }
    var showResetWallpapersDialog by remember { mutableStateOf(false) }

    if (showResetColorsDialog) {
        AlertDialog(
            onDismissRequest = { showResetColorsDialog = false },
            text = { Text(stringResource(MR.string.chat_color_reset_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showResetColorsDialog = false
                    onResetChatColors()
                }) {
                    Text(stringResource(MR.string.chat_color_reset))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetColorsDialog = false }) {
                    Text(stringResource(MR.string.cancel))
                }
            },
        )
    }

    if (showResetWallpapersDialog) {
        AlertDialog(
            onDismissRequest = { showResetWallpapersDialog = false },
            text = { Text(stringResource(MR.string.chat_wallpaper_reset_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showResetWallpapersDialog = false
                    onResetWallpapers()
                }) {
                    Text(stringResource(MR.string.chat_wallpaper_reset))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetWallpapersDialog = false }) {
                    Text(stringResource(MR.string.cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(MR.string.chat_color_wallpaper_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(MR.string.menu_back),
                        )
                    }
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            // Phone frame preview — Signal-style miniature phone mockup
            Card(modifier = Modifier.fillMaxWidth()) {
                PhoneFramePreview(
                    chatColor = uiState.activeChatColor,
                    wallpaper = uiState.activeWallpaper,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }

            Spacer(Modifier.height(16.dp))

            // Chat Color section
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onNavigateToChatColorPicker)
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ColorCircleItem(
                            chatColor = uiState.activeChatColor,
                            isSelected = false,
                            isAutoItem = uiState.activeChatColor is ChatColor.Auto,
                            onClick = onNavigateToChatColorPicker,
                            modifier = Modifier.size(40.dp),
                        )
                        Spacer(Modifier.width(16.dp))
                        Text(
                            text = stringResource(MR.string.chat_color_title),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    TextButton(
                        onClick = { showResetColorsDialog = true },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        Text(
                            text = stringResource(MR.string.chat_color_reset),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // Wallpaper section
            Text(
                text = stringResource(MR.string.chat_wallpaper_section),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onNavigateToWallpaperPicker)
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(MR.string.chat_wallpaper_set),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(MR.string.chat_wallpaper_dark_dims),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = uiState.dimInDarkTheme,
                            onCheckedChange = onDimInDarkThemeChanged,
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    TextButton(
                        onClick = { showResetWallpapersDialog = true },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        Text(
                            text = stringResource(MR.string.chat_wallpaper_reset),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
