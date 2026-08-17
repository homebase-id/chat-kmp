@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.contactbook

import id.homebase.core.ui.screens.contactbook.model.ContactFieldOverlay
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * [saveNewContact]'s write ordering. Three writes have to chain — create, then the override
 * carrying the extras under the tag the create returned, then the photo under the tag the override
 * returned — and the second and third must never be able to make the caller retry the first: the
 * contact exists by then, and a retried create is a duplicate contact the user has to clean up.
 */
class SaveNewContactTest {

    private sealed interface Write {
        data class Create(val draft: ContactDraft, val withPhoto: Boolean) : Write
        data class Overlay(val id: Uuid, val tag: Uuid, val overlay: ContactFieldOverlay) : Write
        data class Photo(val id: Uuid, val tag: Uuid) : Write
    }

    private val draft = ContactDraft(givenName = "Ada", surname = "Vance", phone = "+14155550123")
    private val withOrganization = draft.copy(organization = "Contoso GmbH")
    private val photo = PlatformFile("/tmp/contact-avatar.png")
    private val createdId = Uuid.random()
    private val createdTag = Uuid.random()
    private val overlayTag = Uuid.random()

    private class Recorder {
        val writes = mutableListOf<Write>()
    }

    private suspend fun save(
        recorder: Recorder,
        draft: ContactDraft = this.draft,
        additionalPhones: List<String> = listOf("+14155550999"),
        additionalEmails: List<String> = emptyList(),
        photo: PlatformFile? = null,
        create: suspend (ContactDraft, PlatformFile?) -> ContactSaveResult = { _, _ ->
            ContactSaveResult.Success(
                photoFailed = false,
                uniqueId = createdId,
                versionTag = createdTag,
            )
        },
        overlay: suspend (Uuid, Uuid, ContactFieldOverlay) -> Uuid? = { _, _, _ -> overlayTag },
        upload: suspend (Uuid, Uuid, PlatformFile) -> Boolean = { _, _, _ -> true },
    ): ContactSaveResult = saveNewContact(
        draft = draft,
        additionalPhones = additionalPhones,
        additionalEmails = additionalEmails,
        photo = photo,
        createContact = { d, p ->
            recorder.writes += Write.Create(d, withPhoto = p != null)
            create(d, p)
        },
        saveOverlay = { id, tag, o ->
            recorder.writes += Write.Overlay(id, tag, o)
            overlay(id, tag, o)
        },
        uploadPhoto = { id, tag, file ->
            recorder.writes += Write.Photo(id, tag)
            upload(id, tag, file)
        },
    )

    @Test
    fun `each write carries the tag the one before it produced`() = runTest {
        val recorder = Recorder()

        val result = save(recorder, photo = photo)

        assertContentEquals(
            listOf(
                Write.Create(draft, withPhoto = false),
                Write.Overlay(
                    createdId,
                    createdTag,
                    ContactFieldOverlay(additionalPhones = listOf("+14155550999")),
                ),
                Write.Photo(createdId, overlayTag),
            ),
            recorder.writes,
        )
        assertEquals(
            ContactSaveResult.Success(
                photoFailed = false,
                additionsFailed = false,
                uniqueId = createdId,
                versionTag = overlayTag,
            ),
            result,
        )
    }

    @Test
    fun `a card with nothing extra is one write that carries the photo itself`() = runTest {
        val recorder = Recorder()

        save(recorder, additionalPhones = emptyList(), photo = photo)

        assertContentEquals(listOf(Write.Create(draft, withPhoto = true)), recorder.writes)
    }

    @Test
    fun `a failed overlay is partial, never a retry that creates the contact twice`() = runTest {
        val recorder = Recorder()

        val result = save(
            recorder,
            photo = photo,
            overlay = { _, _, _ -> error("app-data blob exceeds the tier size cap") },
        )

        assertEquals(1, recorder.writes.count { it is Write.Create }, "The create must not repeat.")
        assertTrue(result is ContactSaveResult.Success, "Failed/Forbidden here would invite a retry.")
        assertTrue(result.additionsFailed)
        assertFalse(result.photoFailed)
        assertEquals(
            Write.Photo(createdId, createdTag),
            recorder.writes.last(),
            "With no new tag from the overlay, the photo falls back to the create's.",
        )
    }

    @Test
    fun `an overlay that reports failure without throwing is partial too`() = runTest {
        val recorder = Recorder()

        val result = save(recorder, overlay = { _, _, _ -> null })

        assertTrue(result is ContactSaveResult.Success)
        assertTrue(result.additionsFailed)
    }

    @Test
    fun `a failed create writes nothing else`() = runTest {
        val recorder = Recorder()

        val result = save(recorder, photo = photo, create = { _, _ -> ContactSaveResult.Forbidden })

        assertEquals(ContactSaveResult.Forbidden, result)
        assertContentEquals(listOf(Write.Create(draft, withPhoto = false)), recorder.writes)
    }

    @Test
    fun `a create that returns no id cannot be layered on`() = runTest {
        val recorder = Recorder()

        val result = save(
            recorder,
            photo = photo,
            create = { _, _ -> ContactSaveResult.Success(photoFailed = false) },
        )

        assertTrue(result is ContactSaveResult.Success)
        assertTrue(result.additionsFailed)
        assertNull(result.uniqueId)
        assertContentEquals(listOf(Write.Create(draft, withPhoto = false)), recorder.writes)
    }

    @Test
    fun `a failed photo upload is reported without touching the contact again`() = runTest {
        val recorder = Recorder()

        val result = save(recorder, photo = photo, upload = { _, _, _ -> false })

        assertTrue(result is ContactSaveResult.Success)
        assertTrue(result.photoFailed)
        assertFalse(result.additionsFailed)
        assertEquals(1, recorder.writes.count { it is Write.Create })
    }

    // The contact schema has no organization slot, so it rides the same override write the extra
    // phones/emails do — including that write's partial-failure reporting.

    @Test
    fun `an organization alone is enough to need the override write`() = runTest {
        val recorder = Recorder()

        save(recorder, draft = withOrganization, additionalPhones = emptyList())

        assertContentEquals(
            listOf(
                Write.Create(withOrganization, withPhoto = false),
                Write.Overlay(createdId, createdTag, ContactFieldOverlay(organization = "Contoso GmbH")),
            ),
            recorder.writes,
        )
    }

    @Test
    fun `a failed override write cannot report the organization as saved`() = runTest {
        val recorder = Recorder()

        val result = save(
            recorder,
            draft = withOrganization,
            additionalPhones = emptyList(),
            overlay = { _, _, _ -> null },
        )

        assertTrue(result is ContactSaveResult.Success, "The contact exists; a retry would duplicate it.")
        assertTrue(result.additionsFailed, "Silent success here is exactly the defect being fixed.")
    }
}
