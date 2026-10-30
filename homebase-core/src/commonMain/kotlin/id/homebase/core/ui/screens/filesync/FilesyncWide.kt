package id.homebase.core.ui.screens.filesync

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import id.homebase.common.util.formatBytes
import id.homebase.core.ui.navigation.LocalNavAnimatedVisibilityScope
import id.homebase.core.ui.navigation.LocalSharedTransitionScope
import id.homebase.core.util.isExpandedLayout
import id.homebase.core.widget.MinimalSearchTextField
import id.homebase.resources.MR
import id.homebase.resources.filesync_all_files
import id.homebase.resources.filesync_file_count
import id.homebase.resources.filesync_folder_count
import id.homebase.resources.filesync_info_hint
import id.homebase.resources.filesync_more
import id.homebase.resources.filesync_new_folder
import id.homebase.resources.filesync_recent
import id.homebase.resources.filesync_refresh
import id.homebase.resources.filesync_download
import id.homebase.resources.filesync_select
import id.homebase.resources.filesync_select_all
import id.homebase.resources.filesync_selected
import id.homebase.resources.filesync_search
import id.homebase.resources.filesync_selected_size
import id.homebase.resources.filesync_tags_here
import id.homebase.resources.filesync_totals
import id.homebase.resources.filesync_trash
import id.homebase.resources.filesync_upload
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

enum class FilesyncPlace { Files, Recent, Trash }

class FilesyncPlaces(val onFiles: () -> Unit, val onRecent: () -> Unit, val onTrash: () -> Unit)

private val ModifiedWidth = 150.dp
private val SizeWidth = 84.dp
private val KindWidth = 118.dp
private val ActionsWidth = 80.dp
private val RowHeight = 44.dp
private val PaneWidth = 320.dp

// Below these content widths a column gives way to the name: Kind first, then Modified.
private val KindFrom = 680.dp
private val ModifiedFrom = 520.dp

@Composable
internal fun FilesyncShell(
    place: FilesyncPlace,
    places: FilesyncPlaces,
    snackbarHostState: SnackbarHostState,
    title: @Composable RowScope.() -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    sidebarExtra: @Composable ColumnScope.() -> Unit = {},
    pane: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Scaffold(modifier = modifier, snackbarHost = { SnackbarHost(snackbarHostState) }) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            Row(
                modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, content = title)
                actions()
            }
            HorizontalDivider()
            Row(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.fillMaxHeight().sharedSidebar()) {
                    Column(modifier = Modifier.width(216.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp)) {
                        SidebarItem(MR.string.filesync_all_files, Icons.Outlined.Folder, place == FilesyncPlace.Files, places.onFiles)
                        SidebarItem(MR.string.filesync_recent, Icons.Outlined.History, place == FilesyncPlace.Recent, places.onRecent)
                        SidebarItem(MR.string.filesync_trash, Icons.Outlined.Delete, place == FilesyncPlace.Trash, places.onTrash)
                        sidebarExtra()
                    }
                    VerticalDivider()
                }
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) { content() }
                if (pane != null) {
                    VerticalDivider()
                    Box(
                        modifier = Modifier
                            .width(PaneWidth)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .verticalScroll(rememberScrollState())
                            .padding(24.dp),
                    ) { pane() }
                }
            }
        }
    }
}

// Each place draws its own sidebar; across a transition Compose draws only the target's, in place.
@Composable
private fun Modifier.sharedSidebar(): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val visibility = LocalNavAnimatedVisibilityScope.current ?: return this
    return with(shared) { this@sharedSidebar.sharedElement(rememberSharedContentState(SIDEBAR_KEY), visibility) }
}

private const val SIDEBAR_KEY = "filesync-sidebar"

/** The wide shell on wide windows, a plain screen with [compactTopBar] otherwise; for Recent and Trash. */
@Composable
internal fun FilesyncPlaceScaffold(
    place: FilesyncPlace,
    places: FilesyncPlaces,
    snackbarHostState: SnackbarHostState,
    title: String,
    compactTopBar: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    if (isExpandedLayout()) {
        FilesyncShell(
            place = place,
            places = places,
            snackbarHostState = snackbarHostState,
            title = { Text(title, style = MaterialTheme.typography.titleLarge) },
            actions = actions,
            content = content,
        )
    } else {
        Scaffold(topBar = compactTopBar, snackbarHost = { SnackbarHost(snackbarHostState) }) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding)) { content() }
        }
    }
}

