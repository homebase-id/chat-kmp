package id.homebase.core.ui.screens.card

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** Run with a device attached: `./gradlew :homebase-core:connectedAndroidDeviceTest`. */
@RunWith(AndroidJUnit4::class)
class AndroidCardHostSnapshotTest {

    @Test
    fun aLaterSnapshotLeavesAnEarlierOneUntouched() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var host: AndroidCardHost
            scenario.onActivity { activity ->
                host = AndroidCardHost(activity, "about:blank")
                activity.setContentView(host.attach(activity))
                host.webView.setBackgroundColor(Color.RED)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                val first = assertNotNull(runBlocking { host.snapshot() }).asAndroidBitmap()
                host.webView.setBackgroundColor(Color.BLUE)
                val second = assertNotNull(runBlocking { host.snapshot() }).asAndroidBitmap()

                assertNotSame(first, second)
                assertEquals(Color.RED, first.getPixel(first.width / 2, first.height / 2))
                assertEquals(Color.BLUE, second.getPixel(second.width / 2, second.height / 2))
                host.dispose()
            }
        }
    }
}
