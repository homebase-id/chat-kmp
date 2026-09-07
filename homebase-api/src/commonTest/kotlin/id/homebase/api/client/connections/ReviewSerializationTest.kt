@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.api.client.connections

import id.homebase.api.common.OdinId
import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * `reviewedAt` is the owner's private stamp and `vetted` is now the server's alias for it, so the
 * pair has to survive a server on either side of the review rollout.
 */
class ReviewSerializationTest {

    private val base = """
        "odinId":"sam.dotyou.cloud","status":"connected","created":1,"lastUpdated":2,
        "connectionRequestOrigin":"identityOwner","hasVerificationHash":false,"rku":false
    """.trimIndent().replace("\n", "")

    @Test
    fun anUnreviewedConnectionCarriesNoStamp() {
        val reg = OdinSystemSerializer
            .deserialize<RedactedIdentityConnectionRegistration>("{$base,\"vetted\":false}")

        assertNull(reg.reviewedAt)
    }

    @Test
    fun aReviewedConnectionCarriesTheTimestamp() {
        val reg = OdinSystemSerializer
            .deserialize<RedactedIdentityConnectionRegistration>("{$base,\"reviewedAt\":1764000000000}")

        assertEquals(1_764_000_000_000L, reg.reviewedAt)
    }

    /** A server that predates the review endpoints sends vetted and no reviewedAt. */
    @Test
    fun aPreRolloutServerShapeStillParses() {
        val reg = OdinSystemSerializer
            .deserialize<RedactedIdentityConnectionRegistration>("{$base,\"vetted\":true}")

        assertNull(reg.reviewedAt)
        @Suppress("DEPRECATION")
        assertTrue(reg.vetted)
    }

    /** An empty selection is the "chat only" outcome, and has to reach the server as `[]`. */
    @Test
    fun aChatOnlyReviewSendsAnEmptyArrayNotAnOmittedField() {
        val json = OdinSystemSerializer.serialize(
            ReviewConnectionRequest(OdinId("sam.dotyou.cloud"), emptyList())
        )

        assertTrue(json.contains("\"circleIds\":[]"), json)
    }

    @Test
    fun circleIdsRideTheReviewRequest() {
        val id = Uuid.parse("2d781401-3804-4b4b-b03f-4b4d1e4c1a06")
        val json = OdinSystemSerializer.serialize(
            ReviewConnectionRequest(OdinId("sam.dotyou.cloud"), listOf(id))
        )

        assertTrue(json.contains("2d781401-3804-4b4b-b03f-4b4d1e4c1a06"), json)
        assertTrue(json.contains("sam.dotyou.cloud"), json)
    }
}
