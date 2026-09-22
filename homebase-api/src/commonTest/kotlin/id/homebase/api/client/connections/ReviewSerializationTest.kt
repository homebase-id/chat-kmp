@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.api.client.connections

import id.homebase.api.common.OdinId
import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * `reviewedAt` is the owner's private stamp. `vetted` is the retired alias — nothing reads it,
 * but a response carrying it still has to parse.
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

/**
 * The awaiting entries carry names now, and every null in them means something specific — an
 * owner circle, a deleted circle, a deleted app. Each has to survive the parse so the UI can say
 * which, rather than hiding a stuck enrolment.
 */
class AwaitingAppsSerializationTest {

    private fun parse(json: String) =
        OdinSystemSerializer.deserialize<RedactedAccessExchangeGrant>(json)

    @Test
    fun anAppOwnedEntryCarriesBothNames() {
        val grant = parse(
            """{"isRevoked":false,"awaitingApps":[{
               "circleId":"2942b454-2416-4f85-b378-42d5e25388ed","circleName":"Moments",
               "appId":"0babb1e6-7604-4bcd-b1fb-87e959226492","appName":"Moments"}]}"""
                .trimIndent().replace("\n", "")
        )

        val entry = grant.awaitingApps.single()
        assertEquals("Moments", entry.circleName)
        assertEquals("Moments", entry.appName)
        assertFalse(entry.awaitsOwner)
    }

    /** No owning app means it waits on the owner, not on "null". */
    @Test
    fun aNullAppIdMeansTheOwnerMustAct() {
        val grant = parse(
            """{"isRevoked":false,"awaitingApps":[
               {"circleId":"8dfcdfb6-eb7d-bc7f-3c68-c0d1fe954920","circleName":"The Doe's and I"}]}"""
                .trimIndent().replace("\n", "")
        )

        assertTrue(grant.awaitingApps.single().awaitsOwner)
    }

    /** Reported deliberately after the circle is deleted, so the owner can see it is stuck. */
    @Test
    fun aDeletedCircleStillParses() {
        val grant = parse(
            """{"isRevoked":false,"awaitingApps":[
               {"circleId":"8dfcdfb6-eb7d-bc7f-3c68-c0d1fe954920","appId":"aa","appName":"Vault"}]}"""
                .trimIndent().replace("\n", "")
        )

        val entry = grant.awaitingApps.single()
        assertNull(entry.circleName)
        assertEquals("Vault", entry.appName)
        assertFalse(entry.awaitsOwner)
    }

    /** Ids arrive hyphenated here but dashless on a circle definition; the two must compare. */
    @Test
    fun theCircleIdNormalisesToACircleDefinitionId() {
        val grant = parse(
            """{"isRevoked":false,"awaitingApps":[
               {"circleId":"2942b454-2416-4f85-b378-42d5e25388ed"}]}"""
                .trimIndent().replace("\n", "")
        )

        assertEquals("2942b45424164f85b37842d5e25388ed", grant.awaitingApps.single().circleIdHex)
    }

    @Test
    fun aGrantWithNoAwaitingEntriesIsEmptyNotNull() {
        assertTrue(parse("""{"isRevoked":false}""").awaitingApps.isEmpty())
    }
}
