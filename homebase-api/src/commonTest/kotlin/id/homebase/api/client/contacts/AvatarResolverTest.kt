package id.homebase.api.client.contacts

import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.common.OdinId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.uuid.Uuid

class AvatarResolverTest {

    private val frodo = OdinId("frodo.dotyou.cloud")

    private fun image(encrypted: Boolean) = ContactImageRef(
        driveId = Uuid.random(),
        fileId = Uuid.random(),
        payload = PayloadDescriptor(key = "prfl_pic"),
        previewThumbnail = null,
        keyHeader = null,
        isEncrypted = encrypted,
    )

    private fun contact(
        odinId: String = frodo.domainName,
        name: ContactName? = ContactName(givenName = "Frodo", surname = "Baggins"),
        image: ContactImageRef? = null,
    ) = Contact(
        uniqueId = Uuid.random(),
        versionTag = null,
        content = ContactContent(odinId = odinId, name = name),
        image = image,
    )

    @Test
    fun storedPhotoWinsOverPublicAvatar() {
        val img = image(encrypted = true)
        val source = resolveAvatar(frodo, listOf(contact(image = img)), AvatarCapability.Any)
        assertIs<AvatarSource.StoredPhoto>(source)
        assertEquals(img, source.image)
        assertEquals("FB", source.initials)
    }

    @Test
    fun unencryptedOnlySkipsAnEncryptedStoredPhoto() {
        val source = resolveAvatar(frodo, listOf(contact(image = image(encrypted = true))), AvatarCapability.UnencryptedOnly)
        assertIs<AvatarSource.PublicPhoto>(source)
        assertEquals("FB", source.initials)
    }

    @Test
    fun unencryptedOnlyKeepsAnUnencryptedStoredPhoto() {
        val source = resolveAvatar(frodo, listOf(contact(image = image(encrypted = false))), AvatarCapability.UnencryptedOnly)
        assertIs<AvatarSource.StoredPhoto>(source)
    }

    @Test
    fun noContactFallsBackToPublicAvatarWithDomainInitial() {
        val source = resolveAvatar(frodo, emptyList(), AvatarCapability.Any)
        assertEquals(AvatarSource.PublicPhoto(frodo, "F"), source)
    }

    @Test
    fun contactWithoutAPhotoKeepsItsNameInitials() {
        val source = resolveAvatar(frodo, listOf(contact()), AvatarCapability.Any)
        assertEquals(AvatarSource.PublicPhoto(frodo, "FB"), source)
    }

    @Test
    fun namelessContactUsesTheDomainInitial() {
        val source = resolveAvatar(frodo, listOf(contact(name = null)), AvatarCapability.Any)
        assertEquals("F", source.initials)
    }

    @Test
    fun matchesTheStoredOdinIdIgnoringCaseAndWhitespace() {
        val source = resolveAvatar(frodo, listOf(contact(odinId = " Frodo.DotYou.Cloud ", image = image(true))), AvatarCapability.Any)
        assertIs<AvatarSource.StoredPhoto>(source)
    }

    @Test
    fun aDuplicateContactWithAPhotoIsPreferred() {
        val img = image(encrypted = true)
        val source = resolveAvatar(frodo, listOf(contact(), contact(image = img)), AvatarCapability.Any)
        assertIs<AvatarSource.StoredPhoto>(source)
        assertEquals(img, source.image)
    }

    @Test
    fun otherIdentitiesDoNotMatch() {
        val source = resolveAvatar(frodo, listOf(contact(odinId = "sam.dotyou.cloud", image = image(true))), AvatarCapability.Any)
        assertIs<AvatarSource.PublicPhoto>(source)
    }

    @Test
    fun unsavedIdentityUsesTheCallersDisplayName() {
        val source = resolveAvatar(frodo, emptyList(), AvatarCapability.Any, displayName = "Frodo Baggins")
        assertEquals("FB", source.initials)
    }

    @Test
    fun savedContactNameWinsOverTheCallersDisplayName() {
        val source = resolveAvatar(frodo, listOf(contact()), AvatarCapability.Any, displayName = "Mr Underhill")
        assertEquals("FB", source.initials)
    }

    @Test
    fun blankDisplayNameFallsBackToTheDomain() {
        val source = resolveAvatar(frodo, emptyList(), AvatarCapability.Any, displayName = "  ")
        assertEquals("F", source.initials)
    }
}
