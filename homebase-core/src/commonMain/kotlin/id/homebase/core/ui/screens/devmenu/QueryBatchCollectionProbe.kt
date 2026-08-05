package id.homebase.core.ui.screens.devmenu

import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.CollectionQueryParamSection
import id.homebase.api.client.drives.CollectionSectionResultOptions
import id.homebase.api.client.drives.QueryBatchCollectionRequest
import id.homebase.api.client.drives.QueryBatchCollectionSection
import id.homebase.api.client.drives.QueryBatchSectionStatus
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.drives.query.FileQueryParams
import id.homebase.core.config.chatLabeledDrive
import id.homebase.core.config.contactLabeledDrive
import id.homebase.core.config.profileLabeledDrive
import kotlin.uuid.Uuid

/**
 * Temporary acceptance probe for the V2 `query-batch-collection` contract (odin-core#1629 /
 * chat-kmp#1102). Delete once the sync engine uses the endpoint in production — at that point
 * the real thing exercises the same paths.
 *
 * It answers three things unit tests can't: whether the *deployed* tenant has the new server,
 * whether the client's encrypted-envelope decode of the new response shape works end to end, and
 * whether the budget/cursor algorithm `syncAll()` is about to implement actually holds up.
 *
 * Read-only — issues queries and writes nothing.
 */
