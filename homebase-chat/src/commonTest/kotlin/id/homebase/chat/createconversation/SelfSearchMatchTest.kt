@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.chat.createconversation

import id.homebase.api.client.auth.OwnerSession
import id.homebase.api.common.OdinId
import id.homebase.chat.data.ContactUiModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The self-search seam behind "searching your own name surfaces Note to Self as a 'Name (you)' row
 * with your avatar" (#895). Covers the matcher/label helpers and [filterAndGroup]'s list shaping:
 * self row only on a match, your own identity excluded from the normal contact rows.
 */
class SelfSearchMatchTest {

    private fun session(displayName: String?, handle: String = "samwise.gamgee.demo.rocks") =
        OwnerSession(
            odinId = OdinId(handle),
            displayName = displayName,
            firstName = null,
            surName = null,
            profileImageFileId = null,
            profileImageFileKey = null,
            profileImagePreviewThumbnail = null,
            profileImageLastModified = null,
            status = null,
        )

    private fun contact(name: String, handle: String) =
        ContactUiModel(id = Uuid.random(), odinId = OdinId(handle), name = name, avatarInitials = "")

    // ---- matchesQuery / displayLabel ----

    @Test
    fun matches_displayName_caseInsensitive() {
        assertTrue(session("Samwise Gamgee").matchesQuery("sam"))
        assertTrue(session("Samwise Gamgee").matchesQuery("GAMGEE"))
    }

    @Test
    fun matches_handle_evenWhenNameDoesnt() {
        // A different display name, but the query hits the odinId handle.
        assertTrue(session("Frodo Baggins").matchesQuery("gamgee.demo"))
    }

    @Test
    fun noMatch_whenQueryHitsNeither() {
        assertFalse(session("Frodo Baggins").matchesQuery("mordor"))
    }

    @Test
    fun nullSession_neverMatches() {
        assertFalse((null as OwnerSession?).matchesQuery("sam"))
    }

    @Test
    fun blankQuery_neverMatches() {
        assertFalse(session("Samwise Gamgee").matchesQuery("   "))
    }

    @Test
    fun displayLabel_prefersName() {
        assertEquals("Samwise Gamgee", session("Samwise Gamgee").displayLabel())
    }

    @Test
    fun displayLabel_fallsBackToHandle_whenNameNullOrBlank() {
        val expected = OdinId("samwise.gamgee.demo.rocks").domainName
        assertEquals(expected, session(displayName = null).displayLabel())
        assertEquals(expected, session(displayName = "   ").displayLabel())
    }

    // ---- filterAndGroup (the feature seam) ----

    @Test
    fun filterAndGroup_idle_showsStandingActions_andNoSelfRow() {
        val items = filterAndGroup(
            contacts = listOf(contact("Frodo Baggins", "frodo.baggins.demo.rocks")),
            groupConversations = emptyList(),
            query = "",
            self = session("Samwise Gamgee"),
        )
        val note = items.filterIsInstance<CreateConversationListItem.NoteToSelf>().single()
        assertNull(note.self) // idle Note to Self carries no self ref → plain "Note to Self"
        assertTrue(items.any { it is CreateConversationListItem.NewGroup })
        assertTrue(items.any { it is CreateConversationListItem.NewContact })
    }

    @Test
    fun filterAndGroup_selfMatch_emitsSelfRowWithAvatarData() {
        val self = session("Samwise Gamgee")
        val items = filterAndGroup(
            contacts = emptyList(),
            groupConversations = emptyList(),
            query = "sam",
            self = self,
        )

        val ref = assertNotNull(
            items.filterIsInstance<CreateConversationListItem.NoteToSelf>().single().self
        )
        assertEquals(self.odinId, ref.odinId)
        assertEquals("Samwise Gamgee", ref.name)
        assertEquals("SG", ref.initials) // OwnerSession.initials() from the display name
    }

    @Test
    fun filterAndGroup_excludesSelfFromContactRows_keepsDifferentSameName() {
        val self = session("Samwise Gamgee", handle = "samwise.gamgee.demo.rocks")
        val items = filterAndGroup(
            contacts = listOf(
                contact("Samwise Gamgee", "samwise.gamgee.demo.rocks"), // self, as a stored contact
                contact("Samwise Gamgee", "samwisegamgee.me"),          // a DIFFERENT Samwise
            ),
            groupConversations = emptyList(),
            query = "sam",
            self = self,
        )
        val rows = items.filterIsInstance<CreateConversationListItem.Contacts>()
            .single().contactGroups.flatMap { it.contacts }.map { it.odinId.toString() }

        assertTrue("samwisegamgee.me" in rows)             // the other person stays
        assertFalse("samwise.gamgee.demo.rocks" in rows)   // your own identity is filtered out
    }

    @Test
    fun filterAndGroup_noSelfMatch_emitsNoSelfRow() {
        val items = filterAndGroup(
            contacts = emptyList(),
            groupConversations = emptyList(),
            query = "mordor",
            self = session("Samwise Gamgee"),
        )
        assertTrue(items.filterIsInstance<CreateConversationListItem.NoteToSelf>().isEmpty())
    }
}
