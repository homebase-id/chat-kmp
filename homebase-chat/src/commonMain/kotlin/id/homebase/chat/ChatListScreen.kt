package id.homebase.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.navigation.BackNavigationBehavior
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.homebase.core.ui.theme.HomebaseTheme
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Composable
fun ChatListScreen(
    viewModel: ChatListViewModel,
    onNavigateToMessages: (conversationId: String) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.uiEvent.collect { event ->
            when (event) {
                is ChatListUiEvent.NavigateToMessages -> onNavigateToMessages(event.conversationId)
                ChatListUiEvent.NavigateBack -> onNavigateBack()
            }
        }
    }

    ChatListUi(
        uiState = uiState,
        onAction = viewModel::onAction
    )
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class, ExperimentalComposeUiApi::class)
@Composable
fun ChatListUi(
    uiState: ChatListUiState,
    onAction: (ChatListUiAction) -> Unit,
) {
    Scaffold { innerPadding ->
        val scaffoldNavigator = rememberListDetailPaneScaffoldNavigator<String>()
        //val customScaffoldDirective = customPaneScaffoldDirective(currentWindowAdaptiveInfo())
        val scope = rememberCoroutineScope()
        val backNavigationBehavior = BackNavigationBehavior.PopUntilScaffoldValueChange

        BackHandler(scaffoldNavigator.canNavigateBack(BackNavigationBehavior.PopUntilContentChange)) {
            scope.launch {
                scaffoldNavigator.navigateBack(BackNavigationBehavior.PopUntilContentChange)
            }
        }

        ListDetailPaneScaffold(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
            directive = scaffoldNavigator.scaffoldDirective,
            scaffoldState = scaffoldNavigator.scaffoldState,
            listPane = {
                AnimatedPane {
                    MyList(
                        items = uiState.conversations,
                        onItemClick = { item ->
                            // Navigate to the detail pane with the passed item
                            scope.launch {
                                scaffoldNavigator.navigateTo(
                                    ListDetailPaneScaffoldRole.Detail,
                                    item.id.toString()
                                )
                            }
                        },
                    )
                }
            },
            detailPane = {
                AnimatedPane {
                    // Show the detail pane content if selected item is available
                    scaffoldNavigator.currentDestination?.contentKey?.let {
                        Column {
                            // Allow users to dismiss the detail pane. Use back navigation to
                            // hide an expanded detail pane.
                            if (scaffoldNavigator.scaffoldValue[ListDetailPaneScaffoldRole.Detail] == PaneAdaptedValue.Expanded) {
                                // Material design principles promote the usage of a right-aligned
                                // close (X) button.
                                IconButton(
                                    modifier = Modifier.align(Alignment.End).padding(16.dp),
                                    onClick = {
                                        scope.launch {
                                            scaffoldNavigator.navigateBack(backNavigationBehavior)
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Close")
                                }
                            }
                            MyDetails(
                                item = uiState.conversations[it.toInt()],
                                showExtra = {
                                    scope.launch {
                                        scaffoldNavigator.navigateTo(
                                            ListDetailPaneScaffoldRole.Extra,
                                            it
                                        )
                                    }
                                }
                            )
                        }
                    } ?: Box(
                        contentAlignment = Alignment.Center
                    ) { Text("Empty") }
                }
            },
            extraPane = {
                AnimatedPane {
                    scaffoldNavigator.currentDestination?.contentKey?.let {
                        Column {
                            Text("Extra pane for $it")
                            Button(onClick = {
                                scope.launch {
                                    scaffoldNavigator.navigateBack(backNavigationBehavior)
                                }
                            }) {
                                Text("Go back")
                            }
                        }
                    }
                }
            }
        )
    }
}

//fun customPaneScaffoldDirective(currentWindowAdaptiveInfo: WindowAdaptiveInfo): PaneScaffoldDirective {
//    val horizontalPartitions = when {
//        currentWindowAdaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(
//            WIDTH_DP_EXPANDED_LOWER_BOUND
//        ) -> 3
//
//        currentWindowAdaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(
//            WIDTH_DP_MEDIUM_LOWER_BOUND
//        ) -> 2
//
//        else -> 1
//    }
//
//    return PaneScaffoldDirective(
//        maxHorizontalPartitions = horizontalPartitions,
//        horizontalPartitionSpacerSize = 16.dp,
//        maxVerticalPartitions = 1,
//        verticalPartitionSpacerSize = 8.dp,
//        defaultPanePreferredWidth = 320.dp,
//        excludedBounds = emptyList()
//    )
//}

@Composable
fun MyList(
    items: ImmutableList<String>,
    onItemClick: (MyItem) -> Unit,
) {
    Card {
        LazyColumn {
            items.forEachIndexed { id, string ->
                item {
                    ListItem(
                        modifier = Modifier
                            .background(Color.Magenta)
                            .clickable {
                                onItemClick(MyItem(id))
                            },
                        headlineContent = {
                            Text(
                                text = string,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun MyDetails(item: String, showExtra: () -> Unit) {
    Card {
        Column(
            Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text(
                text = "Details page for $item",
                fontSize = 24.sp,
            )
            Spacer(Modifier.size(16.dp))
            Text(
                text = "TODO: Add great details here"
            )
            Button(onClick = showExtra) {
                Text("Show extra")
            }
        }
    }
}

@Serializable
data class MyItem(val id: Int)

@Preview
@Composable
fun ChatListUiPreview() {
    HomebaseTheme {
        ChatListUi(
            uiState = ChatListUiState(),
            onAction = {}
        )
    }
}