@Composable
private fun SidebarItem(label: StringResource, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    NavigationDrawerItem(
        label = { Text(stringResource(label)) },
        icon = { Icon(icon, contentDescription = null) },
        selected = selected,
        // No "already here" guard: on a pop the hidden outgoing copy takes clicks, and its selection is stale.
        onClick = onClick,
        modifier = Modifier.height(44.dp),
    )
}

@Composable
internal fun FilesyncWideBrowser(scope: FilesyncBrowserScope) {
    val uiState = scope.uiState
    val searchFocus = remember { FocusRequester() }
    // On the content rather than the list, which leaves composition while a folder loads or is empty.
    val keysFocus = remember { FocusRequester() }
    val listState = remember(uiState.path.last().nodeId) { LazyListState() }
    // Rows are keyed, so without this a new order keeps whichever row was on top and hides the new first ones.
    LaunchedEffect(uiState.sort, uiState.query, uiState.tagFilter) { listState.requestScrollToItem(0) }
    var keyMoves by remember { mutableIntStateOf(0) }
    LaunchedEffect(keyMoves) {
        if (keyMoves > 0) listState.bringIntoView(uiState.visibleIds.indexOf(uiState.selection.focus))
    }
    val actions = scope.actions
    FilesyncShell(
        place = FilesyncPlace.Files,
        places = scope.places,
        snackbarHostState = scope.snackbarHostState,
        modifier = Modifier.onPreviewKeyEvent { event ->
            val find = event.type == KeyEventType.KeyDown && event.key == Key.F && (event.isCtrlPressed || event.isMetaPressed)
            if (find) searchFocus.requestFocus()
            find
        },
        title = { Breadcrumbs(uiState.path, onCrumb = { scope.onAction(FilesyncUiAction.NavigateToCrumb(it)) }) },
        actions = {
            MinimalSearchTextField(
                // Esc clears the search; unhandled, desktop and web turn it into Back and leave the folder.
                modifier = Modifier.width(260.dp).focusRequester(searchFocus).onPreviewKeyEvent { event ->
                    val escape = event.type == KeyEventType.KeyDown && event.key == Key.Escape
                    if (escape) {
                        scope.search.clearText()
                        keysFocus.requestFocus()
                    }
                    escape
                },
                textFieldState = scope.search,
                placeHolderText = stringResource(MR.string.filesync_search),
            )
            if (!scope.currentRemoved) {
                OutlinedButton(onClick = scope.onNewFolder) {
                    Icon(Icons.Outlined.CreateNewFolder, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(MR.string.filesync_new_folder))
                }
                Button(onClick = scope.onUpload) {
                    Icon(Icons.Outlined.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(MR.string.filesync_upload))
                }
            }
            IconButton(onClick = { scope.onAction(FilesyncUiAction.Refresh) }) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(MR.string.filesync_refresh))
            }
            OverflowMenu(
                showDeleted = uiState.showDeleted,
                onToggleShowDeleted = { scope.onAction(FilesyncUiAction.ToggleShowDeleted) },
                places = null,
            )
        },
        sidebarExtra = { TagFilters(uiState, scope.onAction) },
        // Always there, empty until a row is selected: a pane that opened on the first press of a
        // double-click would move the row out from under the second.
        pane = {
            val selected = uiState.selected
            if (selected.size == 1) {
                FilesyncInfoContent(selected.single(), crumbNames(uiState.path), scope.onAction)
            } else if (selected.isNotEmpty()) {
                FilesyncSelectionContent(uiState, scope.onAction)
            } else {
                Text(
                    text = stringResource(MR.string.filesync_info_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(keysFocus)
                // After the focused child, so Enter on a focused button presses it.
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val one = uiState.selectedOne
                    when {
                        event.key == Key.DirectionDown || event.key == Key.DirectionUp -> {
                            actions.onAction(FilesyncUiAction.MoveSelection(if (event.key == Key.DirectionDown) 1 else -1, extend = event.isShiftPressed))
                            keyMoves++
                        }
                        event.key == Key.A && (event.isCtrlPressed || event.isMetaPressed) -> actions.onAction(FilesyncUiAction.SelectAll)
                        // Without a single selection Enter belongs to whichever button has focus.
                        (event.key == Key.Enter || event.key == Key.NumPadEnter) && one != null -> actions.click(one)
                        event.key == Key.Backspace -> actions.onAction(FilesyncUiAction.NavigateUp)
                        event.key == Key.Delete && FilesyncBulkAction.Delete in uiState.bulkActions -> actions.onAction(FilesyncUiAction.Bulk(FilesyncBulkAction.Delete))
                        event.key == Key.Escape && uiState.selecting -> actions.onAction(FilesyncUiAction.ClearSelection)
                        event.key == Key.F5 -> actions.onAction(FilesyncUiAction.Refresh)
                        else -> return@onKeyEvent false
                    }
                    true
                }
                .focusable()
                .browserDropTarget(scope),
        ) {
            val columns = TableColumns(modified = maxWidth >= ModifiedFrom, kind = maxWidth >= KindFrom)
            Column(modifier = Modifier.fillMaxSize()) {
                if (scope.uploads.isNotEmpty()) {
                    UploadStrip(
                        scope.uploads,
                        scope.onAction,
                        Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 12.dp),
                    )
                }
                FilesyncPrivateDriveBanners(uiState.privateDrive, scope.signIn, scope.actions)
                if (scope.currentRemoved) DeletedFolderBanner(scope)
                uiState.transfer?.takeIf { it.isZip }?.let { FilesyncArchiveBar(it, onCancel = { scope.onAction(FilesyncUiAction.CancelTransfer) }) }
                TableHeader(uiState.sort, uiState.selected.size, uiState.visible.size, columns, scope.onAction)
                Box(modifier = Modifier.weight(1f)) {
                    BrowserListBody(scope) {
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)) {
                            val several = uiState.selected.size > 1
                            items(uiState.visible, key = { it.nodeId.toString() }) { node ->
                                val selected = node.nodeId in uiState.selection.ids
                                TableRow(
                                    node = node,
                                    selected = selected,
                                    progress = uiState.transfer.progressOf(node.nodeId),
                                    columns = columns,
                                    actions = actions,
                                    selecting = uiState.selecting,
                                    inGroup = selected && several,
                                    onSelect = { mode ->
                                        keysFocus.requestFocus()
                                        actions.onAction(FilesyncUiAction.Select(node.nodeId, mode))
                                    },
                                )
                            }
                        }
                    }
                }
                HorizontalDivider()
                Footer(uiState)
            }
        }
    }
}

