package id.homebase.photobase.backup

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupUploadVerdictTest {
    @Test
    fun aSuccessLanded() {
        assertTrue(backgroundUploadLanded(200, "{}"))
        assertTrue(backgroundUploadLanded(204, null))
    }

    @Test
    fun aFileAlreadyThereLanded() {
        assertTrue(backgroundUploadLanded(400, """{"status":400,"title":"File already exists with ClientUniqueId: x"}"""))
    }

    @Test
    fun anythingElseGoesBackForARetry() {
        assertFalse(backgroundUploadLanded(0, null), "no response")
        assertFalse(backgroundUploadLanded(400, """{"status":400,"title":"nope"}"""))
        assertFalse(backgroundUploadLanded(400, "not json"))
        assertFalse(backgroundUploadLanded(401, null))
        assertFalse(backgroundUploadLanded(507, null))
        assertFalse(backgroundUploadLanded(500, ""))
    }
}
