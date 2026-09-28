package id.homebase.api.client.drives.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PayloadDescriptorInternalDescriptorTest {

    @Test
    fun descriptorKeys_areInternal() {
        assertTrue(PayloadDescriptor(key = "pld_desc0").isInternalDescriptor())
        assertTrue(PayloadDescriptor(key = "pld_desc12").isInternalDescriptor())
    }

    @Test
    fun ordinaryKeys_areNotInternal() {
        assertFalse(PayloadDescriptor(key = "chat_web0").isInternalDescriptor())
        assertFalse(PayloadDescriptor(key = "vlt_pg_00").isInternalDescriptor())
        assertFalse(PayloadDescriptor(key = "x_pld_desc0").isInternalDescriptor())
    }

    @Test
    fun withoutInternalDescriptors_keepsOnlyRealPayloadsInOrder() {
        val video = PayloadDescriptor(key = "mmnt_pl0", contentType = "video/mp4")
        val image = PayloadDescriptor(key = "mmnt_pl1", contentType = "image/jpeg")
        val descriptor = PayloadDescriptor(key = "pld_desc0", contentType = "application/json")

        assertEquals(listOf(video, image), listOf(video, descriptor, image).withoutInternalDescriptors())
    }
}