@Immutable
internal data class TableColumns(val modified: Boolean, val kind: Boolean)

// Scrolls only as far as it takes to show the row whole, so a selection made with the mouse never moves the list.
// Rows are one height, so a row just off screen is placed from its neighbours' offsets.
private suspend fun LazyListState.bringIntoView(index: Int) {
    val shown = layoutInfo.visibleItemsInfo
    if (index < 0 || shown.isEmpty()) return
    val first = shown.first()
    val start = shown.firstOrNull { it.index == index }?.offset ?: (first.offset + (index - first.index) * first.size)
    val end = start + first.size
    when {
        start < layoutInfo.viewportStartOffset -> scrollBy((start - layoutInfo.viewportStartOffset).toFloat())
        end > layoutInfo.viewportEndOffset -> scrollBy((end - layoutInfo.viewportEndOffset).toFloat())
    }
}

@Composable
private fun ColumnScope.TagFilters(uiState: FilesyncUiState, onAction: (FilesyncUiAction) -> Unit) {
    if (uiState.folderTags.isEmpty()) return
    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp))
    Text(
        text = stringResource(MR.string.filesync_tags_here),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, bottom = 6.dp),
    )
    uiState.folderTags.forEach { tag ->
        NavigationDrawerItem(
            label = { Text(tag.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            icon = { TagDot(tag, size = 10.dp) },
            selected = uiState.tagFilter == tag.name,
            onClick = { onAction(FilesyncUiAction.FilterTag(tag.name)) },
            modifier = Modifier.height(36.dp),
        )
    }
}

@Composable
private fun TableHeader(sort: FilesyncSort, selectedCount: Int, visibleCount: Int, columns: TableColumns, onAction: (FilesyncUiAction) -> Unit) {
    val onSort: (FilesyncSortKey) -> Unit = { onAction(FilesyncUiAction.SortBy(it)) }
    Row(
        modifier = Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(42.dp)) {
            if (visibleCount > 0) {
                val all = selectedCount == visibleCount
                SelectBox(
                    state = when {
                        all -> ToggleableState.On
                        selectedCount > 0 -> ToggleableState.Indeterminate
                        else -> ToggleableState.Off
                    },
                    description = stringResource(MR.string.filesync_select_all),
                    onClick = { onAction(if (all) FilesyncUiAction.ClearSelection else FilesyncUiAction.SelectAll) },
                )
            }
        }
        HeaderCell(FilesyncSortKey.Name, sort, onSort, Modifier.weight(1f))
        if (columns.modified) HeaderCell(FilesyncSortKey.Modified, sort, onSort, Modifier.width(ModifiedWidth))
        HeaderCell(FilesyncSortKey.Size, sort, onSort, Modifier.width(SizeWidth), Arrangement.End)
        if (columns.kind) HeaderCell(FilesyncSortKey.Kind, sort, onSort, Modifier.width(KindWidth).padding(start = 20.dp))
        Spacer(Modifier.width(ActionsWidth))
    }
    HorizontalDivider()
}

