package id.homebase.core.notifications

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SilentPushTest {
    private fun push(silent: Boolean) = mapOf(
        "data" to """{"senderId":"a.example","options":{"appId":"app","typeId":"type","silent":$silent}}""",
    )

    @Test
    fun aSilentPushIsSilent() {
        assertTrue(isSilentPush(push(silent = true)))
    }

    @Test
    fun aNormalPushIsNot() {
        assertFalse(isSilentPush(push(silent = false)))
    }

    @Test
    fun aPayloadThatCannotBeReadIsNot() {
        assertFalse(isSilentPush(emptyMap()))
        assertFalse(isSilentPush(mapOf("data" to "not json")))
    }
}
