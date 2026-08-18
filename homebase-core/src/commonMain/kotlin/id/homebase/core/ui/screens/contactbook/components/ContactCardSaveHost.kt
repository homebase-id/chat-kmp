@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.contactbook.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContactPage
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import co.touchlab.kermit.Logger
import id.homebase.api.client.contacts.ContactRepository
import id.homebase.chat.contactcard.ContactCardDescriptor
import id.homebase.core.contactbook.ContactBookPreferences
import id.homebase.core.contactbook.ContactOverrideStore
import id.homebase.core.ui.screens.contactbook.ContactCardImport
import id.homebase.core.ui.screens.contactbook.ContactFieldValidation
import id.homebase.core.ui.screens.contactbook.ContactSaveResult
import id.homebase.core.ui.screens.contactbook.model.ContactBookEntry
import id.homebase.core.ui.screens.contactbook.model.toContactBookEntry
import id.homebase.core.ui.screens.contactbook.saveContactEdit
import id.homebase.core.ui.screens.contactbook.saveNewContact
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.contactbook_edit_odinid_from_card
import id.homebase.resources.chat_contact_card_exists_body
import id.homebase.resources.chat_contact_card_exists_identity_body
import id.homebase.resources.chat_contact_card_exists_open
import id.homebase.resources.chat_contact_card_exists_title
import id.homebase.resources.chat_contact_card_merge
import id.homebase.resources.chat_contact_card_merge_body
import id.homebase.resources.chat_contact_card_merge_confirm
import id.homebase.resources.chat_contact_card_partial_additions
import id.homebase.resources.chat_contact_card_partial_cleared
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

internal sealed interface SaveStage {
    data object Editing : SaveStage
    data object Saving : SaveStage
    data object Forbidden : SaveStage
    data object Failed : SaveStage
    data class Saved(
        val uniqueId: Uuid?,
        val photoFailed: Boolean,
        val additionsFailed: Boolean,
        val clearedFieldsIgnored: Boolean = false,
        /** Captured when the write starts: a duplicate banner can still arrive mid-write, and
         *  reading the merge target at render time names the wrong contact. */
        val name: String? = null,
    ) : SaveStage
}

// A null result is a throw out of the write, indistinguishable from a transport failure: both land
// on the retryable Failed, never on a stage that says "saved".
internal fun saveStageFor(result: ContactSaveResult?): SaveStage = when (result) {
    is ContactSaveResult.Success -> SaveStage.Saved(
        uniqueId = result.uniqueId,
        photoFailed = result.photoFailed,
        additionsFailed = result.additionsFailed,
        clearedFieldsIgnored = result.clearedFieldsIgnored,
    )
    ContactSaveResult.Forbidden -> SaveStage.Forbidden
    else -> SaveStage.Failed
}

/**
 * Receiving half of the contact card's save action: hosts the same seeded [ContactEditSheet] the
 * share-a-vCard flow uses. Lives in `:homebase-core` because [ContactEditSheet] and
 * [ContactRepository] do.
 *
 * The duplicate check runs beside the open editor, never in front of it: a cold contact book has to
 * fetch and decrypt one override blob per contact, which is seconds of nothing to look at. When it
 * finds a match the sheet grows a banner offering to add the card's new values to that contact
 * instead of creating a second one.
 */
