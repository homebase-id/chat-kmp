package id.homebase.core.ui.screens.contactbook

import id.homebase.chat.contactcard.ContactCardDescriptor
import id.homebase.core.ui.screens.contactbook.model.ContactBookEntry
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import org.jetbrains.compose.resources.StringResource
import kotlin.uuid.Uuid

data class ShareContactPickerUiState(
    val candidates: PersistentList<ShareContactCandidate> = persistentListOf(),
    val selectedId: Uuid? = null,
    val isSending: Boolean = false,
    /** Non-null while the review step is up; it replaces the picker list rather than routing. */
    val review: ContactCardReview? = null,
)

/**
 * A contact book row offered in the send-a-contact picker. [descriptor] is null when the entry
 * carries nothing a contact card can hold — the row is shown but disabled, so a contact that
 * can't be shared reads as "needs a phone or email" rather than silently missing from the list.
 */
data class ShareContactCandidate(
    val entry: ContactBookEntry,
    val descriptor: ContactCardDescriptor?,
) {
    val shareable: Boolean get() = descriptor != null
}

sealed interface ShareContactPickerUiAction {
    data class ContactClicked(val entry: ContactBookEntry) : ShareContactPickerUiAction
    data object SendClicked : ShareContactPickerUiAction
    data object BackClicked : ShareContactPickerUiAction
    data class ReviewNameChanged(val name: String) : ShareContactPickerUiAction
    data class ReviewFieldToggled(val index: Int) : ShareContactPickerUiAction
    data object ReviewPhotoToggled : ShareContactPickerUiAction
}

sealed interface ShareContactPickerUiEvent {
    data object Back : ShareContactPickerUiEvent

    /** The contact card went out — the screen pops back to the conversation. */
    data object MessageSent : ShareContactPickerUiEvent

    data class ShowError(val res: StringResource) : ShareContactPickerUiEvent
}

/**
 * Builds the picker list: [query]-filtered, name-sorted, each row carrying the descriptor it
 * would send (null when there is nothing to send).
 */
fun shareContactCandidates(
    entries: List<ContactBookEntry>,
    query: String,
): PersistentList<ShareContactCandidate> = entries
    .filter { it.matches(query) }
    .sortedBy { it.sortKey }
    .map { ShareContactCandidate(entry = it, descriptor = ContactCardImport.toDescriptor(it)) }
    .toPersistentList()
