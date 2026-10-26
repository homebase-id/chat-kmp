package id.homebase.chat.calling

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import co.touchlab.kermit.Logger
import id.homebase.chat.services.calling.CallLogReport
import id.homebase.core.logging.LogFileExporter
import id.homebase.core.logging.LoggerConfig
import id.homebase.core.util.Platform
import id.homebase.core.util.PlatformInfo
import id.homebase.core.util.getUriHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import org.koin.mp.KoinPlatform

internal suspend fun exportCallReport(callId: String, versionName: String, versionCode: Int): Path? {
    val dir = LoggerConfig.logDirectory ?: return null
    val header = listOf(
        "Homebase call report",
        "call=$callId",
        "app=$versionName ($versionCode)",
        "platform=${Platform.osName} ${Platform.osVersion}",
    )
    return withContext(Dispatchers.Default) {
        LogFileExporter.exportSelection(dir, CallLogReport.exportName(callId)) { files ->
            CallLogReport.select(files, callId).takeIf { it.isNotEmpty() }?.let { CallLogReport.render(header, it) }
        }
    }
}

/** Null where no log file exists to share (web), so the action is not offered. */
@Composable
internal fun rememberCallProblemReporter(callId: () -> String?): ((onUnavailable: () -> Unit) -> Unit)? {
    val handler = getUriHandler()
    val scope = rememberCoroutineScope()
    val available = LoggerConfig.logDirectory != null
    return remember(handler, scope, available) {
        if (!available) return@remember null
        { onUnavailable: () -> Unit ->
            val id = callId()
            scope.launch {
                val info = KoinPlatform.getKoinOrNull()?.getOrNull<PlatformInfo>()
                val file = id?.let { exportCallReport(it, info?.versionName ?: "unknown", info?.versionCode ?: 0) }
                if (file == null) onUnavailable()
                else handler.shareFile(file) { Logger.w(it) { "call report share failed" } }
            }
            Unit
        }
    }
}
