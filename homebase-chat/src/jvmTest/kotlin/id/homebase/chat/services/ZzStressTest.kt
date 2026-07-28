package id.homebase.chat.services

import kotlin.test.Test

/** TEMPORARY Linux-CI stress harness — delete before this branch is finalised. */
class ZzStressTest {

    private val report = StringBuilder()

    private fun stress(name: String, n: Int, body: () -> Unit) {
        var failures = 0
        val seen = LinkedHashMap<String, Int>()
        repeat(n) {
            try {
                body()
            } catch (t: Throwable) {
                failures++
                val key = "${t::class.qualifiedName}: ${t.message?.take(300)}"
                seen[key] = (seen[key] ?: 0) + 1
            }
        }
        report.appendLine("STRESS $name: $failures/$n failed")
        seen.forEach { (k, v) -> report.appendLine("   [$v x] $k") }
    }

    @Test
    fun stressAll() {
        val n = 80
        stress("viaDriveSyncStopped", n) {
            ChatMessageStreamLoadRaceTest()
                .loadConversation_rowCommittedMidFetch_isRecoveredViaDriveSyncStopped()
        }
        stress("viaBatchReceived", n) {
            ChatMessageStreamLoadRaceTest()
                .loadConversation_rowCommittedMidFetch_isRecoveredViaBatchReceived()
        }
        stress("survivesTheWriteBack", n) {
            ChatMessageStreamLoadRaceTest()
                .loadConversation_rowCommittedDuringTheReRead_survivesTheWriteBack()
        }
        stress("loadAround", n) {
            ChatMessageStreamLoadRaceTest()
                .loadConversationAroundMessage_rowCommittedMidFetch_isRecovered()
        }
        stress("onePagingQuery", n) {
            ChatMessageStreamLoadRaceTest()
                .loadConversation_withoutAConcurrentWrite_issuesOnePagingQuery()
        }
        // Always fail so the report reaches the CI log via the FULL exception format.
        throw AssertionError("STRESS REPORT\n$report")
    }
}
