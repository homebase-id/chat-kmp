package id.homebase.feed.calls

import android.app.Application
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import id.homebase.feed.MainActivity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class IncomingCallOpenIntentTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    // The system resolves every activity start (PendingIntent sends included) with MATCH_DEFAULT_ONLY.
    @Test
    fun `answer intent resolves to MainActivity the way the system resolves an activity start`() {
        val intent = IncomingCallNotifier.openIntent(context, "call-1", accept = true)

        val resolved = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)

        assertEquals(MainActivity::class.java.name, resolved?.activityInfo?.name)
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals("call-1", intent.getStringExtra(IncomingCallNotifier.EXTRA_CALL_ID))
        assertTrue(intent.getBooleanExtra(IncomingCallNotifier.EXTRA_ACCEPT, false))
    }

    @Test
    fun `open intent resolves to MainActivity without accepting`() {
        val intent = IncomingCallNotifier.openIntent(context, "call-2", accept = false)

        val resolved = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)

        assertEquals(MainActivity::class.java.name, resolved?.activityInfo?.name)
        assertEquals(false, intent.hasExtra(IncomingCallNotifier.EXTRA_ACCEPT))
    }
}
