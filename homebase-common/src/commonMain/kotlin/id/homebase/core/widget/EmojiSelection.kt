package id.homebase.core.widget

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import id.homebase.core.emoji.EmojiData
import id.homebase.core.emoji.EmojiParser
import id.homebase.core.emoji.EmojiSkin
import id.homebase.resources.MR
import id.homebase.resources.backspace
import id.homebase.resources.emoji_none_found
import id.homebase.resources.emoji_search_placeholder
import id.homebase.resources.error
import id.homebase.resources.search
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
fun EmojiSelection(
    modifier: Modifier = Modifier,
    messageInputMode: Boolean = false,
    onBackSpace: () -> Unit = {},
    onEmojiSelected: (String) -> Unit,
) {
    var emojiData by remember { mutableStateOf<EmojiData?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var isSearchActive by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }


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
                Text(stringResource(MR.string.error) + ": $error", modifier = Modifier.align(Alignment.Center))
            }
        }
    }

    emojiData?.let { emojiData ->
        val groupedEmojis = remember { EmojiParser.groupEmojis(emojiData.emojis, emojiData.groups) }
        val searchQuery = rememberTextFieldState()
        var selectedSection by remember { mutableStateOf(groupedEmojis.keys.first()) }
        val isSearching = searchQuery.text.isNotEmpty()
        val lazyGridState = rememberLazyGridState()
        val scope = rememberCoroutineScope()

        val filteredEmojis = remember(searchQuery.text, selectedSection) {
            if (isSearching) {
                // Search across all emojis
                EmojiParser.filterEmojis(searchQuery.text.toString(), emojiData.emojis)
            } else {
                groupedEmojis[selectedSection] ?: emptyList()
            }
        }

        Column(
            modifier = modifier
        ) {
            if (!messageInputMode) {
                MinimalSearchTextField(
                    textFieldState = searchQuery,
                    modifier = Modifier.fillMaxWidth(),
                    placeHolderText = stringResource(MR.string.emoji_search_placeholder)
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            // LazyRow with common emoji sections (max 10) - only show when not searching

            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (messageInputMode) {
                    if (!isSearchActive) {
                        IconButton(onClick = {
                            isSearchActive = !isSearchActive
                            if (!isSearchActive) {
                                searchQuery.clearText()
                            }
                        }) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = stringResource(MR.string.search)
                            )
                        }
                        VerticalDivider(modifier = Modifier.height(24.dp).padding(end = 8.dp))
                    } else {
                        LaunchedEffect(Unit) {
                            focusRequester.requestFocus()
                        }
                        MinimalSearchTextField(
                            textFieldState = searchQuery,
                            modifier = Modifier.weight(1f).focusRequester(focusRequester),
                            placeHolderText = stringResource(MR.string.emoji_search_placeholder),
                            showBackButton = true,
                            onBackButtonClick = {
                                isSearchActive = false
                                searchQuery.clearText()
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                }
                if (!isSearchActive && !isSearching) {
                    LazyRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
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
                                    Color.Unspecified,
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
                }
                if (messageInputMode) {
                    VerticalDivider(modifier = Modifier.height(24.dp).padding(start = 8.dp))
                    IconButton(onClick = onBackSpace) {
                        Icon(
                            Icons.AutoMirrored.Filled.Backspace,
                            contentDescription = stringResource(MR.string.backspace)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            if (filteredEmojis.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)

                ) {
                    Text(stringResource(MR.string.emoji_none_found), modifier = Modifier.align(Alignment.Center))
                }
            }
            LazyVerticalGrid(
                state = lazyGridState,
                columns = GridCells.Adaptive(32.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 16.dp, top = 8.dp)
            ) {

                items(filteredEmojis) { emoji ->
                    var skins by remember { mutableStateOf<List<EmojiSkin>?>(null) }
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clickable {
                                if (emoji.skins != null) {
                                    skins = emoji.skins
                                } else {
                                    onEmojiSelected(emoji.emoji)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = emoji.emoji,
                            autoSize = TextAutoSize.StepBased(14.sp, 24.sp),
                            textAlign = TextAlign.Center
                        )
                        if (emoji.skins != null) {
                            Canvas(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .size(8.dp)
                            ) {
                                val trianglePath = Path().apply {
                                    moveTo(size.width, 0f)
                                    lineTo(size.width, size.height)
                                    lineTo(0f, size.height)
                                    close()
                                }
                                drawPath(
                                    path = trianglePath,
                                    color = Color.Gray
                                )
                            }
                        }
                        skins?.let { skinEmojis ->
                            Popup(
                                onDismissRequest = { skins = null }
                            ) {
                                Surface(
                                    modifier = Modifier
                                        .wrapContentWidth()
                                        .padding(top = 4.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shadowElevation = 4.dp,
                                    tonalElevation = 2.dp
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .horizontalScroll(rememberScrollState())
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        IconButton(
                                            onClick = {
                                                skins = null
                                                onEmojiSelected(emoji.emoji)
                                            },
                                            modifier = Modifier.size(40.dp)
                                        ) {
                                            Text(
                                                text = emoji.emoji,
                                                fontSize = 20.sp
                                            )
                                        }
                                        skinEmojis.forEach { emoji ->
                                            IconButton(
                                                onClick = {
                                                    skins = null
                                                    onEmojiSelected(emoji.emoji)
                                                },
                                                modifier = Modifier.size(40.dp)
                                            ) {
                                                Text(
                                                    text = emoji.emoji,
                                                    fontSize = 20.sp
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
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
    onBackSpace: () -> Unit,
    onEmojiSelected: (String) -> Unit,
) {
    AnimatedVisibility(
        visible,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it })
    ) {
        val listState = rememberScrollState()
        Column(
            modifier = modifier
                .verticalScroll(state = listState)
                .padding(horizontal = 16.dp)
        ) {
            EmojiSelection(
                modifier = Modifier.fillMaxWidth().height(380.dp),
                messageInputMode = true,
                onBackSpace = onBackSpace,
                onEmojiSelected = {
                    onEmojiSelected(it)
                }
            )
        }
    }
}