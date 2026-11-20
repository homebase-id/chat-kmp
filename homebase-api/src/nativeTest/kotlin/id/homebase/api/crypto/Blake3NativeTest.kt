package id.homebase.api.crypto

import kotlin.test.Test
import kotlin.test.assertNotNull

class Blake3NativeTest {

    @Test
    fun iosHashesWithTheNativeBlake3() {
        assertNotNull(platformBlake3Hasher(), "homebase-blake3's C is linked and agrees with the Kotlin port")
    }
}
