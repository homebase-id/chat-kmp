package id.homebase.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import dev.nucleusframework.composenativetray.utils.ComposeNativeTrayLoggingLevel
import dev.nucleusframework.composenativetray.utils.allowComposeNativeTrayLogging
import dev.nucleusframework.composenativetray.utils.composeNativeTrayLoggingLevel
import kotlinx.coroutines.delay
import java.io.File
import kotlin.system.exitProcess

// CI launch check: set to a file path to make the app exit 0 after its first frame instead of idling.
private val smokeMarker: String? = System.getenv("HOMEBASE_SMOKE_MARKER")

internal fun configureSmokeLogging() {
    if (smokeMarker == null) return
    allowComposeNativeTrayLogging = true
    composeNativeTrayLoggingLevel = ComposeNativeTrayLoggingLevel.ERROR
}

// The crash dialog is modal and would hang CI.
internal fun failSmokeRun(throwable: Throwable) {
    if (smokeMarker == null) return
    throwable.printStackTrace()
    exitProcess(1)
}

// Non-daemon threads keep the JVM alive after exitApplication, hence exitProcess; the delay lets tray init run.
@Composable
internal fun SmokeExit() {
    val marker = smokeMarker ?: return
    LaunchedEffect(Unit) {
        withFrameNanos { }
        delay(5_000)
        File(marker).writeText("ok")
        println("HOMEBASE_SMOKE_OK")
        exitProcess(0)
    }
}