class QueryBatchCollectionProbe(
    private val driveQueryProvider: DriveQueryProvider,
) {

    suspend fun run(): String {
        Logger.i(tag = TAG) { "── query-batch-collection probe ──" }

        val happy = probeHappyPath()
        val missing = probeNonExistentDrive()
        val ungranted = probeUngrantedDrive()
        val budget = probeBudgetExhaustion()
        val drain = probeDrainLoop()

        val verdicts = listOf(happy, missing, ungranted, budget, drain)
        val failed = verdicts.filterNot { it.passed }
        Logger.i(tag = TAG) { "── probe done: ${verdicts.size - failed.size}/${verdicts.size} passed ──" }

        return if (failed.isEmpty()) {
            "query-batch-collection: all ${verdicts.size} phases passed (log tag $TAG)"
        } else {
            "query-batch-collection: ${failed.size}/${verdicts.size} FAILED — ${failed.joinToString { it.name }} (log tag $TAG)"
        }
    }

    /** Three real drives, generous budget: proves rows decode and sections match back by name. */
    private suspend fun probeHappyPath(): Verdict = phase("happy-path") {
        val response = collection(MANDATORY, budget = 1000)
        logSections(response)

        // A null status on every section means the tenant predates odin-core#1630 — the single
        // most useful thing this probe can tell us, and it invalidates every phase below.
        if (response.all { it.status == null }) {
            return@phase fail("no section carried a status — deployed server predates odin-core#1630")
        }
        val names = response.mapNotNull { it.name }
        if (names != MANDATORY.map { it.label }) {
            return@phase fail("section names/order did not round-trip: got $names")
        }
        if (response.any { it.status != QueryBatchSectionStatus.Ok }) {
            return@phase fail("a granted drive did not report ok")
        }
        pass("${response.sumOf { it.searchResults.size }} rows across ${response.size} sections")
    }

    /** The call that used to 500 the whole collection. */
    private suspend fun probeNonExistentDrive(): Verdict = phase("non-existent-drive") {
        val sections = MANDATORY + ProbeDrive("ghost", Uuid.random())
        val response = collection(sections, budget = 100)
        logSections(response)

        val ghost = response.firstOrNull { it.name == "ghost" }
            ?: return@phase fail("no section returned for the non-existent drive")
        if (ghost.status != QueryBatchSectionStatus.DriveNotFound) {
            return@phase fail("expected driveNotFound, got ${ghost.status}")
        }
        if (response.filter { it.name != "ghost" }.any { it.isFailure }) {
            return@phase fail("a healthy section was collateral damage")
        }
        pass("collection survived; ghost=driveNotFound, ${response.size - 1} healthy sections intact")
    }

    /** walletDrive exists on every identity but chat never requests read on it. */
    private suspend fun probeUngrantedDrive(): Verdict = phase("ungranted-drive") {
        val wallet = ProbeDrive("wallet", SystemDriveConstants.walletDrive.alias)
        val response = collection(MANDATORY + wallet, budget = 100)
        logSections(response)

        val section = response.firstOrNull { it.name == "wallet" }
            ?: return@phase fail("no section returned for the ungranted drive")
        // driveNotFound is also an acceptable outcome — it just means this tenant never
        // provisioned a wallet drive, which tells us the same thing about fault isolation.
        if (section.status != QueryBatchSectionStatus.NoReadGrant &&
            section.status != QueryBatchSectionStatus.DriveNotFound
        ) {
            return@phase fail("expected noReadGrant or driveNotFound, got ${section.status}")
        }
        if (!section.invalidDrive) {
            return@phase fail("invalidDrive was not set on a failed section")
        }
        pass("wallet=${section.status}, healthy sections unaffected")
    }

    /**
     * The semantic the whole sync design rests on: the budget is global, and a section the budget
     * never reached comes back with the cursor we submitted, echoed unchanged.
     *
     * Section 1 starts from a null cursor so it is guaranteed to have a row to spend the budget on;
     * sections 2 and 3 carry real cursors harvested from a prior round, so "echoed verbatim" is
     * asserted against a non-trivial value rather than null-equals-null.
     */
    private suspend fun probeBudgetExhaustion(): Verdict = phase("budget-exhaustion") {
        val harvested = collection(MANDATORY, budget = 1000)
        val cursorsByName = harvested.associate { it.name to it.cursorState }

        val sections = MANDATORY.mapIndexed { index, drive ->
            if (index == 0) drive else drive.copy(cursorState = cursorsByName[drive.label])
        }
        val response = collection(sections, budget = 1)
        logSections(response)

        val served = response.sumOf { it.searchResults.size }
        val exhausted = response.filter { it.status == QueryBatchSectionStatus.BudgetExhausted }
        if (exhausted.isEmpty()) {
            return@phase if (served == 0) {
                // Nothing to page anywhere, so the budget was never spent. Says nothing about the
                // contract either way — don't report it as a failure.
                pass("INCONCLUSIVE — no rows available on any drive to exhaust the budget")
            } else {
                fail("budget=1 over ${sections.size} sections produced no budgetExhausted section")
            }
        }

        val submitted = sections.associate { it.label to it.cursorState }
        val notEchoed = exhausted.filter { it.cursorState != submitted[it.name] }
        if (notEchoed.isNotEmpty()) {
            return@phase fail(
                "budgetExhausted cursor not echoed verbatim: " +
                    notEchoed.joinToString { "${it.name} sent=${submitted[it.name]} got=${it.cursorState}" }
            )
        }
        if (exhausted.any { it.searchResults.isNotEmpty() }) {
            return@phase fail("a budgetExhausted section carried rows")
        }
        if (exhausted.any { it.isFailure }) {
            return@phase fail("budgetExhausted was reported as a failure")
        }
        if (served > 1) return@phase fail("budget=1 but $served rows came back")
        pass("$served row served, ${exhausted.size} section(s) budgetExhausted with cursors echoed")
    }

    /**
     * Dry run of the loop `syncAll()` is about to implement: re-send only the sections that still
     * report [QueryBatchCollectionSection.needsAnotherRound], carrying each one's returned cursor.
     * Bounded by rounds rather than run to exhaustion — a real account would page for a long time.
     */
    private suspend fun probeDrainLoop(): Verdict = phase("drain-loop") {
        var pending = MANDATORY
        val seenFileIds = mutableSetOf<String>()
        var duplicates = 0
        var rounds = 0
        var total = 0

        while (pending.isNotEmpty() && rounds < MAX_DRAIN_ROUNDS) {
            rounds++
            val response = collection(pending, budget = DRAIN_BUDGET)
            val roundRows = response.sumOf { it.searchResults.size }
            total += roundRows

            for (section in response) {
                for (file in section.searchResults) {
                    if (!seenFileIds.add(file.fileId.toString())) duplicates++
                }
            }
            Logger.i(tag = TAG) {
                "  round $rounds: ${pending.size} sections → $roundRows rows, " +
                    "still paging=${response.count { it.needsAnotherRound }}"
            }
            response.filter { it.isFailure }.forEach {
                Logger.w(tag = TAG) { "  ${it.describeFailure()}" }
            }

            val byName = response.associateBy { it.name }
            pending = pending.mapNotNull { drive ->
                val section = byName[drive.label] ?: return@mapNotNull null
                if (section.needsAnotherRound) drive.copy(cursorState = section.cursorState) else null
            }
        }

        if (duplicates > 0) return@phase fail("$duplicates file(s) returned more than once across rounds")
        pass("$rounds round(s), $total rows, no duplicates, ${pending.size} section(s) still paging")
    }

    private suspend fun collection(drives: List<ProbeDrive>, budget: Int): List<QueryBatchCollectionSection> =
        driveQueryProvider.queryBatchCollection(
            QueryBatchCollectionRequest(
                queries = drives.map { drive ->
                    CollectionQueryParamSection(
                        name = drive.label,
                        driveId = drive.driveId,
                        queryParams = FileQueryParams(),
                        resultOptionsRequest = CollectionSectionResultOptions(
                            cursorState = drive.cursorState,
                            includeMetadataHeader = true,
                        ),
                    )
                },
                maxRecords = budget,
            )
        ).results

    private fun logSections(sections: List<QueryBatchCollectionSection>) {
        for (section in sections) {
            Logger.i(tag = TAG) {
                "  section=${section.name} status=${section.status} rows=${section.searchResults.size} " +
                    "hasMoreRows=${section.hasMoreRows} invalidDrive=${section.invalidDrive}"
            }
            if (section.isFailure) Logger.w(tag = TAG) { "  ${section.describeFailure()}" }
        }
    }

    private suspend fun phase(name: String, body: suspend () -> PhaseResult): Verdict {
        Logger.i(tag = TAG) { "▸ $name" }
        return try {
            when (val result = body()) {
                is PhaseResult.Pass -> {
                    Logger.i(tag = TAG) { "  PASS — ${result.detail}" }
                    Verdict(name, passed = true)
                }

                is PhaseResult.Fail -> {
                    Logger.e(tag = TAG) { "  FAIL — ${result.reason}" }
                    Verdict(name, passed = false)
                }
            }
        } catch (e: Exception) {
            Logger.e(tag = TAG, throwable = e) { "  FAIL — threw ${e::class.simpleName}: ${e.message}" }
            Verdict(name, passed = false)
        }
    }

    private fun pass(detail: String): PhaseResult = PhaseResult.Pass(detail)
    private fun fail(reason: String): PhaseResult = PhaseResult.Fail(reason)

    private data class ProbeDrive(val label: String, val driveId: Uuid, val cursorState: String? = null)

    private data class Verdict(val name: String, val passed: Boolean)

    private sealed interface PhaseResult {
        data class Pass(val detail: String) : PhaseResult
        data class Fail(val reason: String) : PhaseResult
    }

    private companion object {
        const val TAG = "QBCollection"
        const val MAX_DRAIN_ROUNDS = 5
        const val DRAIN_BUDGET = 50

        val MANDATORY = listOf(
            ProbeDrive("chat", chatLabeledDrive.drive.alias),
            ProbeDrive("contacts", contactLabeledDrive.drive.alias),
            ProbeDrive("profile", profileLabeledDrive.drive.alias),
        )
    }
}
