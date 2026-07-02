@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.chat.data

import id.homebase.api.client.contacts.Contact
import id.homebase.api.client.contacts.ContactContent
import id.homebase.api.client.contacts.ContactName
import id.homebase.api.common.OdinId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Pins the contract that no avatar-model path emits blank avatar fields (#952: the Location
 * dashboard rendered blank circles because `ContactService.resolveByOdinId`'s fallback returned
 * empty `avatarInitials`/`avatarUrl` for odinIds not in the contact book).
 */
class ContactUiModelFallbackTest {

    private val odinId = OdinId("frodo.digital")

    // =========================================================
    // ContactUiModel.fallbackFor — the resolveByOdinId fallback
    // =========================================================

    @Test
    fun fallbackFor_derivesInitialsFromDomain() {
        assertEquals("F", ContactUiModel.fallbackFor(odinId).avatarInitials)
    }

    @Test
    fun fallbackFor_usesCanonicalPublicImageUrl() {
        assertEquals("https://frodo.digital/pub/image", ContactUiModel.fallbackFor(odinId).avatarUrl)
    }

    @Test
    fun fallbackFor_nameIsDomainName() {
        assertEquals("frodo.digital", ContactUiModel.fallbackFor(odinId).name)
    }

    @Test
    fun fallbackFor_idIsStableHashOfOdinId() {
        val first = ContactUiModel.fallbackFor(odinId)
        val second = ContactUiModel.fallbackFor(odinId)
        assertEquals(odinId.toHashId(), first.id)
        assertEquals(first.id, second.id)
    }

    // =========================================================
    // Contact.toContactUiModel — the saved-contact path
    // =========================================================

    private fun contact(content: ContactContent) = Contact(
        uniqueId = Uuid.random(),
        versionTag = null,
        content = content,
    )

    @Test
    fun toContactUiModel_usesCanonicalPublicImageUrl() {
        val model = contact(
            ContactContent(odinId = "frodo.digital", name = ContactName(displayName = "Frodo Baggins"))
        ).toContactUiModel()
        assertEquals("https://frodo.digital/pub/image", model?.avatarUrl)
    }

    @Test
    fun toContactUiModel_initialsFromGivenAndSurname() {
        val model = contact(
            ContactContent(
                odinId = "frodo.digital",
                name = ContactName(givenName = "Frodo", surname = "Baggins"),
            )
        ).toContactUiModel()
        assertEquals("FB", model?.avatarInitials)
    }

    @Test
    fun toContactUiModel_namelessContact_initialsNeverBlank() {
        val model = contact(ContactContent(odinId = "frodo.digital")).toContactUiModel()
        assertEquals("?", model?.avatarInitials)
    }

    @Test
    fun toContactUiModel_blankOdinId_returnsNull() {
        assertNull(contact(ContactContent(odinId = " ")).toContactUiModel())
        assertNull(contact(ContactContent(odinId = null)).toContactUiModel())
    }
}
