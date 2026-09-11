package id.homebase.api.client

import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 3012 is the one error whose body the UI has to read structurally: it names every circle blocking
 * the un-review, so the user can be told the whole list instead of discovering it one rejection at
 * a time.
 */
class BlockingCirclesTest {

    @Test
    fun theServerShapeParsesWithTheCircles() {
        val json = """
            {"title":"Cannot clear the review for frodo.dotyou.cloud while they are a member of the
             personal circle(s) 'family', 'work'; remove them from those first",
             "status":400,"errorCode":3012,"correlationId":"abc",
             "blockingCircles":[
               {"circleId":"cefc4f7cbc8c34762e0f76703e7e174e","name":"family"},
               {"circleId":"3d594614f445f6b00014e9b77730b833","name":"work"}]}
        """.trimIndent().replace("\n", "")

        val problem = OdinSystemSerializer.deserialize<ProblemDetails>(json)

        assertEquals(
            OdinClientErrorCode.CannotClearReviewWhilePersonalCircleMember,
            problem.errorCodeEnumOrUnhandled(),
        )
        assertEquals(listOf("family", "work"), problem.blockingCircles().map { it.name })
    }

    /** Ids come through GuidIdConverter, so they match a circle definition's id as-is. */
    @Test
    fun theIdMatchesACircleDefinitionIdWithoutParsing() {
        val json = """{"status":400,"errorCode":3012,
            "blockingCircles":[{"circleId":"cefc4f7cbc8c34762e0f76703e7e174e","name":"family"}]}"""
            .trimIndent().replace("\n", "")

        val id = OdinSystemSerializer.deserialize<ProblemDetails>(json).blockingCircles().single().circleId

        assertEquals(32, id.length)
        assertTrue(id.none { it == '-' }, id)
    }

    /** Every other error in the codebase omits the bag entirely. */
    @Test
    fun anErrorWithoutTheBagYieldsAnEmptyList() {
        val problem = OdinSystemSerializer
            .deserialize<ProblemDetails>("""{"status":400,"errorCode":3006,"title":"nope"}""")

        assertTrue(problem.blockingCircles().isEmpty())
    }

    /** The nested-extensions fallback the rest of this file already tolerates. */
    @Test
    fun theNestedExtensionsShapeAlsoParses() {
        val json = """{"status":400,
            "extensions":{"errorCode":"3012",
            "blockingCircles":[{"circleId":"aa","name":"family"}]}}""".trimIndent().replace("\n", "")

        val problem = OdinSystemSerializer.deserialize<ProblemDetails>(json)

        assertEquals(listOf("family"), problem.blockingCircles().map { it.name })
    }
}
