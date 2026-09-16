package id.homebase.api.client.connections

import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The enrolment endpoints serve grantOn and kind as integers while the circle endpoints serve
 * grantOn as a camelCase string. Both shapes reach the same models, so both have to parse — and
 * the serializer runs strict, so a bare number would otherwise take the whole response down.
 */
class EnrollmentCandidatesSerializationTest {

    @Test
    fun candidatesParseWithAnIntegerGrantOn() {
        val json = """
            [{"circleId":"2942b454-2416-4f85-b378-42d5e25388ed","circleName":"Moments","grantOn":3,
              "candidates":[{"odinId":"sam.dotyou.cloud","reviewedAt":1757203200000},
                            {"odinId":"frodo.dotyou.cloud","reviewedAt":null}]}]
        """.trimIndent().replace("\n", "")

        val result = OdinSystemSerializer.deserialize<List<CircleEnrollmentCandidates>>(json)

        val circle = result.single()
        assertEquals(CircleGrantOn.Review, circle.grantOn)
        assertEquals(2, circle.candidates.size)
        assertEquals(1_757_203_200_000L, circle.candidates.first().reviewedAt)
        // Null on a Connect circle, where connecting rather than reviewing is what qualifies.
        assertEquals(null, circle.candidates.last().reviewedAt)
    }

    @Test
    fun aStringGrantOnStillParses() {
        val json = """[{"circleId":"aa","circleName":"Chat","grantOn":"connect","candidates":[]}]"""

        val result = OdinSystemSerializer.deserialize<List<CircleEnrollmentCandidates>>(json)

        assertEquals(CircleGrantOn.Connect, result.single().grantOn)
    }

    @Test
    fun anEmptyArrayMeansNothingToOffer() {
        assertTrue(
            OdinSystemSerializer.deserialize<List<CircleEnrollmentCandidates>>("[]").isEmpty()
        )
    }

    @Test
    fun outcomeKindsParseFromTheirIntegers() {
        val json = """
            {"enrolled":1,"deposited":2,"skipped":1,
             "outcomes":[{"odinId":"a.dotyou.cloud","kind":1},
                         {"odinId":"b.dotyou.cloud","kind":2},
                         {"odinId":"c.dotyou.cloud","kind":3}]}
        """.trimIndent().replace("\n", "")

        val result = OdinSystemSerializer.deserialize<EnrollmentResult>(json)

        assertEquals(1, result.enrolled)
        assertEquals(2, result.deposited)
        assertEquals(
            listOf(
                EnrollmentOutcomeKind.Enrolled,
                EnrollmentOutcomeKind.Deposited,
                EnrollmentOutcomeKind.Skipped,
            ),
            result.outcomes.map { it.kind },
        )
    }

    /** A Read circle reports deposits with zero enrolments, and that is success, not failure. */
    @Test
    fun aReadCircleReportsDepositsRatherThanMemberships() {
        val json = """{"enrolled":0,"deposited":3,"skipped":0,"outcomes":[]}"""

        val result = OdinSystemSerializer.deserialize<EnrollmentResult>(json)

        assertEquals(0, result.enrolled)
        assertEquals(3, result.deposited)
    }

    @Test
    fun theAddManyRequestCarriesTheCircleAndIdentities() {
        val json = OdinSystemSerializer.serialize(
            AddManyCircleMembershipRequest("aa", listOf("sam.dotyou.cloud", "frodo.dotyou.cloud"))
        )

        assertTrue(json.contains("\"circleId\":\"aa\""), json)
        assertTrue(json.contains("sam.dotyou.cloud"), json)
    }
}
