@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.contactbook.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContactPage
import androidx.compose.material.icons.outlined.HowToReg
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import co.touchlab.kermit.Logger
import id.homebase.api.client.contacts.ContactRepository
import id.homebase.chat.contactcard.ContactCardDescriptor
import id.homebase.core.contactbook.ContactBookPreferences
import id.homebase.core.contactbook.ContactOverrideStore
import id.homebase.core.ui.screens.contactbook.ContactCardImport
import id.homebase.core.ui.screens.contactbook.ContactSaveResult
import id.homebase.core.ui.screens.contactbook.model.ContactBookEntry
import id.homebase.core.ui.screens.contactbook.saveNewContact
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.chat_contact_card_add_anyway
import id.homebase.resources.chat_contact_card_checking
import id.homebase.resources.chat_contact_card_exists_body
import id.homebase.resources.chat_contact_card_exists_open
import id.homebase.resources.chat_contact_card_exists_title
import id.homebase.resources.chat_contact_card_partial_additions
import id.homebase.resources.chat_contact_card_partial_photo
import id.homebase.resources.chat_contact_card_retry
import id.homebase.resources.chat_contact_card_save_failed
import id.homebase.resources.chat_contact_card_save_failed_title
import id.homebase.resources.chat_contact_card_saved_body
import id.homebase.resources.chat_contact_card_saved_open
import id.homebase.resources.chat_contact_card_saved_title
import id.homebase.resources.chat_contact_card_title
import id.homebase.resources.contactbook_error_forbidden
import id.homebase.resources.ok
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

internal sealed interface SaveStage {
    data object Checking : SaveStage
    data class Duplicate(val match: ContactBookEntry) : SaveStage
    data object Editing : SaveStage
    data object Saving : SaveStage
    data object Forbidden : SaveStage
    data object Failed : SaveStage
    data class Saved(
        val uniqueId: Uuid?,
        val photoFailed: Boolean,
        val additionsFailed: Boolean,
    ) : SaveStage
}

// A null result is a throw out of the write, indistinguishable from a transport failure: both land
// on the retryable Failed, never on a stage that says "saved".
internal fun saveStageFor(result: ContactSaveResult?): SaveStage = when (result) {
    is ContactSaveResult.Success -> SaveStage.Saved(
        uniqueId = result.uniqueId,
        photoFailed = result.photoFailed,
        additionsFailed = result.additionsFailed,
    )
    ContactSaveResult.Forbidden -> SaveStage.Forbidden
    else -> SaveStage.Failed
}

/**
 * Receiving half of the contact card's save action: hosts the same seeded [ContactEditSheet] the
 * share-a-vCard flow uses, behind a duplicate check. Lives in `:homebase-core` because
 * [ContactEditSheet] and [ContactRepository] do.
 */
