package id.homebase.chat.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.client.auth.OwnerSession
import id.homebase.chat.data.ContactUiModel
import id.homebase.chat.services.convo.contact.ContactService
import id.homebase.chat.services.convo.selfDisplayLabel
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentMap
import org.koin.compose.koinInject

/**
 * Lowercased odinId → the name a mention chip should draw for it, for the whole conversation
 * screen. Ambient like its sibling [LocalCurrentOdinId] rather than threaded through
 * `MessageItem` → `*MessageBubble` → `MessageBubbleRaw`; the bubble then hands it to
 * [ChatMarkdown] as an explicit [MentionContext], which is what keeps the feed's call sites out of
 * it (they render through the same composable but never through a bubble).
 */
val LocalMentionNames = staticCompositionLocalOf<ImmutableMap<String, String>> { persistentMapOf() }

/**
 * Resolves every known identity to a display name ONCE per contact-cache change, not per bubble
 * and not per recomposition — the chip's annotator gets finished data and never reaches for a
 * contact mid-render.
 *
 * [ContactService.contacts] is the same cache the rest of chat resolves sender names through
 * (`ChatMessageStream.resolveDisplayName`), so a mention and the bubble's author line can never
 * disagree. [ownerSession] is folded in separately for the reason that code has to as well: you
 * are not in your own contacts, so the owner would otherwise resolve to a bare domain.
 */
@Composable
fun rememberMentionNames(ownerSession: OwnerSession?): ImmutableMap<String, String> {
    val contactService: ContactService = koinInject()
    val contacts by contactService.contacts.collectAsStateWithLifecycle()
    return remember(contacts, ownerSession) { mentionNamesOf(contacts, ownerSession) }
}

/**
 * An identity whose best-known name IS its domain is left OUT, so the chip falls through to the
 * raw `@odinId` instead of round-tripping the same string. That covers both an unsaved contact
 * and the pre-profile-load owner session, whose `displayName` is seeded with the domain.
 */
internal fun mentionNamesOf(
    contacts: List<ContactUiModel>,
    ownerSession: OwnerSession?,
): ImmutableMap<String, String> {
    val names = mutableMapOf<String, String>()
    for (contact in contacts) {
        names.putName(contact.odinId.domainName, contact.name)
    }
    ownerSession?.let { names.putName(it.odinId.domainName, it.selfDisplayLabel()) }
    return names.toPersistentMap()
}

private fun MutableMap<String, String>.putName(domain: String, name: String) {
    val trimmed = name.trim()
    if (trimmed.isEmpty() || trimmed.equals(domain, ignoreCase = true)) return
    put(domain.lowercase(), trimmed)
}
