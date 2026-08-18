@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.contactbook.components

import id.homebase.core.ui.screens.contactbook.ContactSaveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The contact-card save host's only branch that decides what the user is told after a write.
 * Everything that isn't an unambiguous success has to land on a stage that still offers a retry.
 */
class SaveStageMappingTest {

    @Test
    fun `a throw out of the write is retryable, never a saved stage`() {
        assertEquals(SaveStage.Failed, saveStageFor(null))
    }

    @Test
    fun `a transport failure is retryable`() {
        assertEquals(SaveStage.Failed, saveStageFor(ContactSaveResult.Failed))
    }

    @Test
    fun `403 keeps a stage of its own so the UI can name the missing permission`() {
        assertEquals(SaveStage.Forbidden, saveStageFor(ContactSaveResult.Forbidden))
    }

    @Test
    fun `success carries the partial-failure flags through, so a half-saved contact says so`() {
        val uniqueId = Uuid.random()

        val stage = saveStageFor(
            ContactSaveResult.Success(
                photoFailed = true,
                additionsFailed = true,
                uniqueId = uniqueId,
            )
        )

        assertEquals(
            SaveStage.Saved(uniqueId = uniqueId, photoFailed = true, additionsFailed = true),
            stage,
        )
    }

    @Test
    fun `a clean success carries the id the Saved dialog needs to offer View contact`() {
        val uniqueId = Uuid.random()

        val stage = saveStageFor(ContactSaveResult.Success(photoFailed = false, uniqueId = uniqueId))

        assertEquals(
            SaveStage.Saved(uniqueId = uniqueId, photoFailed = false, additionsFailed = false),
            stage,
        )
    }
}
