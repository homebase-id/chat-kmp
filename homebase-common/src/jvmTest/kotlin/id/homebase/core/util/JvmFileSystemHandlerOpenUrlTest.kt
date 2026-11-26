package id.homebase.core.util

import kotlin.test.Test
import kotlin.test.assertIs

class JvmFileSystemHandlerOpenUrlTest {
    @Test
    fun aUrlWithNoSchemeCallsOnError() {
        var error: Throwable? = null
        JvmFileSystemHandler().openUrl("your-na-report-content-url-here") { error = it }
        assertIs<IllegalArgumentException>(error)
    }
}
