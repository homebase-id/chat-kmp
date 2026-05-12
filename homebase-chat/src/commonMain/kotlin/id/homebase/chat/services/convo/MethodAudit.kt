package id.homebase.chat.services.convo

import co.touchlab.kermit.Logger

/**
 * Lightweight per-method diagnostic harness used across conversation services
 * (`ConversationService`, `GroupHealService`) to emit START / STEP / POST /
 * DIAGNOSIS / END lines under the `ConvoAudit` log tag. The class collects
 * `checkPass` / `checkFail` / `checkWarn` / `threw` outcomes and rolls them
 * into a single verdict at `finish()`.
 *
 * To remove the audit from a method:
 *   1. Drop the `MethodAudit(...)` construction at the top.
 *   2. Remove `audit.*` calls in the body, plus the surrounding
 *      `// ---- DEBUG instrumentation` / `// ---- end DEBUG` fence.
 *
 * `internal` so any class inside `homebase-chat` can construct an audit
 * (e.g. heal handlers, conversation orchestration). Not exposed outside the
 * module — consumers shouldn't depend on the audit shape.
 */
class MethodAudit(private val method: String) {
    companion object { const val TAG = "ConvoAudit" }
    private val checks = mutableListOf<String>()

    fun start(detail: String = "") {
        Logger.i(tag = TAG) { "═════ $method START $detail ═════" }
    }
    fun pre(line: String) { Logger.i(tag = TAG) { "[$method] PRE $line" } }
    fun step(num: Int, what: String) { Logger.i(tag = TAG) { "[$method] STEP $num $what" } }
    fun post(line: String) { Logger.i(tag = TAG) { "[$method] POST $line" } }
    fun info(line: String) { Logger.i(tag = TAG) { "[$method] $line" } }

    fun checkPass(name: String) { checks += "$name=PASS" }
    fun checkFail(name: String, bugMessage: String) {
        checks += "$name=FAIL"
        Logger.w(tag = TAG) { "[$method] BUG? $name: $bugMessage" }
    }
    fun checkWarn(name: String, bugMessage: String) {
        checks += "$name=WARN"
        Logger.w(tag = TAG) { "[$method] BUG? $name: $bugMessage" }
    }
    fun check(name: String, pass: Boolean, failBugMessage: String) {
        if (pass) checkPass(name) else checkFail(name, failBugMessage)
    }
    fun threw(stepLabel: String, e: Throwable) {
        checks += "$stepLabel=THREW"
        Logger.e(throwable = e, tag = TAG) {
            "[$method] BUG? $stepLabel threw: ${e::class.simpleName}: ${e.message}"
        }
    }
    fun finish(extra: String = "") {
        val verdict = when {
            checks.any { it.endsWith("=FAIL") || it.endsWith("=THREW") } -> "FAIL"
            checks.any { it.endsWith("=WARN") } -> "WARN"
            else -> "OK"
        }
        Logger.i(tag = TAG) {
            "DIAGNOSIS: $method verdict=$verdict ${checks.joinToString(" ")} $extra".trim()
        }
        Logger.i(tag = TAG) { "═════ $method END ═════" }
    }
}
