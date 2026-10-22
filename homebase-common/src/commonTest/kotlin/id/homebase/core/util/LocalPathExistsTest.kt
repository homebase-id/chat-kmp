package id.homebase.core.util

import kotlin.test.Test
import kotlin.test.assertFalse

class LocalPathExistsTest {
    @Test
    fun missingPath_isFalse() {
        assertFalse(localPathExists("/definitely/does/not/exist/image.jpg"))
    }
}
