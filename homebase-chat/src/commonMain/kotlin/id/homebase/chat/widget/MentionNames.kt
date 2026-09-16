package id.homebase.chat.widget

import androidx.compose.runtime.compositionLocalOf
import id.homebase.api.client.auth.OwnerSession
import id.homebase.api.common.OdinId
import id.homebase.chat.data.ContactUiModel
import id.homebase.chat.services.convo.selfDisplayLabel
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentMap

/**
 * Lowercased odinId → the name a mention chip should draw for it, for the whole conversation
 * screen. Ambient rather than threaded through `MessageItem` → `*MessageBubble` →
 * `MessageBubbleRaw`; the bubble then hands it to [ChatMarkdown] as an explicit [MentionContext],
 * which is what keeps the feed's call sites out of it.
 *
 * Not `static`, unlike [LocalCurrentOdinId]: this one changes whenever a contact is renamed or
 * added, and a static local invalidates its whole subtree with skipping disabled.
 */
internal val LocalMentionNames =
    compositionLocalOf<ImmutableMap<String, String>> { persistentMapOf() }

/**
 * An identity whose best-known name IS its domain is left OUT, so the chip falls through to the
 * raw `@odinId` instead of round-tripping the same string — that covers both an unsaved contact
 * and the pre-profile-load owner session, whose `displayName` is seeded with the domain.
 * [ownerSession] is folded in separately because you are not in your own contacts.
 */
internal fun mentionNamesOf(
    contacts: List<ContactUiModel>,
    ownerSession: OwnerSession?,
): ImmutableMap<String, String> {
    val names = mutableMapOf<String, String>()
    for (contact in contacts) {
        names.putName(contact.odinId, contact.name)
    }
    ownerSession?.let { names.putName(it.odinId, it.selfDisplayLabel()) }
    return names.toPersistentMap()
}

private fun MutableMap<String, String>.putName(odinId: OdinId, name: String) {
    val domain = odinId.domainName
    val trimmed = name.trim()
    if (trimmed.isEmpty() || trimmed.equals(domain, ignoreCase = true)) return
    put(domain, trimmed)
}
