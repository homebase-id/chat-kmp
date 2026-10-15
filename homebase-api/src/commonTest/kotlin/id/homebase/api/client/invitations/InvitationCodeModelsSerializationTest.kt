package id.homebase.api.client.invitations

import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ignoreUnknownKeys is on, so a misnamed field would silently read as its default; this pins the server's shape.
class InvitationCodeModelsSerializationTest {

    @Test
    fun parsesTheServerShape() {
        val json = """
            {
              "codes": [
                { "code": "K7QX-M2PD-9FHW", "maxUses": 5, "usesLeft": 3, "link": "https://provisioning.example.com/?invitation-code=K7QX-M2PD-9FHW" }
              ]
            }
        """.trimIndent()

        val codes = OdinSystemSerializer.deserialize<InvitationCodesResponse>(json).codes

        assertEquals(
            listOf(
                InvitationCode(
                    code = "K7QX-M2PD-9FHW",
                    maxUses = 5,
                    usesLeft = 3,
                    link = "https://provisioning.example.com/?invitation-code=K7QX-M2PD-9FHW",
                ),
            ),
            codes,
        )
    }

    @Test
    fun emptyListParses() {
        assertTrue(OdinSystemSerializer.deserialize<InvitationCodesResponse>("""{"codes":[]}""").codes.isEmpty())
    }
}
