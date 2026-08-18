@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.contactbook

import id.homebase.api.client.ForbiddenException
import id.homebase.core.ui.screens.contactbook.model.ContactFieldOverlay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Merging a card into an existing contact writes the extras to the override blob after the contact
 * itself. That second write can fail on its own, and its failure used to be discarded — the user
 * was told "Contact saved" while every extra phone, extra email and the organization had gone
 * nowhere. These pin that the failure reaches the caller.
 */
class AttachEditAdditionsTest {

    private val saved = ContactSaveResult.Success(photoFailed = false)
    private val additions = ContactFieldOverlay(
        additionalPhones = listOf("+14155550123"),
        organization = "Contoso",
    )

    private fun assertLossReported(result: ContactSaveResult) {
        val success = assertIs<ContactSaveResult.Success>(result)
        assertTrue(
            success.additionsFailed,
            "The contact is written but its extras are not; silence here reads as a full save.",
        )
    }

    @Test
    fun `a null tag from the overlay write is reported as a partial save`() = runTest {
        val result = attachEditAdditions(
            result = saved,
            additions = additions,
            hadOverride = false,
            currentTag = Uuid.random(),
            // ContactOverrideStore.save's own convention for a generic write failure.
            saveOverlay = { _, _ -> null },
        )

        assertLossReported(result)
    }

    @Test
    fun `a contact whose version tag cannot be found is reported as a partial save`() = runTest {
        var attempted = false

        val result = attachEditAdditions(
            result = saved,
            additions = additions,
            hadOverride = false,
            currentTag = null,
            saveOverlay = { _, _ -> attempted = true; Uuid.random() },
        )

        assertFalse(attempted, "There is no tag to write under, so no write should be attempted.")
        assertLossReported(result)
    }

    @Test
    fun `a successful overlay write leaves the result untouched`() = runTest {
        val tag = Uuid.random()
        var seen: Pair<Uuid, ContactFieldOverlay>? = null

        val result = attachEditAdditions(
            result = saved,
            additions = additions,
            hadOverride = false,
            currentTag = tag,
            saveOverlay = { t, overlay -> seen = t to overlay; Uuid.random() },
        )

        assertEquals(tag to additions, seen)
        assertEquals(saved, result)
    }

    @Test
    fun `a 403 on the overlay write is Forbidden, not a partial save`() = runTest {
        val result = attachEditAdditions(
            result = saved,
            additions = additions,
            hadOverride = false,
            currentTag = Uuid.random(),
            saveOverlay = { _, _ -> throw ForbiddenException() },
        )

        assertEquals(ContactSaveResult.Forbidden, result)
    }

    @Test
    fun `nothing to add and nothing previously overridden skips the write entirely`() = runTest {
        var attempted = false

        val result = attachEditAdditions(
            result = saved,
            additions = ContactFieldOverlay(),
            hadOverride = false,
            currentTag = Uuid.random(),
            saveOverlay = { _, _ -> attempted = true; Uuid.random() },
        )

        assertFalse(attempted)
        assertEquals(saved, result)
    }

    @Test
    fun `clearing the last extra still writes, so the blob is emptied rather than left stale`() =
        runTest {
            var attempted = false

            val result = attachEditAdditions(
                result = saved,
                additions = ContactFieldOverlay(),
                // The contact had extras before this edit; an empty overlay now means "delete".
                hadOverride = true,
                currentTag = Uuid.random(),
                saveOverlay = { _, _ -> attempted = true; Uuid.random() },
            )

            assertTrue(attempted, "Skipping this leaves the old extras on the contact forever.")
            assertEquals(saved, result)
        }
}