@Composable
private fun HeaderCell(
    key: FilesyncSortKey,
    sort: FilesyncSort,
    onSort: (FilesyncSortKey) -> Unit,
    modifier: Modifier,
    arrangement: Arrangement.Horizontal = Arrangement.Start,
) {
    val active = sort.key == key
    Row(modifier = modifier, horizontalArrangement = arrangement) {
        Row(
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onSort(key) }.padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(key.label),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (active) SortArrow(sort.ascending, Modifier.size(14.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TableRow(
    node: FilesyncNode,
    selected: Boolean,
    progress: Float?,
    columns: TableColumns,
    actions: FilesyncBrowserActions,
    onSelect: (FilesyncSelectMode) -> Unit,
    selecting: Boolean = false,
    // One of several selected: its right-click menu is the selection's.
    inGroup: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var menuAt by remember { mutableStateOf<DpOffset?>(null) }
    var moreOpen by remember { mutableStateOf(false) }
    // The pointer loop below outlives recompositions; it must act on the node as it is now.
    val current by rememberUpdatedState(node)
    val select by rememberUpdatedState(onSelect)
    val isSelected by rememberUpdatedState(selected)
    val colors = MaterialTheme.colorScheme
    val background = when {
        selected -> colors.secondaryContainer
        hovered -> colors.surfaceContainerLow
        else -> Color.Transparent
    }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(RowHeight)
                .clip(RoundedCornerShape(10.dp))
                .background(background)
                .hoverable(interaction)
                // Touch, keyboard and screen readers; a mouse press never reaches it, the handler below consumes it.
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onLongClick = { onSelect(FilesyncSelectMode.Toggle) },
                    // Once something is selected, a tap picks rows the way it does on a phone.
                    onClick = { if (selecting) onSelect(FilesyncSelectMode.Toggle) else actions.click(node) },
                )
                // A mouse selects on press and opens on a double-click, the way desktop file managers do; a finger opens
                // on a tap and selects on a long press, and never on press, or every scroll would select its row.
                // Inside the click handler, so it sees a press after the row's buttons and before the handler consumes it.
                .pointerInput(node.nodeId, rtl) {
                    var lastPress: Long? = null
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type != PointerEventType.Press) continue
                            val change = event.changes.first()
                            // Pressed on the row's own Save or More button.
                            if (change.isConsumed || change.type != PointerType.Mouse) continue
                            event.changes.forEach { it.consume() }
                            val keys = event.keyboardModifiers
                            val add = keys.isCtrlPressed || keys.isMetaPressed
                            if (event.buttons.isSecondaryPressed) {
                                // A right-click inside the selection is for all of it.
                                if (!isSelected) select(FilesyncSelectMode.Only)
                                val start = if (rtl) size.width - change.position.x else change.position.x
                                menuAt = DpOffset(start.toDp(), change.position.y.toDp() - RowHeight)
                            } else if (add || keys.isShiftPressed) {
                                lastPress = null
                                select(FilesyncSelectMode.of(add, keys.isShiftPressed))
                            } else if (lastPress?.let { change.uptimeMillis - it < viewConfiguration.doubleTapTimeoutMillis } == true) {
                                lastPress = null
                                actions.click(current)
                            } else {
                                lastPress = change.uptimeMillis
                                select(FilesyncSelectMode.Only)
                            }
                        }
                    }
                }
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val textColor = if (node.removedMs != null) colors.onSurfaceVariant else colors.onSurface
            // The checkbox takes the icon's place, as in OneDrive: on hover, and on every row once anything is selected.
            if (hovered || selecting) {
                Box(modifier = Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                    SelectBox(
                        state = ToggleableState(selected),
                        description = stringResource(MR.string.filesync_select, node.name),
                        onClick = { onSelect(FilesyncSelectMode.Toggle) },
                    )
                }
            } else {
                FileTypeTile(node, size = 30.dp)
            }
            Spacer(Modifier.width(12.dp))
            NodeName(
                node = node,
                ringColor = if (background == Color.Transparent) colors.surface else background,
                fontWeight = if (node.isFolder) FontWeight.SemiBold else FontWeight.Medium,
                modifier = Modifier.weight(1f).padding(end = 12.dp),
            )
            if (columns.modified) Cell(node.modifiedMs?.let { formatDayTime(it) } ?: DASH, Modifier.width(ModifiedWidth), textColor)
            Cell(node.sizeBytes?.let { formatBytes(it) } ?: DASH, Modifier.width(SizeWidth), textColor, TextAlign.End)
            if (columns.kind) Cell(kindLabel(node), Modifier.width(KindWidth).padding(start = 20.dp), textColor)
            Row(modifier = Modifier.width(ActionsWidth), horizontalArrangement = Arrangement.End) {
                when {
                    progress != null -> CancelTransferButton(progress) { actions.onAction(FilesyncUiAction.CancelTransfer) }
                    hovered || selected || moreOpen -> RowActions(node, actions.onAction, moreOpen, canDelete = !actions.current().inDeletedFolder) { moreOpen = it }
                }
            }
        }
        if (inGroup) {
            FilesyncSelectionMenu(
                bulkActions = actions.current().bulkActions,
                expanded = menuAt != null,
                onDismiss = { menuAt = null },
                onAction = actions.onAction,
                offset = menuAt ?: DpOffset.Zero,
            )
        } else {
            FilesyncNodeMenu(
                node = node,
                expanded = menuAt != null,
                onDismiss = { menuAt = null },
                onAction = actions.onAction,
                onInfo = null,
                offset = menuAt ?: DpOffset.Zero,
                canDelete = !actions.current().inDeletedFolder,
            )
        }
    }
}

