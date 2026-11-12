package id.homebase.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PlatformContractTest {
    @Test
    fun platformFlagsMatchGetPlatform() {
        val type = getPlatform().name
        assertEquals(type == PlatformType.ANDROID, isAndroid())
        assertEquals(type == PlatformType.IOS, isIos())
        assertFalse(isAndroid() && isIos())
    }
}
