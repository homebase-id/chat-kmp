package id.homebase.core.util

import kotlin.test.Test
import kotlin.test.assertFalse

class LocalPathExistsWebTest {
    @Test
    fun anyInput_isFalse() {
        assertFalse(localPathExists("/tmp"))
        assertFalse(localPathExists(""))
    }
}