// The menu's open state lives in the row, which keeps these actions shown while the pointer is over the menu.
@Composable
private fun RowActions(node: FilesyncNode, onAction: (FilesyncUiAction) -> Unit, expanded: Boolean, canDelete: Boolean, onExpanded: (Boolean) -> Unit) {
    if (!node.isFolder && node.removedMs == null) {
        IconButton(onClick = { onAction(FilesyncUiAction.SaveClicked(node)) }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Outlined.Download, contentDescription = stringResource(MR.string.filesync_download), modifier = Modifier.size(20.dp))
        }
    }
    Box {
        IconButton(onClick = { onExpanded(true) }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(MR.string.filesync_more), modifier = Modifier.size(20.dp))
        }
        FilesyncNodeMenu(node = node, expanded = expanded, onDismiss = { onExpanded(false) }, onAction = onAction, onInfo = null, canDelete = canDelete)
    }
}

@Composable
private fun Cell(text: String, modifier: Modifier, color: Color, align: TextAlign = TextAlign.Start) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        textAlign = align,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
private fun Footer(uiState: FilesyncUiState) {
    val totals = uiState.totals
    Row(
        modifier = Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(
                MR.string.filesync_totals,
                pluralStringResource(MR.plurals.filesync_folder_count, totals.folders, totals.folders),
                pluralStringResource(MR.plurals.filesync_file_count, totals.files, totals.files),
                formatBytes(totals.bytes),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        val selected = uiState.selected
        if (selected.isNotEmpty()) {
            val count = pluralStringResource(MR.plurals.filesync_selected, selected.size, selected.size)
            val bytes = uiState.selectedTotals.bytes
            Text(
                text = if (bytes > 0) stringResource(MR.string.filesync_selected_size, count, formatBytes(bytes)) else count,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val DASH = "—"
