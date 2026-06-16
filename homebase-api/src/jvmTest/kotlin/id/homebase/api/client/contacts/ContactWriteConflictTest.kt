@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.api.client.contacts

import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class ContactWriteConflictTest {

    private val uniqueId = "11111111-1111-1111-1111-111111111111"
    private val versionTag = "22222222-2222-2222-2222-222222222222"

    @Test
    fun deserializesCurrentIntoServerFileHeader() {
        val conflict = OdinSystemSerializer.deserialize<ContactWriteConflict>(
            ContactFixtures.conflictBody(uniqueId, versionTag),
        )

        // Authoritative tag is exposed top-level and mirrored on the file header.
        assertEquals(Uuid.parse(versionTag), conflict.versionTag)
        assertEquals(Uuid.parse(versionTag), conflict.current.fileMetadata.versionTag)

        // Contact id comes from the file header's appData, surfaced via the convenience accessor.
        assertEquals(Uuid.parse(uniqueId), conflict.uniqueId)
        assertEquals(Uuid.parse(uniqueId), conflict.current.fileMetadata.appData.uniqueId)
    }
}
