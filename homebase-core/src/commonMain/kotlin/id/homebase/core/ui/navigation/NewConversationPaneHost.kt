@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import id.homebase.chat.createconversation.CreateConversationScreen
import id.homebase.chat.selectmembers.SelectMembersScreen
import org.koin.compose.viewmodel.koinViewModel
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private enum class NewConversationStep { PickContact, SelectMembers }

/**
 * The new-conversation flow rendered inside the conversation-list pane on an expanded window.
 *
 * Only the two steps whose ViewModels take no route arguments live here; naming the group reads
 * its member list from [id.homebase.core.ui.navigation.Route.CreateConversationGroup], so it stays
 * a destination and [onCreateGroup] hands it over.
 */
@Composable
internal fun NewConversationPaneHost(
    onDismiss: () -> Unit,
    onShowConversation: (Uuid) -> Unit,
    onCreateGroup: (contactIds: List<String>) -> Unit,
    onAddContact: () -> Unit,
) {
    // A destination clears its ViewModelStore when it pops; this pane has no destination of its
    // own, so without a scoped store the picker would reopen holding the last run's selection.
    val storeOwner = remember { NewConversationPaneStoreOwner() }
    DisposableEffect(storeOwner) { onDispose { storeOwner.viewModelStore.clear() } }

    var step by remember { mutableStateOf(NewConversationStep.PickContact) }

    CompositionLocalProvider(LocalViewModelStoreOwner provides storeOwner) {
        when (step) {
            NewConversationStep.PickContact -> CreateConversationScreen(
                viewModel = koinViewModel(),
                onNavigateBack = onDismiss,
                onShowConversation = onShowConversation,
                onShowCreateGroup = { step = NewConversationStep.SelectMembers },
                onAddContact = onAddContact,
            )

            NewConversationStep.SelectMembers -> SelectMembersScreen(
                viewModel = koinViewModel(),
                onNavigateBack = { step = NewConversationStep.PickContact },
                onMembersSelected = onCreateGroup,
            )
        }
    }
}

private class NewConversationPaneStoreOwner : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}
