package id.homebase.core.ui.screens.contactbook

import id.homebase.api.crypto.Md5
import id.homebase.core.ui.screens.contactbook.model.ContactBookEntry
import id.homebase.core.ui.screens.contactbook.model.ContactBookSource

/**
 * Resolve a set of circle-member domains (odinIds) to contact-book rows for display in
 * [id.homebase.core.ui.screens.contactbook.components.CircleMembersSheet]. Identities that
 * aren't in the address book fall back to a synthetic domain-named entry rather than being
 * dropped, so the roster always accounts for every member. Shared by contact detail and the
 * moments audience circle roster.
 */
fun resolveCircleMemberEntries(
    domains: Set<String>,
    contacts: List<ContactBookEntry>,
): List<ContactBookEntry> {
    val byOdin = contacts.filter { !it.odinId.isNullOrBlank() }.associateBy { it.odinId!!.lowercase() }
    return domains.map { domain -> byOdin[domain.lowercase()] ?: syntheticCircleMemberEntry(domain) }
}

/** A minimal entry for an identity not in the address book — keyed by the same md5→guid the
 *  server derives, so it dedups against a real entry if one appears later. */
private fun syntheticCircleMemberEntry(domain: String): ContactBookEntry {
    val uid = Md5.toGuidId(domain.lowercase())
    return ContactBookEntry(
        uniqueId = uid,
        fileId = uid,
        versionTag = null,
        odinId = domain,
        displayName = domain,
        source = ContactBookSource.CONNECTION,
    )
}