@Composable
fun ContactCardSaveHost(
    descriptor: ContactCardDescriptor?,
    onDismiss: () -> Unit,
    onOpenContact: (uniqueId: Uuid, odinId: String?) -> Unit,
    // A clean save is expected and non-destructive, so it reports as a snackbar rather than a
    // second modal. Raised here because it must outlive this host, which unmounts immediately.
    onSaved: (name: String, uniqueId: Uuid?) -> Unit,
) {
    if (descriptor == null) return

    val repo: ContactRepository = koinInject()
    val store: ContactOverrideStore = koinInject()
    val preferences: ContactBookPreferences = koinInject()
    // A composition-scoped launch would cancel a half-written contact when the host goes away.
    val appScope: CoroutineScope = koinInject()
    var stage by remember(descriptor) { mutableStateOf<SaveStage>(SaveStage.Editing) }
    var duplicate by remember(descriptor) { mutableStateOf<ContactCardImport.ExistingContact?>(null) }
    // The check is allowed to outlive a fast Save tap, so the write awaits it rather than racing it
    // — otherwise the first thing a quick user does is create the duplicate this exists to prevent.
    val checked = remember(descriptor) { CompletableDeferred<ContactCardImport.ExistingContact?>() }
    var mergeInto by remember(descriptor) { mutableStateOf<ContactBookEntry?>(null) }
    var confirmMerge by remember(descriptor) { mutableStateOf<ContactBookEntry?>(null) }
    // Retained so Failed's "Try again" repeats the write instead of just reopening the sheet.
    var lastAttempt by remember(descriptor) { mutableStateOf<(() -> Unit)?>(null) }
    val cardName = descriptor.summaryLine()
        .ifBlank { stringResource(MR.string.chat_contact_card_title) }

    // Runs to completion rather than under a deadline: a time-boxed hydrate answers from a partial
    // override set, where a match that lives only in an override reads as "no duplicate". A save
    // that arrives first waits on it, but only up to DupeCheckSaveDeadlineMs.
    LaunchedEffect(descriptor) {
        try {
        duplicate = try {
            ContactCardImport.resolveExisting(
                descriptor,
                loadContacts = {
                    repo.ensureLoaded()
                    repo.contacts.value
                },
                loadOverrides = { contacts ->
                    store.hydrateAll(contacts)
                    store.overrides.value
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Logger.w(tag = TAG, throwable = e) { "contact load failed; skipping dupe check" }
            null
        }
        } finally {
            // In `finally` because cancellation is rethrown above: a write already suspended on
            // this deferred would otherwise stay suspended for the life of the process, and the
            // contact would never be written or reported.
            checked.complete(duplicate)
        }
    }

    // Mounted across Saving and both failures so a retry resumes on the user's own edits.
    val current = stage
    val target = mergeInto
    if (current is SaveStage.Editing || current is SaveStage.Saving ||
        current is SaveStage.Forbidden || current is SaveStage.Failed
    ) {
        val match = duplicate
        val saving = current is SaveStage.Saving
        val banner: (@Composable () -> Unit)? = when {
            target != null -> {
                { MergeBanner(name = target.displayName) }
            }
            match != null -> {
                {
                    DuplicateBanner(
                        match = match,
                        // The check is allowed to land late, so the banner can appear over a write
                        // already in flight; acting on it then would rebuild the sheet mid-save.
                        enabled = !saving,
                        onMerge = { confirmMerge = match.entry },
                        onOpen = {
                            onDismiss()
                            onOpenContact(match.entry.uniqueId, match.entry.odinId)
                        },
                    )
                }
            }
            else -> null
        }
        // Switching to the merge editor re-seeds every field, so the sheet has to start over. Keyed
        // on the descriptor too: ContactEditSheet seeds with an unkeyed remember, so a new card
        // arriving under a mounted sheet would otherwise keep the previous card's draft.
        // A saved odinId is fetched on every render of the contact's row, and any client can put
        // any host in that field — so say what saving it costs rather than silently dropping it.
        val odinIdNote = stringResource(MR.string.contactbook_edit_odinid_from_card)
            .takeIf { descriptor.identity() != null && target?.odinId.isNullOrBlank() }
        key(descriptor, target?.uniqueId) {
            ContactEditSheet(
                // The unfilled target: it decides whether the sheet calls itself profile-synced,
                // and it must agree with useOverride below. The card's own values reach the draft
                // through `seed`, which now fills the gaps a merge target leaves.
                editing = target,
                seed = remember(descriptor) { ContactCardImport.toDraft(descriptor) },
                seedAdditionalPhones = remember(descriptor, target) {
                    if (target == null) ContactCardImport.extraPhones(descriptor)
                    else descriptor.phonesMissingFrom(target)
                },
                seedAdditionalEmails = remember(descriptor, target) {
                    if (target == null) ContactCardImport.extraEmails(descriptor)
                    else descriptor.emailsMissingFrom(target)
                },
                saving = current is SaveStage.Saving,
                banner = banner,
                odinIdNote = odinIdNote,
                onSave = { draft, extraPhones, extraEmails, photo ->
                    val savedName = target?.displayName ?: cardName
                    val attempt: () -> Unit = {
                        // Read per attempt, not once at the first tap: a retry after the check
                        // landed has seen the banner, and capturing it outside made every retry
                        // bounce back to the sheet without ever writing.
                        val sawBanner = duplicate != null
                    stage = SaveStage.Saving
                    appScope.launch {
                        // A match that only lands now is one the user was never offered; show it
                        // rather than silently creating the second contact they'd have declined.
                        // Bounded: Saving disables Cancel, so an unbounded wait on a cold contact
                        // book pins the user in a spinner with no way out. A timeout is treated
                        // like the check failing — proceed, same as the catch above.
                        if (target == null && !sawBanner) {
                            val late = withTimeoutOrNull(DupeCheckSaveDeadlineMs) { checked.await() }
                            if (late != null) {
                                stage = SaveStage.Editing
                                return@launch
                            }
                        }
                        val result = try {
                            if (target == null) {
                                saveNewContact(store, repo, draft, extraPhones, extraEmails, photo)
                            } else {
                                saveContactEdit(
                                    store = store,
                                    repo = repo,
                                    useOverride = !target.odinId.isNullOrBlank() &&
                                        target.versionTag != null,
                                    editing = target,
                                    synced = repo.syncedBaselineOf(target),
                                    draft = draft,
                                    additionalPhones = extraPhones,
                                    additionalEmails = extraEmails,
                                    photo = photo,
                                )
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            Logger.e(tag = TAG, throwable = e) { "contact save threw" }
                            null
                        }
                        // An override write reports no id; for a merge we already know it.
                        val next = saveStageFor(result).let {
                            if (it is SaveStage.Saved) {
                                it.copy(
                                    uniqueId = it.uniqueId ?: target?.uniqueId,
                                    name = savedName,
                                )
                            } else {
                                it
                            }
                        }
                        // A contact saved from chat is a contact book with a contact in it; without
                        // this AppNavHost still shows the first-run intro over it.
                        if (next is SaveStage.Saved) {
                            try {
                                preferences.setOnboardingComplete(true)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                Logger.w(tag = TAG, throwable = e) { "onboarding flag not stored" }
                            }
                        }
                        stage = next
                    }
                    }
                    lastAttempt = attempt
                    attempt()
                },
                onDismiss = onDismiss,
            )
        }
    }

    val pendingMerge = confirmMerge
    if (pendingMerge != null) {
        // Merging re-seeds every field from the matched contact, so anything typed here is lost.
        AlertDialog(
            onDismissRequest = { confirmMerge = null },
            title = { Text(stringResource(MR.string.chat_contact_card_merge)) },
            text = {
                Text(
                    stringResource(
                        MR.string.chat_contact_card_merge_confirm,
                        pendingMerge.displayName,
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        mergeInto = pendingMerge
                        confirmMerge = null
                    },
                ) {
                    Text(stringResource(MR.string.chat_contact_card_merge))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmMerge = null }) {
                    Text(stringResource(MR.string.cancel))
                }
            },
        )
    }

    when (current) {
        SaveStage.Editing, SaveStage.Saving -> Unit

        // No retry: a 403 is the app token missing manage-contacts, and repeating the write
        // fails identically.
        SaveStage.Forbidden -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(MR.string.chat_contact_card_save_failed_title)) },
            text = { Text(stringResource(MR.string.contactbook_error_forbidden)) },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(MR.string.ok)) }
            },
        )

        SaveStage.Failed -> RetryableFailure(
            message = stringResource(MR.string.chat_contact_card_save_failed),
            onRetry = {
                val retry = lastAttempt
                if (retry != null) retry() else stage = SaveStage.Editing
            },
            // Back to the sheet, not out of the flow: it still holds everything the user typed.
            onDismiss = { stage = SaveStage.Editing },
        )

        is SaveStage.Saved -> if (
            !current.additionsFailed && !current.photoFailed && !current.clearedFieldsIgnored
        ) {
            val name = current.name ?: cardName
            val uniqueId = current.uniqueId
            LaunchedEffect(current) {
                onSaved(name, uniqueId)
                onDismiss()
            }
        } else AlertDialog(
            onDismissRequest = onDismiss,
            // Every partial failure, not just the first: two can be true at once, and the
            // unmentioned one is the data the user thinks they saved.
            icon = { Icon(Icons.Outlined.ErrorOutline, contentDescription = null) },
            title = { Text(stringResource(MR.string.chat_contact_card_saved_title)) },
            text = {
                val problems = listOfNotNull(
                    stringResource(MR.string.chat_contact_card_partial_additions)
                        .takeIf { current.additionsFailed },
                    stringResource(MR.string.chat_contact_card_partial_photo)
                        .takeIf { current.photoFailed },
                    stringResource(MR.string.chat_contact_card_partial_cleared)
                        .takeIf { current.clearedFieldsIgnored },
                )
                // Which contact it was still leads: a problem list on its own leaves the user
                // reading about data that failed with no idea what it failed on.
                val saved = stringResource(
                    MR.string.chat_contact_card_saved_body,
                    current.name ?: cardName,
                )
                Text((listOf(saved) + problems).joinToString(" "))
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

// The matched entry carries its override applied; diffing an edit against it would drop the user's
// existing primary overrides, so the merge writes against the pre-override contact.
private fun ContactRepository.syncedBaselineOf(entry: ContactBookEntry): ContactBookEntry =
    contacts.value.firstOrNull { it.uniqueId == entry.uniqueId }?.toContactBookEntry() ?: entry

private fun ContactCardDescriptor.phonesMissingFrom(entry: ContactBookEntry): List<String> {
    val held = (listOfNotNull(entry.phone) + entry.additionalPhones)
        .map { ContactFieldValidation.normalizePhone(it) }
        .filter { it.isNotBlank() }
        .toSet()
    return phones
        .map { ContactFieldValidation.normalizePhone(it) }
        .filter { it.isNotBlank() && it !in held }
        .distinct()
}

private fun ContactCardDescriptor.emailsMissingFrom(entry: ContactBookEntry): List<String> {
    val held = (listOfNotNull(entry.email) + entry.additionalEmails)
        .mapNotNull { it.trim().lowercase().ifBlank { null } }
        .toSet()
    return emails
        .map { it.trim() }
        .filter { it.isNotBlank() && it.lowercase() !in held }
        .distinct()
}

@Composable
private fun DuplicateBanner(
    match: ContactCardImport.ExistingContact,
    enabled: Boolean,
    onMerge: () -> Unit,
    onOpen: () -> Unit,
) {
    val body = when (match.matchedOn) {
        ContactCardImport.ExistingContact.MatchedOn.Identity ->
            MR.string.chat_contact_card_exists_identity_body
        ContactCardImport.ExistingContact.MatchedOn.PhoneOrEmail ->
            MR.string.chat_contact_card_exists_body
    }
    SheetBanner(
        title = stringResource(MR.string.chat_contact_card_exists_title),
        text = stringResource(body, match.entry.displayName),
        // Adding to the contact you already have is the recommended action, so it carries the
        // emphasis; saving a second contact is the sheet's own Save, one step below.
        actions = {
            TextButton(onClick = onOpen, enabled = enabled) {
                Text(stringResource(MR.string.chat_contact_card_exists_open))
            }
            FilledTonalButton(onClick = onMerge, enabled = enabled) {
                Text(stringResource(MR.string.chat_contact_card_merge))
            }
        },
    )
}

@Composable
private fun MergeBanner(name: String) {
    SheetBanner(text = stringResource(MR.string.chat_contact_card_merge_body, name))
}

@Composable
private fun SheetBanner(
    text: String,
    title: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(12.dp),
        // Merged, so its arrival is announced once instead of line by line.
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    Icons.Outlined.ContactPage,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Column {
                    if (title != null) {
                        Text(text = title, style = MaterialTheme.typography.titleSmall)
                    }
                    Text(text = text, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (actions != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
        }
    }
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

// Only bounds the case where Save beat the check; the check itself still runs to completion.
private const val DupeCheckSaveDeadlineMs = 4_000L
