package id.homebase.core.widget

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import id.homebase.core.emoji.EmojiData
import id.homebase.core.emoji.EmojiParser
import id.homebase.core.util.isMobile
import kotlinx.coroutines.launch

@Composable
fun EmojiSelection(
    modifier: Modifier = Modifier,
    onEmojiSelected: (String) -> Unit,
) {
    var emojiData by remember { mutableStateOf<EmojiData?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            emojiData = EmojiParser.loadEmojiData()
            isLoading = false
        } catch (e: Exception) {
            error = e.message
            isLoading = false
        }
    }

    when {
        isLoading -> {
            // Show loading state
            Box(modifier = modifier) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }

        error != null -> {
            Box(modifier = modifier) {
                Text("Error: $error", modifier = Modifier.align(Alignment.Center))
            }
        }
    }

    emojiData?.let { emojiData ->
        val groupedEmojis = remember { EmojiParser.groupEmojis(emojiData.emojis, emojiData.groups) }
        var searchQuery by remember { mutableStateOf("") }
        var selectedSection by remember { mutableStateOf(groupedEmojis.keys.first()) }
        val isSearching = searchQuery.isNotEmpty()
        val lazyGridState = rememberLazyGridState()
        val scope = rememberCoroutineScope()

        val filteredEmojis = remember(searchQuery, selectedSection) {
            if (isSearching) {
                // Search across all emojis
                EmojiParser.filterEmojis(searchQuery, emojiData.emojis)
            } else {
                groupedEmojis[selectedSection] ?: emptyList()
            }
        }

        Column(
            modifier = modifier
        ) {
            // Search field at top
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search emojis...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                trailingIcon = {
                    if (isSearching) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // LazyRow with common emoji sections (max 10) - only show when not searching
            if (!isSearching) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(groupedEmojis.keys.toList()) { section ->
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                scope.launch {
                                    lazyGridState.scrollToItem(0)
                                }
                                selectedSection = section
                                                          },
                            shape = RoundedCornerShape(8.dp),
                            color = if (selectedSection == section)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surfaceVariant,
                            tonalElevation = if (selectedSection == section) 4.dp else 0.dp
                        ) {
                            Text(
                                text = EmojiParser.getSectionEmoji(section),
                                fontSize = 24.sp,
                                modifier = Modifier.padding(4.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
            }

            LazyVerticalGrid(
                state = lazyGridState,
                columns = GridCells.Adaptive(32.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 16.dp, top = 16.dp)
            ) {
                items(filteredEmojis) { emoji ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .aspectRatio(1f)
                            .clickable { onEmojiSelected(emoji.emoji) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = emoji.emoji,
                            autoSize = TextAutoSize.StepBased(14.sp, 24.sp),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun EmojiSelectorDialog(
    dismissOnSelect: Boolean = true,
    onDismiss: () -> Unit,
    onEmojiSelected: (String) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        DialogCard(
            modifier = Modifier.height(500.dp),
            bottomPadding = 0.dp
        ) {
            EmojiSelection(
                modifier = Modifier.fillMaxWidth().height(480.dp),
                onEmojiSelected = {
                    onEmojiSelected(it)
                    if (dismissOnSelect) {
                        onDismiss()
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmojiSelectorSheet(
    modifier: Modifier = Modifier,
    visible: Boolean,
    onDismiss: () -> Unit,
    onEmojiSelected: (String) -> Unit,
) {
    if (isMobile()) {
        AnimatedVisibility(
            visible,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it })
        ) {
            val listState = rememberScrollState()
            Column(
                modifier = modifier
                    .verticalScroll(state = listState)
                    .padding(16.dp)
            ) {
                EmojiSelection(
                    modifier = Modifier.fillMaxWidth().height(380.dp),
                    onEmojiSelected = {
                        onEmojiSelected(it)
                    }
                )
            }
        }
    } else {
        if (visible) {
            EmojiSelectorDialog(
                onDismiss = onDismiss,
                dismissOnSelect = false,
                onEmojiSelected = {
                    onEmojiSelected(it)
                }
            )
        }
    }
}