package id.homebase.core.vault

import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

private val log = Logger.withTag("LinuxPolkit")

// No distro ships a portable auth_self action, so the .deb installs this one
// (desktopApp/linux/*.policy); dev runs lack it and resolve to Unavailable.
internal const val ACTION_ID = "id.homebase.feed.vault-unlock"
internal const val POLKIT_ACTIONS_DIR = "/usr/share/polkit-1/actions"

internal suspend fun linuxAuthenticate(title: String, subtitle: String): BiometricResult =
    withContext(Dispatchers.IO) {
        val processArg = currentProcessArgument() ?: return@withContext BiometricResult.Unavailable
        val pkcheck = try {
            ProcessBuilder(
                "pkcheck",
                "--action-id", ACTION_ID,
                "--process", processArg,
                "--allow-user-interaction",
            ).redirectErrorStream(true).start()
        } catch (e: IOException) {
            log.d(e) { "pkcheck unavailable" }
            return@withContext BiometricResult.Unavailable
        }
        try {
            val exitCode = runInterruptible { pkcheck.waitFor() }
            val output = pkcheck.inputStream.bufferedReader().readText()
            if (isActionNotRegistered(output)) {
                log.d { "$ACTION_ID not registered — is the .deb's policy file installed?" }
                BiometricResult.Unavailable
            } else {
                mapPkcheckExitCode(exitCode).also {
                    if (it == BiometricResult.Unavailable) log.d { "pkcheck exit=$exitCode: ${output.trim()}" }
                }
            }
        } finally {
            if (pkcheck.isAlive) pkcheck.destroy()
        }
    }

internal fun isActionNotRegistered(pkcheckOutput: String): Boolean =
    pkcheckOutput.contains("is not registered")

private fun currentProcessArgument(): String? {
    val startTime = runCatching { File("/proc/self/stat").readText() }.getOrNull()
        ?.let(::parseProcStatStartTime)
        ?: return null
    val uid = runCatching { File("/proc/self/status").readText() }.getOrNull()
        ?.let(::parseProcStatusUid)
    return pkcheckProcessArgument(ProcessHandle.current().pid(), startTime, uid)
}

// pid,start-time,uid per pkcheck(1) — the bare pid or pid,start-time forms are documented as racy.
internal fun pkcheckProcessArgument(pid: Long, startTime: Long, uid: Long?): String =
    if (uid != null) "$pid,$startTime,$uid" else "$pid,$startTime"

// proc(5) field 22; comm (field 2) is parenthesized and may itself contain ')' or spaces, so
// skip past the LAST ')' rather than splitting the whole line.
internal fun parseProcStatStartTime(stat: String): Long? {
    val afterComm = stat.substringAfterLast(')')
    if (afterComm == stat) return null
    return afterComm.trim().split(Regex("\\s+")).getOrNull(19)?.toLongOrNull()
}

internal fun parseProcStatusUid(status: String): Long? {
    val line = status.lineSequence().firstOrNull { it.startsWith("Uid:") } ?: return null
    return line.removePrefix("Uid:").trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull()
}

// pkcheck(1): 0 authorized, 1 denied, 3 dismissed; 2 (no agent) and anything undocumented is Unavailable.
internal fun mapPkcheckExitCode(exitCode: Int): BiometricResult = when (exitCode) {
    0 -> BiometricResult.Success
    1, 3 -> BiometricResult.Failure
    else -> BiometricResult.Unavailable
}
