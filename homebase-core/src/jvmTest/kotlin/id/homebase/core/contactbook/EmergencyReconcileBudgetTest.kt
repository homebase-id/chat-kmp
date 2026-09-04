package id.homebase.core.contactbook

import id.homebase.api.client.ClientException
import id.homebase.api.client.NotFoundException
import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.ProblemDetails
import id.homebase.api.client.ServerException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the two bounds that keep the every-app-start sweep flat as the contact book grows
 * (issue #1243): the per-peer TTL and the per-pass budget.
 */
class EmergencyReconcileBudgetTest {

    private val now = 1_000_000_000_000L

    @Test
    fun `a peer probed inside the TTL is skipped`() {
        val targets = selectProbeTargets(
            candidates = setOf("a.example.com"),
            lastAttemptMs = mapOf("a.example.com" to now - 1),
            nowMs = now,
            ttlMs = RECONCILE_TTL_MS,
            budget = RECONCILE_BUDGET,
        )
        assertEquals(emptyList(), targets)
    }

    @Test
    fun `a peer probed longer ago than the TTL is probed again`() {
        val targets = selectProbeTargets(
            candidates = setOf("a.example.com"),
            lastAttemptMs = mapOf("a.example.com" to now - RECONCILE_TTL_MS),
            nowMs = now,
            ttlMs = RECONCILE_TTL_MS,
            budget = RECONCILE_BUDGET,
        )
        assertEquals(listOf("a.example.com"), targets)
    }

    @Test
    fun `a never-probed peer is a target`() {
        val targets = selectProbeTargets(
            candidates = setOf("a.example.com"),
            lastAttemptMs = emptyMap(),
            nowMs = now,
            ttlMs = RECONCILE_TTL_MS,
            budget = RECONCILE_BUDGET,
        )
        assertEquals(listOf("a.example.com"), targets)
    }

    @Test
    fun `a thousand-contact book costs the budget, not the book`() {
        val candidates = (1..1000).map { "peer$it.example.com" }.toSet()
        val targets = selectProbeTargets(
            candidates = candidates,
            lastAttemptMs = emptyMap(),
            nowMs = now,
            ttlMs = RECONCILE_TTL_MS,
            budget = RECONCILE_BUDGET,
        )
        assertEquals(RECONCILE_BUDGET, targets.size)
    }

    @Test
    fun `the oldest attempts are probed first so the book cycles`() {
        val targets = selectProbeTargets(
            candidates = setOf("new.example.com", "old.example.com", "never.example.com"),
            lastAttemptMs = mapOf(
                "new.example.com" to now - RECONCILE_TTL_MS,
                "old.example.com" to now - RECONCILE_TTL_MS * 4,
            ),
            nowMs = now,
            ttlMs = RECONCILE_TTL_MS,
            budget = 2,
        )
        assertEquals(listOf("never.example.com", "old.example.com"), targets)
    }

    @Test
    fun `a timestamp in the future is treated as stale, not skipped forever`() {
        val targets = selectProbeTargets(
            candidates = setOf("a.example.com"),
            lastAttemptMs = mapOf("a.example.com" to now + RECONCILE_TTL_MS),
            nowMs = now,
            ttlMs = RECONCILE_TTL_MS,
            budget = RECONCILE_BUDGET,
        )
        assertEquals(listOf("a.example.com"), targets)
    }

    @Test
    fun `a 400 is the server's verdict and starts the TTL`() {
        assertTrue(isConclusiveFailure(badRequest()))
    }

    @Test
    fun `a 404 is conclusive too`() {
        assertTrue(isConclusiveFailure(NotFoundException()))
    }

    @Test
    fun `a 5xx does not start the TTL`() {
        assertFalse(isConclusiveFailure(ServerException(503, null, null)))
    }

    @Test
    fun `a transport failure does not buy a week of silence`() {
        assertFalse(isConclusiveFailure(RuntimeException("connection reset")))
    }

    private fun badRequest() = ClientException(
        status = 400,
        errorCode = OdinClientErrorCode.UnhandledScenario,
        message = "Invalid request",
        correlationId = null,
        problem = ProblemDetails(),
    )
}
