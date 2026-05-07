package id.homebase.chat.chatappearance.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.homebase.chat.chatappearance.model.ChatColor
import id.homebase.chat.chatappearance.model.ChatWallpaper
import id.homebase.chat.chatappearance.ui.components.ChatPreviewMockup
import id.homebase.chat.chatappearance.ui.components.ColorCircleItem
import id.homebase.resources.MR
import id.homebase.resources.chat_color_auto_description
import id.homebase.resources.chat_color_title
import id.homebase.resources.menu_back
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatColorPickerScreen(
    activeChatColor: ChatColor,
    activeWallpaper: ChatWallpaper,
    allColors: List<ChatColor>,
    onColorSelected: (ChatColor) -> Unit,
    onNavigateBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(MR.string.chat_color_title)) },
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
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Preview spanning full width
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    ChatPreviewMockup(
                        chatColor = activeChatColor,
                        wallpaper = activeWallpaper,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (activeChatColor is ChatColor.Auto) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            ),
                        ) {
                            Text(
                                text = stringResource(MR.string.chat_color_auto_description),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }

            // Auto item
            item {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    ColorCircleItem(
                        chatColor = ChatColor.Auto,
                        isSelected = activeChatColor is ChatColor.Auto,
                        isAutoItem = true,
                        onClick = { onColorSelected(ChatColor.Auto) },
                    )
                }
            }

            // All preset colors
            items(allColors, key = { it.id }) { color ->
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    ColorCircleItem(
                        chatColor = color,
                        isSelected = activeChatColor.id == color.id,
                        onClick = { onColorSelected(color) },
                    )
                }
            }
        }
    }
}