@Composable
fun ContactCardSaveHost(
    descriptor: ContactCardDescriptor?,
    onDismiss: () -> Unit,
    onOpenContact: (uniqueId: Uuid, odinId: String?) -> Unit,
) {
    if (descriptor == null) return

    val repo: ContactRepository = koinInject()
    val store: ContactOverrideStore = koinInject()
    val preferences: ContactBookPreferences = koinInject()
    // A composition-scoped launch would cancel a half-written contact when the host goes away.
    val appScope: CoroutineScope = koinInject()
    var stage by remember(descriptor) { mutableStateOf<SaveStage>(SaveStage.Checking) }
    val cardName = descriptor.summaryLine()
        .ifBlank { stringResource(MR.string.chat_contact_card_title) }

    LaunchedEffect(descriptor) {
        val match = try {
            ContactCardImport.resolveExisting(
                descriptor,
                loadContacts = {
                    repo.ensureLoaded()
                    repo.contacts.value
                },
                // Time-boxed: one wedged payload fetch must not pin the user behind this modal.
                loadOverrides = { contacts ->
                    withTimeoutOrNull(HYDRATE_TIMEOUT_MS) { store.hydrateAll(contacts) }
                    store.overrides.value
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Logger.w(tag = TAG, throwable = e) { "contact load failed; skipping dupe check" }
            null
        }
        stage = if (match != null) SaveStage.Duplicate(match) else SaveStage.Editing
    }

    // Mounted across Saving and both failures so a retry resumes on the user's own edits.
    val current = stage
    if (current is SaveStage.Editing || current is SaveStage.Saving ||
        current is SaveStage.Forbidden || current is SaveStage.Failed
    ) {
        ContactEditSheet(
            editing = null,
            seed = remember(descriptor) { ContactCardImport.toDraft(descriptor) },
            seedAdditionalPhones = remember(descriptor) { ContactCardImport.extraPhones(descriptor) },
            seedAdditionalEmails = remember(descriptor) { ContactCardImport.extraEmails(descriptor) },
            saving = current is SaveStage.Saving,
            onSave = { draft, extraPhones, extraEmails, photo ->
                stage = SaveStage.Saving
                appScope.launch {
                    val result = try {
                        saveNewContact(store, repo, draft, extraPhones, extraEmails, photo)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        Logger.e(tag = TAG, throwable = e) { "contact save threw" }
                        null
                    }
                    val next = saveStageFor(result)
                    // A contact saved from chat is a contact book with a contact in it; without
                    // this AppNavHost still shows the first-run intro over it.
                    if (next is SaveStage.Saved) {
                        runCatching { preferences.setOnboardingComplete(true) }
                    }
                    stage = next
                }
            },
            onDismiss = onDismiss,
        )
    }

    when (current) {
        SaveStage.Editing, SaveStage.Saving -> Unit

        // Delayed: the warm path resolves within a frame, so showing it at once flashes a scrim.
        SaveStage.Checking -> {
            var visible by remember(descriptor) { mutableStateOf(false) }
            LaunchedEffect(descriptor) {
                delay(CHECKING_DIALOG_DELAY_MS)
                visible = true
            }
            val checkingLabel = stringResource(MR.string.chat_contact_card_checking)
            if (visible) AlertDialog(
                onDismissRequest = onDismiss,
                icon = { Icon(Icons.Outlined.ContactPage, contentDescription = null) },
                title = { Text(checkingLabel) },
                text = {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                liveRegion = LiveRegionMode.Polite
                                contentDescription = checkingLabel
                            },
                    )
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(MR.string.cancel)) }
                },
            )
        }

        is SaveStage.Duplicate -> DuplicateContactDialog(
            match = current.match,
            onAddAnyway = { stage = SaveStage.Editing },
            onOpenContact = onOpenContact,
            onDismiss = onDismiss,
        )

        SaveStage.Forbidden -> RetryableFailure(
            message = stringResource(MR.string.contactbook_error_forbidden),
            onRetry = { stage = SaveStage.Editing },
            onDismiss = onDismiss,
        )

        SaveStage.Failed -> RetryableFailure(
            message = stringResource(MR.string.chat_contact_card_save_failed),
            onRetry = { stage = SaveStage.Editing },
            onDismiss = onDismiss,
        )

        is SaveStage.Saved -> AlertDialog(
            onDismissRequest = onDismiss,
            icon = { Icon(Icons.Outlined.HowToReg, contentDescription = null) },
            title = { Text(stringResource(MR.string.chat_contact_card_saved_title)) },
            text = {
                Text(
                    when {
                        current.additionsFailed ->
                            stringResource(MR.string.chat_contact_card_partial_additions)
                        current.photoFailed ->
                            stringResource(MR.string.chat_contact_card_partial_photo)
                        else -> stringResource(MR.string.chat_contact_card_saved_body, cardName)
                    }
                )
            },
            confirmButton = {
                val uniqueId = current.uniqueId
                if (uniqueId == null) {
                    TextButton(onClick = onDismiss) { Text(stringResource(MR.string.ok)) }
                } else {
                    TextButton(
                        onClick = {
                            onDismiss()
                            onOpenContact(uniqueId, null)
                        },
                    ) {
                        Text(stringResource(MR.string.chat_contact_card_saved_open))
                    }
                }
            },
            dismissButton = if (current.uniqueId == null) null else {
                { TextButton(onClick = onDismiss) { Text(stringResource(MR.string.ok)) } }
            },
        )
    }
}

// Long enough that the warm path (repository already loaded) never paints a scrim.
private const val CHECKING_DIALOG_DELAY_MS = 250L

private const val HYDRATE_TIMEOUT_MS = 4_000L

/**
 * Reached by surprise, so the dismiss button has to stay the one that dismisses — Compose's Dialog
 * publishes no dismiss semantics, and a rendered Cancel is a screen reader's only way out. That
 * puts "View contact" in the body rather than dead-ending on two buttons.
 */
@Composable
internal fun DuplicateContactDialog(
    match: ContactBookEntry,
    onAddAnyway: () -> Unit,
    onOpenContact: (uniqueId: Uuid, odinId: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.ContactPage, contentDescription = null) },
        title = { Text(stringResource(MR.string.chat_contact_card_exists_title)) },
        text = {
            Column {
                Text(stringResource(MR.string.chat_contact_card_exists_body, match.displayName))
                TextButton(
                    onClick = {
                        onDismiss()
                        onOpenContact(match.uniqueId, match.odinId)
                    },
                    modifier = Modifier.align(Alignment.Start).heightIn(min = 48.dp),
                ) {
                    Text(stringResource(MR.string.chat_contact_card_exists_open))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onAddAnyway) {
                Text(stringResource(MR.string.chat_contact_card_add_anyway))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(MR.string.cancel)) }
        },
    )
}

@Composable
private fun RetryableFailure(message: String, onRetry: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.string.chat_contact_card_save_failed_title)) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onRetry) { Text(stringResource(MR.string.chat_contact_card_retry)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(MR.string.cancel)) }
        },
    )
}

// Restores the flow after an activity restart (process death, "don't keep activities"), where the
// message it came from may have scrolled away. Not rotation: MainActivity handles that itself
// (androidApp AndroidManifest, configChanges="orientation|…").
val ContactCardDescriptorSaver: Saver<ContactCardDescriptor?, String> = Saver(
    save = { it?.let { card -> Json.encodeToString(card) }.orEmpty() },
    restore = { stored ->
        stored.ifBlank { null }?.let {
            runCatching { Json.decodeFromString<ContactCardDescriptor>(it) }.getOrNull()
        }
    },
)

private const val TAG = "ContactCardSaveHost"
