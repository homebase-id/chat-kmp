package id.homebase.core.diagnostics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What a run of on-device checks found: [failures] is empty when every check passed; [measurements] are the numbers they took. */
class CheckReport(val failures: List<String>, val measurements: List<String>)

/** One run of named checks: a check that throws is recorded as a failure and the run goes on. */
class CheckRun {
    private val failures = mutableListOf<String>()
    private val measurements = mutableListOf<String>()

    fun measured(line: String) {
        measurements += line
    }

    fun fail(line: String) {
        failures += line
    }

    suspend fun verify(name: String, check: suspend () -> Unit) {
        try {
            check()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failures += "$name: ${e.message ?: e}"
        }
    }

    internal fun report() = CheckReport(failures.toList(), measurements.toList())
}

suspend fun checkRun(checks: suspend CheckRun.() -> Unit): CheckReport =
    withContext(Dispatchers.Default) { CheckRun().apply { checks() }.report() }

/** [checks] for Swift, which can't call a suspend function: [onDone] is called back on a background thread. */
fun launchChecks(checks: suspend () -> CheckReport, onDone: (CheckReport) -> Unit) {
    CoroutineScope(Dispatchers.Default).launch { onDone(checks()) }
}
